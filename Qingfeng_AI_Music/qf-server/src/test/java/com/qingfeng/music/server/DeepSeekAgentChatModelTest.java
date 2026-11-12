package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.agent.api.AgentApplicationActions;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.DeepSeekAgentChatModel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekAgentChatModelTest {
    @Test void completeApplicationCatalogAndCurrentLyricsFitTheReservedPromptBudget() throws Exception {
        List<AgentApplicationActions> applications=List.of(
                new com.qingfeng.music.server.agent.AgentLibraryActions(null,null,null,null,null,null,null,null),
                new com.qingfeng.music.server.agent.AgentStudioActions(null,null,null,null,null,null,null),
                new com.qingfeng.music.server.agent.AgentAccountActions(null,null,null,null,null,null,null,null,null,null),
                new com.qingfeng.music.server.agent.retrieval.SemanticRetrievalActions(null,null,null));
        var service=new com.qingfeng.music.server.agent.AgentChatService(null,null,null,null,null,applications);
        var capabilities=service.capabilities();assertEquals(67,capabilities.size());
        for(int lyricCharacters:List.of(4000,8000)) {
            var song=new SongCard("work","给妈妈","温暖民谣","歌词".repeat(lyricCharacters/2),"","DRAFT",null,1,"revision-1",null,null);
            withResponse("可以，我们先聊这首歌。",(model,request)->{
                try(var scope=new com.qingfeng.music.common.concurrent.AgentRequestScope(java.time.Duration.ofSeconds(10),65536).enter()){
                    assertEquals("chat",model.respond(new AgentChatModel.Context("先聊这首歌","zh-CN",List.of(),song,null,capabilities,null)).action());
                    assertEquals(capabilities.size(),new ObjectMapper().readTree(request.get()).path("tools").size());
                    assertTrue(request.get().getBytes(StandardCharsets.UTF_8).length<=65536);
                }
            });
        }
    }

    @Test void oversizedHttpBodyIsCancelledBeforeUnboundedResponseBuffering() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var finishResponse=new java.util.concurrent.CountDownLatch(1);
        server.createContext("/chat",exchange->{
            exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);
            try {
                byte[] chunk=" ".repeat(16384).getBytes(StandardCharsets.UTF_8);
                for(int i=0;i<20;i++){exchange.getResponseBody().write(chunk);exchange.getResponseBody().flush();}
                // No EOF until the caller returns: an unbounded ofString() handler would keep waiting.
                try {finishResponse.await(10,java.util.concurrent.TimeUnit.SECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } catch(java.io.IOException expectedCancellation) {
                // The subscriber cancels the exchange after its bounded byte allowance.
            } finally {exchange.close();}
        });server.start();
        try {
            var model=new DeepSeekAgentChatModel("test-only-key","test-model",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient());
            var error=assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->assertThrows(DomainException.class,()->model.respond(new AgentChatModel.Context("你好","zh-CN",List.of(),null))));
            assertEquals("AGENT_CHAT_UNAVAILABLE",error.code());
        } finally {finishResponse.countDown();server.stop(0);}
    }

    @Test void nativeFunctionCallsUseServerSchemasAndReturnOneOrderedPlanFromOneHttpCall() throws Exception {
        var received=new AtomicReference<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            calls.incrementAndGet();received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var first=Map.of("id","call-1","type","function","function",Map.of("name","select_work","arguments","{\"workId\":\"work-2\"}"));
            var second=Map.of("id","call-2","type","function","function",Map.of("name","rename_work","arguments","{\"title\":\"晴天\"}"));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",Map.of("tool_calls",List.of(first,second))))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var model=new DeepSeekAgentChatModel("test-only-key","test-model",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient());
            var capabilities=List.of(new AgentApplicationActions.Capability("select_work","选择作品","workId,index","write"),new AgentApplicationActions.Capability("rename_work","修改名称","title","write"));
            var plan=model.respond(new AgentChatModel.Context("打开第二首并改名晴天","zh-CN",List.of(),null,null,capabilities,null));
            assertEquals(1,calls.get());assertEquals(List.of("select_work","rename_work"),plan.orderedSteps().stream().map(AgentChatModel.Step::action).toList());
            var request=json.readTree(received.get());assertEquals("auto",request.path("tool_choice").asText());assertEquals(2,request.path("tools").size());
            assertEquals("function",request.path("tools").get(0).path("type").asText());
            assertTrue(request.path("tools").toString().contains("additionalProperties"));assertFalse(request.path("tools").toString().contains("delete_work"));
            assertEquals(2600,request.path("max_tokens").asInt());
        } finally {server.stop(0);}
    }

    @Test void incompleteProviderResponseCannotBecomeExecutableEvenWhenItsJsonIsValid() throws Exception {
        var json=new ObjectMapper();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","length","message",Map.of("content","{\"action\":\"generate_song\",\"reply\":\"开始\",\"args\":{}}")))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var model=new DeepSeekAgentChatModel("test-only-key","test-model",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient());
            assertEquals("AGENT_CHAT_UNAVAILABLE",assertThrows(DomainException.class,()->model.respond(new AgentChatModel.Context("生成歌曲","zh-CN",List.of(),null))).code());
        } finally {server.stop(0);}
    }

    @Test void plainChatWithoutToolCallsIsAcceptedByTheSameHttpAdapter() throws Exception {
        withResponse("你好！想聊音乐还是一起构思歌词？",(model,request)->{
            var plan=model.respond(new AgentChatModel.Context("你好","zh-CN",List.of(),null));
            assertEquals("chat",plan.action());assertEquals("你好！想聊音乐还是一起构思歌词？",plan.reply());
        });
    }

    @Test void longHistoryDropsOldTurnsWhileKeepingCurrentInstructionInsideReservedBudget()throws Exception{
        var history=new ArrayList<AgentChatModel.Turn>();for(int i=0;i<20;i++)history.add(new AgentChatModel.Turn("user","旧话题".repeat(4000)));
        withResponse("{\"action\":\"chat\",\"reply\":\"可以继续聊\",\"args\":{}}",(model,request)->{
            try(var scope=new com.qingfeng.music.common.concurrent.AgentRequestScope(java.time.Duration.ofSeconds(10),65536).enter()){
                assertEquals("chat",model.respond(new AgentChatModel.Context("请聊现在的主题","zh-CN",history,null)).action());
                assertTrue(request.get().getBytes(StandardCharsets.UTF_8).length<=65536);assertTrue(request.get().contains("请聊现在的主题"));
            }
        });
    }
    @Test void oversizedPromptAndExpiredDeadlineNeverCallProvider() {
        var model=new DeepSeekAgentChatModel("test-key","test",true,URI.create("http://127.0.0.1:1/never"),HttpClient.newHttpClient());
        var scope=new com.qingfeng.music.common.concurrent.AgentRequestScope(java.time.Duration.ofSeconds(10),1024);
        try(var ignored=scope.enter()){
            assertEquals("AGENT_CONTEXT_TOO_LARGE",assertThrows(DomainException.class,()->model.respond(new AgentChatModel.Context("你好","zh-CN",List.of(),null))).code());
        }
        scope=new com.qingfeng.music.common.concurrent.AgentRequestScope(java.time.Duration.ofSeconds(10),65536);scope.cancel();
        try(var ignored=scope.enter()){
            assertEquals("AGENT_DEADLINE_EXCEEDED",assertThrows(DomainException.class,()->model.respond(new AgentChatModel.Context("你好","zh-CN",List.of(),null))).code());
        }
    }
    @Test void sendsConversationAndCurrentWorkToRealHttpAdapterAndPreservesStructuredArgs() throws Exception {
        var received=new AtomicReference<String>();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String plan=json.writeValueAsString(Map.of("action","generate_lyrics","reply","我会保留主题，修改副歌。","args",Map.of("mode","rewrite","instruments",List.of("钢琴"),"revisionId","revision-1")));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",plan)))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var model=new DeepSeekAgentChatModel("test-only-key","test-model",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient());
            var song=new SongCard("work","给妈妈","温暖民谣","原副歌","","DRAFT",null,1,"revision-1",null,null);
            var plan=model.respond(new AgentChatModel.Context("副歌改得更短","zh-CN",List.of(new AgentChatModel.Turn("user","写给妈妈")),song,"{\"bpm\":90,\"language\":\"en\"}"));
            assertEquals("generate_lyrics",plan.action());assertEquals(List.of("钢琴"),plan.args().get("instruments"));
            var messages=json.readTree(received.get()).path("messages");
            assertTrue(messages.toString().contains("原副歌"));assertTrue(messages.toString().contains("写给妈妈"));assertTrue(messages.toString().contains("bpm"));
            assertEquals("副歌改得更短",messages.get(messages.size()-1).path("content").asText());
        } finally { server.stop(0); }
    }
    @Test void disabledModelCannotMakeAnExternalRequest(){
        var model=new DeepSeekAgentChatModel("","test",false,URI.create("http://127.0.0.1:1/never"),HttpClient.newHttpClient());
        var error=assertThrows(DomainException.class,()->model.respond(new AgentChatModel.Context("你好","zh-CN",List.of(),null)));
        assertEquals("AGENT_CHAT_UNAVAILABLE",error.code());
    }
    @Test void capabilityCatalogAndOrderedResultIdsSupportBoundedDependentPlans() throws Exception {
        var catalog=List.of(new AgentApplicationActions.Capability("select_work","选择已有作品","workId","write"),
                new AgentApplicationActions.Capability("rename_work","重命名作品","workId,title","write"));
        var context=new AgentChatModel.Context("第二首改名为晴天","zh-CN",List.of(),null,null,catalog,
                "{\"kind\":\"works\",\"items\":[{\"id\":\"work-1\"},{\"id\":\"work-2\"}]}");
        String response="{\"reply\":\"我会修改第二首的标题。\",\"steps\":[{\"action\":\"select_work\",\"args\":{\"workId\":\"work-2\"}},{\"action\":\"rename_work\",\"args\":{\"title\":\"晴天\"}}]}";
        withResponse(response,(model,request)->{
            var plan=model.respond(context);
            assertEquals(2,plan.orderedSteps().size());
            assertEquals("work-2",plan.orderedSteps().get(0).args().get("workId"));
            assertEquals("rename_work",plan.orderedSteps().get(1).action());
            var content=new ObjectMapper().readTree(request.get()).path("messages").toString();
            assertTrue(content.contains("work-2"));assertTrue(new ObjectMapper().readTree(request.get()).path("tools").toString().contains("重命名作品"));
            assertTrue(content.contains("不能编造"));assertTrue(content.contains("尚未接通"));
        });
    }
    @Test void unknownOrOversizedPlansAreRejectedBeforeExecution() throws Exception {
        var catalog=List.of(new AgentApplicationActions.Capability("list_works","作品列表","query","read"));
        var context=new AgentChatModel.Context("我的作品","zh-CN",List.of(),null,null,catalog,null);
        for(String response:List.of(
                "{\"action\":\"delete_work\",\"reply\":\"开始\",\"args\":{}}",
                "{\"reply\":\"开始\",\"steps\":[{\"action\":\"execute_sql\",\"args\":{}}]}",
                "{\"reply\":\"开始\",\"steps\":[]}",
                "{\"reply\":\"开始\",\"steps\":["+String.join(",",Collections.nCopies(7,"{\"action\":\"list_works\",\"args\":{}}"))+"]}")) {
            withResponse(response,(model,request)->assertEquals("AGENT_CHAT_UNAVAILABLE",assertThrows(DomainException.class,()->model.respond(context)).code()));
        }
    }
    @Test void legacySingleStepPlansRemainCompatibleWithNewPlanExecutor() {
        var plan=new AgentChatModel.Plan("generate_song",Map.of("mode","new"),"开始创作");
        assertEquals(1,plan.orderedSteps().size());
        assertEquals("new",plan.orderedSteps().get(0).args().get("mode"));
        var nullable=new LinkedHashMap<String,Object>();nullable.put("style",null);
        assertNull(new AgentChatModel.Step("set_style",nullable).args().get("style"));
    }
    private static void withResponse(String plan,java.util.function.BiConsumer<DeepSeekAgentChatModel,AtomicReference<String>> assertion) throws Exception {
        var request=new AtomicReference<String>();var json=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("message",Map.of("content",plan)))));
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            assertion.accept(new DeepSeekAgentChatModel("test-only-key","test-model",true,
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient()),request);
        } finally { server.stop(0); }
    }
}
