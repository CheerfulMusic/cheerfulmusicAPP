package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.api.AgentChatModel.Step;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.agent.AgentChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPlanServiceTest {
    final ConversationService conversations=mock(ConversationService.class);
    final AgentToolDispatcher tools=mock(AgentToolDispatcher.class);
    final AgentChatModel model=mock(AgentChatModel.class);
    final AgentSocialActions social=mock(AgentSocialActions.class);
    final List<AgentMessage> messages=new ArrayList<>();
    final Map<String,String> outcomes=new HashMap<>();
    final AtomicReference<SongCard> current=new AtomicReference<>();
    final ObjectMapper json=new ObjectMapper();
    final GuardedLibrary library=new GuardedLibrary();
    AgentChatService service;
    AgentTurnStore store;
    @BeforeEach void setup() {
        current.set(card("work","old-revision",null,"DRAFT"));
        when(conversations.listMessages("owner","conversation")).thenAnswer(c->List.copyOf(messages));
        when(conversations.appendMessage(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(c->{
            var m=new AgentMessage(UUID.randomUUID().toString(),"conversation",messages.size()+1,c.getArgument(2),c.getArgument(3),c.getArgument(4),c.getArgument(5),c.getArgument(6),Instant.now());messages.add(m);return m;
        });
        when(tools.dispatch(eq(AgentTool.GET_CURRENT_SONG),any(),eq("owner"),anyString())).thenAnswer(c->ToolResult.ok("get",current.get()));
        when(tools.dispatch(eq(AgentTool.GENERATE_LYRICS),any(),eq("owner"),anyString())).thenAnswer(c->{
            current.set(card("new-work","new-revision",null,"DRAFT"));return ToolResult.ok("lyrics",current.get());
        });
        when(tools.dispatch(eq(AgentTool.GENERATE_SONG),any(),eq("owner"),anyString())).thenAnswer(c->{
            current.set(card(current.get().songId(),current.get().revisionId(),"task-1","QUEUED"));return ToolResult.ok("song",current.get());
        });
        when(social.execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any())).thenReturn(new AgentSocialActions.Result("分享已创建","share","/gift/real",null));
        when(social.execute(anyString(),anyString(),eq("play"),anyString(),anyString(),any())).thenReturn(new AgentSocialActions.Result("准备播放","play",null,null));
        when(social.execute(anyString(),anyString(),eq("download"),anyString(),anyString(),any())).thenReturn(new AgentSocialActions.Result("准备下载","download",null,null));
        store=new AgentTurnStore(){
            public Claim claim(String owner,String conversation,String key,String text) {
                if(outcomes.containsKey(key))return new Claim(false,outcomes.get(key));
                messages.add(new AgentMessage(UUID.randomUUID().toString(),conversation,messages.size()+1,"user","TEXT","DONE",json.writeValueAsString(Map.of("text",text)),null,Instant.now()));
                return new Claim(true,null);
            }
            public void complete(String owner,String conversation,String key,String result){outcomes.put(key,result);}
        };
        restart();
    }
    void restart(){service=new AgentChatService(conversations,tools,model,store,social,List.of(library));}
    String key(){return UUID.randomUUID().toString();}
    SongCard card(String work,String revision,String task,String status){return new SongCard(work,"作品","民谣","歌词","SUCCEEDED".equals(status)?"/audio/real":"",status,null,1,revision,"SUCCEEDED".equals(status)?"asset-1":null,task);}
    AgentChatModel.Plan plan(Step... steps){return new AgentChatModel.Plan(null,Map.of(),"准备处理",List.of(steps));}
    Step step(String action){return new Step(action,Map.of());}
    String last(){return messages.get(messages.size()-1).contentJson();}
    void finishMusic(){var c=current.get();current.set(card(c.songId(),c.revisionId(),c.taskId(),"SUCCEEDED"));}

    @Test void writeThenGenerateUsesTheNewRevisionAndDoesNotCreateASecondWork() {
        when(model.respond(any())).thenReturn(plan(new Step("generate_lyrics",Map.of("mode","new")),new Step("generate_song",Map.of("mode","new","revisionId","old-revision"))));
        service.send("owner","conversation","先写歌词，再生成音乐",key(),"zh-CN");
        var args=org.mockito.ArgumentCaptor.forClass(Object.class);verify(tools).dispatch(eq(AgentTool.GENERATE_SONG),args.capture(),eq("owner"),anyString());
        var music=(AgentTool.GenerateSongArgs)args.getValue();assertEquals("regenerate",music.mode());assertEquals("new-revision",music.revisionId());
        assertEquals("new-work",current.get().songId());
    }
    @Test void lyricsOnlyNormalizesBeforeValidationAndCannotSpendOnTwoLyricSteps() {
        when(model.respond(any())).thenReturn(plan(step("generate_lyrics"),step("generate_song")));
        service.send("owner","conversation","只写歌词，不要生成音乐",key(),"zh-CN");
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),anyString(),anyString());
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
        assertTrue(last().contains("INVALID_AGENT_PLAN"));
    }
    @Test void unrelatedNegativeDoesNotBlockRequestedMusicButCannotAuthorizeSharing() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        service.send("owner","conversation","不要分享，生成音乐",key(),"zh-CN");finishMusic();
        verify(tools).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
        assertNull(service.state("owner","conversation").continuation());
        verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void destructiveConfirmationFreezesExactObjectAndVersionAndSurvivesRestart() {
        when(model.respond(any())).thenReturn(plan(new Step("delete_work",Map.of("workId","target-work"))));
        service.send("owner","conversation","删除作品 target-work",key(),"zh-CN");
        assertEquals(1,library.preparations);assertEquals(0,library.deletions);assertTrue(last().contains("target-work"));assertTrue(last().contains("版本 5"));
        restart();String confirmed=key();service.send("owner","conversation","确认删除",confirmed,"zh-CN");
        service.send("owner","conversation","确认删除",confirmed,"zh-CN");
        assertEquals(1,library.preparations);assertEquals(1,library.deletions);assertEquals("target-work",library.deletedWork);
    }
    @Test void changedDeletionTargetVersionCannotBeRefreshedByConfirmation() {
        when(model.respond(any())).thenReturn(plan(new Step("delete_work",Map.of("workId","target-work"))));
        service.send("owner","conversation","删除作品 target-work",key(),"zh-CN");library.version=6;
        service.send("owner","conversation","确认执行",key(),"zh-CN");
        assertEquals(1,library.preparations);assertEquals(0,library.deletions);assertTrue(last().contains("STALE_WORK"));
    }
    @Test void changedCurrentSelectionInvalidatesPendingConfirmation() {
        when(model.respond(any())).thenReturn(plan(step("delete_work")));
        service.send("owner","conversation","删除当前作品",key(),"zh-CN");
        current.set(card("other","other-revision",null,"DRAFT"));
        service.send("owner","conversation","确认执行",key(),"zh-CN");
        assertEquals(0,library.deletions);assertTrue(last().contains("AGENT_PLAN_STALE"));
    }
    @Test void asynchronousMusicSharesOnlyAfterSameTaskSucceedsWithPersistedAuthorizationAndOneKey() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        var waiting=service.send("owner","conversation","用当前歌词生成音乐，完成后分享",key(),"zh-CN");
        assertNull(waiting.continuation());verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void continuationSurvivesRestartAndExecutesOnlyOnce() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        service.send("owner","conversation","用当前歌词生成音乐，完成后分享",key(),"zh-CN");
        verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
        finishMusic();restart();var ready=service.state("owner","conversation").continuation();assertNotNull(ready);
        service.send("owner","conversation",ready.text(),ready.requestKey(),"zh-CN");
        service.send("owner","conversation",ready.text(),ready.requestKey(),"zh-CN");
        verify(social,times(1)).execute(eq("owner"),eq("conversation"),eq("share"),eq("用当前歌词生成音乐，完成后分享"),eq(ready.requestKey()),any());
        verify(model,times(1)).respond(any());assertNull(service.state("owner","conversation").continuation());
    }
    @Test void failureOrDifferentTaskCannotReleaseContinuation() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        service.send("owner","conversation","生成音乐，完成后分享",key(),"zh-CN");
        current.set(card("work","old-revision","task-1","FAILED"));assertNull(service.state("owner","conversation").continuation());
        current.set(card("work","old-revision","different-task","SUCCEEDED"));assertNull(service.state("owner","conversation").continuation());
        current.set(card("other","old-revision","task-1","SUCCEEDED"));assertNull(service.state("owner","conversation").continuation());
        current.set(card("work","different-revision","task-1","SUCCEEDED"));assertNull(service.state("owner","conversation").continuation());
    }
    @Test void newConversationTurnCancelsDeferredStepsAndForgedContinuationCannotExecute() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        service.send("owner","conversation","生成音乐，完成后分享",key(),"zh-CN");finishMusic();var ready=service.state("owner","conversation").continuation();
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"你好"));
        service.send("owner","conversation","你好",key(),"zh-CN");assertNull(service.state("owner","conversation").continuation());
        service.send("owner","conversation",ready.text(),ready.requestKey(),"zh-CN");
        verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void deferredStepCannotBeReleasedUsingAnArbitraryRequestKey() {
        when(model.respond(any())).thenReturn(plan(step("generate_song"),step("share")));
        service.send("owner","conversation","生成音乐，完成后分享",key(),"zh-CN");finishMusic();
        var ready=service.state("owner","conversation").continuation();assertNotNull(ready);
        service.send("owner","conversation",ready.text(),key(),"zh-CN");
        assertTrue(last().contains("AGENT_PLAN_STALE"));
        verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void readResultCanResolvePreviousVersionWithinTheOriginalTurn() {
        when(model.respond(any())).thenReturn(plan(step("list_versions")),plan(new Step("select_version",Map.of("revisionId","previous-revision"))));
        service.send("owner","conversation","切回上一版",key(),"zh-CN");
        assertEquals("previous-revision",current.get().revisionId());assertEquals(1,library.queries);
        var contexts=org.mockito.ArgumentCaptor.forClass(AgentChatModel.Context.class);verify(model,times(2)).respond(contexts.capture());
        assertEquals("切回上一版",contexts.getAllValues().get(1).text());
        assertTrue(contexts.getAllValues().get(1).results().contains("previous-revision"));
    }
    @Test void repeatedReadPlanStopsAfterOneReplanAndCannotLoop() {
        when(model.respond(any())).thenReturn(plan(step("list_versions")));
        service.send("owner","conversation","查看歌词历史版本",key(),"zh-CN");
        verify(model,times(2)).respond(any());assertEquals(1,library.queries);
    }
    @Test void aReplanCannotInventPermissionToDeleteAfterReadingVersions() {
        when(model.respond(any())).thenReturn(plan(step("list_versions")),plan(step("delete_work")));
        service.send("owner","conversation","查看歌词历史版本",key(),"zh-CN");
        assertEquals(0,library.preparations);assertEquals(0,library.deletions);assertFalse(last().contains("pendingSteps"));
    }
    @Test void repeatedIdenticalWritesAreRejectedBeforeAnySideEffect() {
        when(model.respond(any())).thenReturn(plan(step("share"),step("share")));
        service.send("owner","conversation","完成后分享当前作品",key(),"zh-CN");
        assertTrue(last().contains("INVALID_AGENT_PLAN"));
        verify(social,never()).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void politeMusicRequestGetsAConcreteConfirmationAndThenExecutesOnce() {
        when(model.respond(any())).thenReturn(plan(step("generate_song")));
        service.send("owner","conversation","能不能帮我写首歌？",key(),"zh-CN");
        assertTrue(last().contains("pendingSteps"));verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
        String confirmed=key();service.send("owner","conversation","确认生成",confirmed,"zh-CN");
        service.send("owner","conversation","确认生成",confirmed,"zh-CN");
        verify(tools,times(1)).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),eq(confirmed));
    }
    @Test void readOnlyResultsDoNotSwallowPlaybackOrDownloadForTheSameMedia() {
        current.set(card("work","old-revision","task-1","SUCCEEDED"));
        for(String action:List.of("play","download")) {
            when(model.respond(any())).thenReturn(plan(step(action),step("get_credits")));
            var response=service.send("owner","conversation",(action.equals("play")?"播放":"下载")+"当前作品并查看积分",key(),"zh-CN");
            assertEquals(action,response.action());assertEquals("asset-1",response.songCard().assetId());
        }
        assertEquals(2,library.creditQueries);
    }
    @Test void clientActionReplayRequiresTheOriginalWorkRevisionAndAudio() {
        current.set(card("work","old-revision","task-1","SUCCEEDED"));
        String request=key();assertEquals("play",service.send("owner","conversation","播放当前作品",request,"zh-CN").action());
        assertEquals("play",service.send("owner","conversation","播放当前作品",request,"zh-CN").action());
        current.set(card("other","old-revision","task-1","SUCCEEDED"));assertNull(service.send("owner","conversation","播放当前作品",request,"zh-CN").action());
        current.set(card("work","another-revision","task-1","SUCCEEDED"));assertNull(service.send("owner","conversation","播放当前作品",request,"zh-CN").action());
        current.set(new SongCard("work","作品","民谣","歌词","/audio/second","SUCCEEDED",null,1,"old-revision","asset-2","task-1"));
        assertNull(service.send("owner","conversation","播放当前作品",request,"zh-CN").action());
        verify(social,times(1)).execute(anyString(),anyString(),eq("play"),anyString(),anyString(),any());
    }
    @Test void downloadingAReplayedRequestCannotTargetANewAudioCandidate() {
        current.set(card("work","old-revision","task-1","SUCCEEDED"));
        String request=key();assertEquals("download",service.send("owner","conversation","下载当前作品",request,"zh-CN").action());
        current.set(new SongCard("work","作品","民谣","歌词","/audio/second","SUCCEEDED",null,1,"old-revision","asset-2","task-1"));
        assertNull(service.send("owner","conversation","下载当前作品",request,"zh-CN").action());
        verify(social,times(1)).execute(anyString(),anyString(),eq("download"),anyString(),anyString(),any());
    }
    @Test void clientActionsCannotPrecedeAWriteOrAnotherClientAction() {
        current.set(card("work","old-revision","task-1","SUCCEEDED"));
        for(var plan:List.of(plan(step("play"),step("download")),plan(step("play"),new Step("select_version",Map.of("revisionId","previous-revision"))))) {
            when(model.respond(any())).thenReturn(plan);
            var response=service.send("owner","conversation","播放当前作品，然后切换上一版并下载",key(),"zh-CN");
            assertNull(response.action());assertTrue(last().contains("INVALID_AGENT_PLAN"));
        }
        verify(social,never()).execute(anyString(),anyString(),eq("play"),anyString(),anyString(),any());
        verify(social,never()).execute(anyString(),anyString(),eq("download"),anyString(),anyString(),any());
        assertEquals("old-revision",current.get().revisionId());
    }
    @Test void aTargetChangeDuringReadResultsSuppressesTheQueuedBrowserAction() {
        current.set(card("work","old-revision","task-1","SUCCEEDED"));library.changeSelectionDuringRead=true;
        when(model.respond(any())).thenReturn(plan(step("play"),step("get_credits")));
        var response=service.send("owner","conversation","播放当前作品并查看积分",key(),"zh-CN");
        assertEquals("other",response.songCard().songId());assertNull(response.action());
    }
    class GuardedLibrary implements AgentApplicationActions {
        int version=5,preparations,deletions,queries,creditQueries;String deletedWork;boolean changeSelectionDuringRead;
        public List<Capability> capabilities(){return List.of(new Capability("delete_work","删除作品","workId,expectedVersion","destructive"),new Capability("list_versions","查看版本","workId","read"),new Capability("select_version","选择版本","revisionId","write"),new Capability("get_credits","查询积分","{}","read"));}
        public Map<String,Object> prepare(String owner,String conversation,String action,Map<String,Object> args,SongCard song) {
            if(!"delete_work".equals(action))return args;
            preparations++;return Map.of("workId",args.getOrDefault("workId",song.songId()),"expectedVersion",version,"title","指定作品");
        }
        public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard song) {
            if("get_credits".equals(action)) {
                creditQueries++;if(changeSelectionDuringRead)current.set(card("other","other-revision","other-task","SUCCEEDED"));
                return new Result("剩余 100 积分","credits",List.of(new Item("balance","积分","100",Map.of())),List.of(),action);
            }
            if("list_versions".equals(action)){
                queries++;return new Result("歌词历史版本","versions",List.of(new Item("previous-revision","上一版","版本 1",Map.of("revisionId","previous-revision","workId",song.songId()))),List.of(),action);
            }
            if("select_version".equals(action)){
                current.set(card(song.songId(),args.get("revisionId").toString(),null,"DRAFT"));return new Result("已切换版本","result",List.of(),List.of(),action);
            }
            if(((Number)args.get("expectedVersion")).intValue()!=version)throw new DomainException("STALE_WORK","作品版本已变化");
            deletions++;deletedWork=args.get("workId").toString();return new Result("已删除","result",List.of(),List.of(),action);
        }
    }
}
