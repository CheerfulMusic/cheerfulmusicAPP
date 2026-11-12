package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentChatModel;
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
}
