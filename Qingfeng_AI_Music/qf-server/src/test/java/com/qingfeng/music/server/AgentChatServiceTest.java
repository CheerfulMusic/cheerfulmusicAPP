package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.*;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.agent.AgentChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    final ConversationService conversations=mock(ConversationService.class);
    final AgentToolDispatcher tools=mock(AgentToolDispatcher.class);
    final AgentChatModel model=mock(AgentChatModel.class);
    final AgentSocialActions social=mock(AgentSocialActions.class);
    final List<AgentMessage> messages=new ArrayList<>();
    final Map<String,String> results=new HashMap<>();
    final SongCard song=new SongCard("work","妈妈","温暖民谣","原来的副歌","/audio/current","SUCCEEDED",null,1,"lyrics","asset",null);
    AgentChatService service;
    @BeforeEach void setup(){
        when(conversations.listRecentMessages(eq("owner"),eq("conversation"),anyInt())).thenAnswer(call->List.copyOf(messages));
        when(conversations.listRecentActionMessages(eq("owner"),eq("conversation"),anyInt())).thenAnswer(call->List.copyOf(messages));
        when(conversations.historyPage(eq("owner"),eq("conversation"),isNull(),anyInt())).thenAnswer(call->new ConversationService.MessagePage(List.copyOf(messages),false,messages.isEmpty()?0:1,messages.size(),0));
        when(conversations.appendMessage(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(call->{
            var message=new AgentMessage(UUID.randomUUID().toString(),"conversation",messages.size()+1,call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6),Instant.now());
            messages.add(message);return message;
        });
        when(tools.dispatch(eq(AgentTool.GET_CURRENT_SONG),any(),eq("owner"),anyString())).thenReturn(ToolResult.ok("m",song));
        when(tools.dispatch(eq(AgentTool.GENERATE_SONG),any(),eq("owner"),anyString())).thenReturn(ToolResult.ok("g",song));
        when(tools.dispatch(eq(AgentTool.GENERATE_LYRICS),any(),eq("owner"),anyString())).thenReturn(ToolResult.ok("l",song));
        AgentTurnStore store=new AgentTurnStore(){
            public Claim claim(String owner,String conversation,String key,String text){return new Claim(!results.containsKey(key),results.get(key));}
            public void complete(String owner,String conversation,String key,String result){results.put(key,result);}
        };
        service=new AgentChatService(conversations,tools,model,store,social);
    }
    String key(){return UUID.randomUUID().toString();}
    AgentApplicationActions app(String... names){
        var app=mock(AgentApplicationActions.class);
        when(app.capabilities()).thenReturn(Arrays.stream(names).map(n->new AgentApplicationActions.Capability(n,n,"",n.startsWith("list_")||n.startsWith("preview_")||n.equals("video_capabilities")?"read":"write")).toList());
        when(app.supports(anyString())).thenAnswer(c->Arrays.asList(names).contains(c.getArgument(0)));
        when(app.prepare(anyString(),anyString(),anyString(),anyMap(),any())).thenAnswer(c->c.getArgument(3));
        when(app.execute(anyString(),anyString(),anyString(),anyMap(),anyString(),anyString(),any())).thenAnswer(c->new AgentApplicationActions.Result("真实工具结果","result",List.of(),List.of(),c.getArgument(2)));
        AgentTurnStore store=new AgentTurnStore(){public Claim claim(String o,String c,String k,String t){return new Claim(!results.containsKey(k),results.get(k));}public void complete(String o,String c,String k,String r){results.put(k,r);}};
        service=new AgentChatService(conversations,tools,model,store,social,List.of(app));return app;
    }
    @Test void explicitMusicRequestExecutesEvenWhenModelOnlyPromises(){
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"我来创作一首热血歌曲。"));
        service.send("owner","conversation","帮我生成音乐，我想要的曲风是热血",key(),"zh-CN");
        var args=ArgumentCaptor.forClass(Object.class);verify(tools).dispatch(eq(AgentTool.GENERATE_SONG),args.capture(),eq("owner"),anyString());
        assertTrue(((AgentTool.GenerateSongArgs)args.getValue()).text().contains("热血"));
    }
    @Test void lyricsRequestExecutesAndDisplaysActualCompleteLyrics(){
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"我来写歌词。"));
        service.send("owner","conversation","帮我写一首歌词，主题是最后一舞",key(),"zh-CN");
        verify(tools).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),eq("owner"),anyString());
        assertTrue(messages.get(messages.size()-1).contentJson().contains("原来的副歌"));
    }
    @Test void pastedLyricsAreConfirmedThenSavedVerbatimWithoutGenerating(){
        var app=app("create_work","save_lyrics");String lyric="[Verse 1]\n为何多年以后\n[Chorus]\n热爱不需要加冕";
        service.send("owner","conversation",lyric,key(),"zh-CN");
        verify(app,never()).execute(anyString(),anyString(),anyString(),anyMap(),anyString(),anyString(),any());
        assertTrue(messages.get(messages.size()-1).contentJson().contains("pendingSteps"));
        service.send("owner","conversation","确认执行",key(),"zh-CN");
        var args=ArgumentCaptor.forClass(Map.class);verify(app).execute(eq("owner"),eq("conversation"),eq("save_lyrics"),args.capture(),anyString(),anyString(),any());
        assertEquals(lyric,args.getValue().get("lyrics"));verifyNoInteractions(model);
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void musicFollowupUsesPreviousUserLyricsAndReplayDoesNotResubmit(){
        var app=app("create_work","save_lyrics");String lyric="[Verse 1]\n为何多年以后\n[Chorus]\n我还在";
        messages.add(new AgentMessage("u","conversation",1,"user","TEXT","DONE",new ObjectMapper().writeValueAsString(Map.of("text",lyric)),null,Instant.now()));
        String request=key();service.send("owner","conversation","帮我生成音乐，我想要的曲风是热血",request,"zh-CN");
        service.send("owner","conversation","帮我生成音乐，我想要的曲风是热血",request,"zh-CN");
        var args=ArgumentCaptor.forClass(Map.class);verify(app,times(1)).execute(eq("owner"),eq("conversation"),eq("save_lyrics"),args.capture(),anyString(),anyString(),any());
        assertEquals(lyric,args.getValue().get("lyrics"));verify(tools,times(1)).dispatch(eq(AgentTool.GENERATE_SONG),any(),eq("owner"),anyString());
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),anyString(),anyString());
    }
    @Test void selectingExplicitWorkReallyExecutesInsteadOfTrustingChat(){
        var app=app("select_work");when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"已打开作品"));
        String id=UUID.randomUUID().toString();service.send("owner","conversation","打开作品 "+id,key(),"zh-CN");
        verify(app).execute(eq("owner"),eq("conversation"),eq("select_work"),eq(Map.of("workId",id)),anyString(),anyString(),any());
    }
    @Test void coverRequestActuallySubmitsToExistingCoverService(){
        var app=app("edit_cover");when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"已按小猫准备生成封面"));
        service.send("owner","conversation","帮我为当前作品设计封面，画面是一只小猫",key(),"zh-CN");
        verify(app).execute(eq("owner"),eq("conversation"),eq("edit_cover"),anyMap(),anyString(),anyString(),any());
    }
    @Test void mvQuoteContinuesIntoActualStoryboardPreviewWithoutSubmittingVideo(){
        var app=app("video_capabilities","preview_music_video");
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("video_capabilities",Map.of(),""),new AgentChatModel.Plan("preview_music_video",Map.of("shots",List.of(Map.of("prompt","故事开场"))),""));
        service.send("owner","conversation","根据当前歌曲帮我设计剧情 MV，先给我分镜方案和费用，再确认生成",key(),"zh-CN");
        verify(app).execute(eq("owner"),eq("conversation"),eq("preview_music_video"),anyMap(),anyString(),anyString(),any());
        var contexts=ArgumentCaptor.forClass(AgentChatModel.Context.class);verify(model,times(2)).respond(contexts.capture());
        assertEquals(List.of("preview_music_video"),contexts.getValue().capabilities().stream().map(AgentApplicationActions.Capability::id).toList());
    }
    @Test void shortReadOnlyContinuationAfterSelectingWorkKeepsStoryboardRequest(){
        var app=app("video_capabilities","preview_music_video");
        for(String text:List.of("根据当前歌曲设计 MV 分镜方案和费用","打开作品 "+UUID.randomUUID()))
            messages.add(new AgentMessage(UUID.randomUUID().toString(),"conversation",messages.size()+1,"user","TEXT","DONE",new ObjectMapper().writeValueAsString(Map.of("text",text)),null,Instant.now()));
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("preview_music_video",Map.of("shots",List.of(Map.of("prompt","故事开场"))),""));
        service.send("owner","conversation","需要",key(),"zh-CN");
        verify(app).execute(eq("owner"),eq("conversation"),eq("preview_music_video"),anyMap(),anyString(),anyString(),any());
        verify(model,times(1)).respond(any());
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void oldPastedLyricsAreNotReimportedAfterInterveningUserRequest(){
        var app=app("create_work","save_lyrics");
        for(String text:List.of("[Verse 1]\n旧的歌词\n[Chorus]\n旧歌","打开作品 "+UUID.randomUUID()))
            messages.add(new AgentMessage(UUID.randomUUID().toString(),"conversation",messages.size()+1,"user","TEXT","DONE",new ObjectMapper().writeValueAsString(Map.of("text",text)),null,Instant.now()));
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("generate_song",Map.of(),""));
        service.send("owner","conversation","生成音乐",key(),"zh-CN");
        verify(app,never()).execute(anyString(),anyString(),eq("save_lyrics"),anyMap(),anyString(),anyString(),any());
    }
    @Test void modelCannotClaimSavedStateWithoutToolEvidence(){
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"歌词已经生成好了，共62行。"));
        service.send("owner","conversation","刚才是什么情况",key(),"zh-CN");
        assertTrue(messages.get(messages.size()-1).contentJson().contains("尚未取得"));
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),anyString(),anyString());
    }
    @Test void executionProgressIsDurableExcludedFromPromptAndReplayDoesNotExecuteTwice(){
        service.withProgress(true);
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("generate_lyrics",Map.of("text","写歌词"),"开始写词"));
        String request=key();service.send("owner","conversation","写一首歌词",request,"zh-CN");
        long count=messages.stream().filter(m->m.contentJson().contains("\"kind\":\"progress\"")).count();
        assertEquals(3,count);
        int before=messages.size();service.send("owner","conversation","写一首歌词",request,"zh-CN");assertEquals(before,messages.size());
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"你好"));
        service.send("owner","conversation","你好",key(),"zh-CN");
        var contexts=ArgumentCaptor.forClass(AgentChatModel.Context.class);verify(model,times(2)).respond(contexts.capture());
        assertEquals("owner",contexts.getValue().execution().owner());
        assertTrue(contexts.getValue().history().stream().noneMatch(t->t.text().contains("正在理解")||t.text().contains("正在处理")||t.text().contains("本步骤已返回")));
        verify(tools,times(1)).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),eq("owner"),eq(request));
    }
    @Test void progressDoesNotBypassConfirmationOrClaimFailedToolSucceeded(){
        service.withProgress(true);when(model.respond(any())).thenReturn(plan("generate_song"));
        service.send("owner","conversation","帮妈妈准备一份生日惊喜",key(),"zh-CN");
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
        when(tools.dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString())).thenThrow(new com.qingfeng.music.common.exception.DomainException("GENERATION_FAILED","生成暂不可用"));
        service.send("owner","conversation","确认生成",key(),"zh-CN");
        assertTrue(messages.stream().anyMatch(m->m.contentJson().contains("tool_started")));
        assertFalse(messages.stream().anyMatch(m->m.contentJson().contains("tool_finished")));
        assertTrue(messages.get(messages.size()-1).contentJson().contains("GENERATION_FAILED"));
    }
    @Test void epochResetReplacesHistoryAndResetsCursorInsteadOfKeepingPreviousSequence(){
        when(conversations.historyPage(eq("owner"),eq("conversation"),isNull(),anyInt())).thenReturn(new ConversationService.MessagePage(List.of(),false,0,0,5));
        when(conversations.historyEpoch("owner","conversation")).thenReturn(5L);
        var snapshot=service.state("owner","conversation",100L,4L);
        assertEquals("replace",snapshot.messagesMode());assertEquals(0,snapshot.latestSeq());assertEquals(5,snapshot.historyEpoch());assertFalse(snapshot.hasNewerMessages());
    }
    @Test void incrementalPageReportsMoreMessagesEvenWhenMusicIsFinished(){
        for(int i=1;i<=150;i++)messages.add(new AgentMessage("m"+i,"conversation",i,"assistant","TEXT","DONE","{\"text\":\"历史\"}",null,Instant.now()));
        when(conversations.listMessagesAfter("owner","conversation",0L,100)).thenReturn(List.copyOf(messages.subList(0,100)));
        var snapshot=service.state("owner","conversation",0L,0L);
        assertEquals("append",snapshot.messagesMode());assertEquals(100,snapshot.latestSeq());assertTrue(snapshot.hasNewerMessages());assertEquals(100,snapshot.messages().size());
    }
    @Test void repeatedResetsDuringSnapshotNeverReturnMixedEpochState(){
        when(conversations.historyEpoch("owner","conversation")).thenReturn(0L,1L,2L);
        assertEquals("AGENT_CONVERSATION_CONFLICT",assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->service.state("owner","conversation",0L,0L)).code());
    }
    AgentChatModel.Plan plan(String action){return new AgentChatModel.Plan(action,Map.of("text","妈妈的生日"),"我可以帮你继续创作。");}
    @Test void conversationalReplyUsesHistoryAndCurrentLyricsWithoutGenerating(){
        messages.add(new AgentMessage("1","conversation",1,"user","TEXT","DONE","{\"text\":\"给妈妈写首歌\"}",null,Instant.now()));
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("chat",Map.of(),"我能陪你聊灵感，也能帮你写词、制作音乐。"));
        var response=service.send("owner","conversation","你能为我做什么",key(),"zh-CN");
        var context=ArgumentCaptor.forClass(AgentChatModel.Context.class);verify(model).respond(context.capture());
        assertEquals("原来的副歌",context.getValue().song().lyrics());
        assertEquals("给妈妈写首歌",context.getValue().history().get(0).text());
        assertTrue(response.messages().get(response.messages().size()-1).contentJson().contains("陪你聊灵感"));
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void negationAndConversationCannotExecuteHallucinatedTool(){
        when(model.respond(any())).thenReturn(plan("generate_song"));
        for(String text:List.of("先不要生成一首歌","我喜欢民谣但今天只想聊天","什么是摇滚"))service.send("owner","conversation",text,key(),"zh-CN");
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void lyricsOnlyNeverCallsMusicAndPassesCurrentRevision(){
        when(model.respond(any())).thenReturn(plan("generate_song"));
        service.send("owner","conversation","只写一首歌词，不要生成音乐",key(),"zh-CN");
        var args=ArgumentCaptor.forClass(Object.class);verify(tools).dispatch(eq(AgentTool.GENERATE_LYRICS),args.capture(),eq("owner"),anyString());
        assertEquals("lyrics",((AgentTool.GenerateLyricsArgs)args.getValue()).revisionId());
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void proposedMusicNeedsConfirmationAndReplayDoesNotChargeAgain(){
        when(model.respond(any())).thenReturn(plan("generate_song"));
        service.send("owner","conversation","帮妈妈准备一份生日惊喜",key(),"zh-CN");
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
        String request=key();service.send("owner","conversation","确认生成",request,"zh-CN");
        service.send("owner","conversation","确认生成",request,"zh-CN");
        verify(tools,times(1)).dispatch(eq(AgentTool.GENERATE_SONG),any(),eq("owner"),eq(request));
        verify(model,times(1)).respond(any());
    }
    @Test void explicitMusicExecutesWithoutExtraConfirmation(){
        when(model.respond(any())).thenReturn(plan("generate_song"));
        service.send("owner","conversation","用当前歌词生成音乐",key(),"zh-CN");
        verify(tools).dispatch(eq(AgentTool.GENERATE_SONG),any(),eq("owner"),anyString());
    }
    @Test void modelCannotPublishOrShareWithoutExplicitUserInstruction(){
        when(model.respond(any())).thenReturn(plan("share"));
        service.send("owner","conversation","这首听起来怎么样",key(),"zh-CN");
        verify(social,never()).execute(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void explicitShareDelegatesAndReplaysPersistedLink(){
        when(social.execute(eq("owner"),eq("conversation"),eq("share"),anyString(),anyString(),eq(song)))
                .thenReturn(new AgentSocialActions.Result("分享链接已创建。","share","/gift/example",song));
        String request=key();service.send("owner","conversation","分享这首歌",request,"zh-CN");
        assertEquals("/gift/example",service.send("owner","conversation","分享这首歌",request,"zh-CN").shareUrl());
        verify(social,times(1)).execute(anyString(),anyString(),eq("share"),anyString(),eq(request),any());
        verifyNoInteractions(model);
    }
    @Test void unavailableModelIsHonestAndNeverUsesFakeTemplateGeneration(){
        when(model.respond(any())).thenThrow(new com.qingfeng.music.common.exception.DomainException("AGENT_CHAT_UNAVAILABLE","对话模型暂未启用。"));
        var response=service.send("owner","conversation","你好",key(),"zh-CN");
        assertTrue(response.messages().get(response.messages().size()-1).contentJson().contains("AGENT_CHAT_UNAVAILABLE"));
        assertNull(response.action());verify(tools,never()).dispatch(eq(AgentTool.GENERATE_SONG),any(),anyString(),anyString());
    }
    @Test void greetingCannotSpendOnLyricsEvenIfModelMisclassifies(){
        when(model.respond(any())).thenReturn(plan("generate_lyrics"));
        for(String text:List.of("你好","先不要改歌词","不要写歌词"))service.send("owner","conversation",text,key(),"zh-CN");
        verify(tools,never()).dispatch(eq(AgentTool.GENERATE_LYRICS),any(),anyString(),anyString());
    }
    @Test void quotedOrDiscussedSocialCommandsNeverExecute(){
        when(model.respond(any())).thenReturn(plan("share"));
        for(String text:List.of("歌词里写‘分享这首歌’","我想知道分享功能","分享功能是什么","你能分享吗？"))service.send("owner","conversation",text,key(),"zh-CN");
        verify(social,never()).execute(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void omittedSettingsRemainUnspecifiedAndShareSurvivesPolling(){
        when(model.respond(any())).thenReturn(new AgentChatModel.Plan("generate_song",Map.of("mode","regenerate"),"开始生成"));
        service.send("owner","conversation","用当前歌词生成音乐，其他不变",key(),"zh-CN");
        var args=ArgumentCaptor.forClass(Object.class);verify(tools).dispatch(eq(AgentTool.GENERATE_SONG),args.capture(),eq("owner"),anyString());
        var request=(AgentTool.GenerateSongArgs)args.getValue();assertNull(request.language());assertNull(request.instruments());assertNull(request.vocal());
        when(social.execute(eq("owner"),eq("conversation"),eq("share"),anyString(),anyString(),eq(song)))
                .thenReturn(new AgentSocialActions.Result("分享已创建","share","/gift/persistent",song));
        service.send("owner","conversation","分享这首歌",key(),"zh-CN");
        assertEquals("/gift/persistent",service.state("owner","conversation").shareUrl());
        assertNull(service.state("owner","conversation").action());
    }
    @Test void shareLinkDoesNotFollowAnotherRevisionOrAudioInTheSameWork(){
        when(social.execute(eq("owner"),eq("conversation"),eq("share"),anyString(),anyString(),eq(song)))
                .thenReturn(new AgentSocialActions.Result("分享已创建","share","/gift/original",song));
        String request=key();assertEquals("/gift/original",service.send("owner","conversation","分享这首歌",request,"zh-CN").shareUrl());
        var changedLyrics=new SongCard("work","妈妈","温暖民谣","新副歌","/audio/current","SUCCEEDED",null,2,"lyrics-next","asset",null);
        when(tools.dispatch(eq(AgentTool.GET_CURRENT_SONG),any(),eq("owner"),anyString())).thenReturn(ToolResult.ok("m",changedLyrics));
        assertNull(service.state("owner","conversation").shareUrl());
        assertNull(service.send("owner","conversation","分享这首歌",request,"zh-CN").shareUrl());
        var changedAudio=new SongCard("work","妈妈","温暖民谣","原来的副歌","/audio/next","SUCCEEDED",null,1,"lyrics","asset-next",null);
        when(tools.dispatch(eq(AgentTool.GET_CURRENT_SONG),any(),eq("owner"),anyString())).thenReturn(ToolResult.ok("m",changedAudio));
        assertNull(service.state("owner","conversation").shareUrl());
        assertNull(service.send("owner","conversation","分享这首歌",request,"zh-CN").shareUrl());
        verify(social,times(1)).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
    @Test void replayingShareAfterRevocationCannotRestoreTheRevokedLink(){
        when(social.execute(eq("owner"),eq("conversation"),eq("share"),anyString(),anyString(),eq(song)))
                .thenReturn(new AgentSocialActions.Result("分享已创建","share","/gift/revoked",song));
        when(social.execute(eq("owner"),eq("conversation"),eq("revoke_share"),anyString(),anyString(),eq(song)))
                .thenReturn(new AgentSocialActions.Result("已撤销所有分享","revoke_share",null,song));
        String shared=key();service.send("owner","conversation","分享这首歌",shared,"zh-CN");
        assertNull(service.send("owner","conversation","撤销分享",key(),"zh-CN").shareUrl());
        assertNull(service.state("owner","conversation").shareUrl());
        assertNull(service.send("owner","conversation","分享这首歌",shared,"zh-CN").shareUrl());
        verify(social,times(1)).execute(anyString(),anyString(),eq("share"),anyString(),anyString(),any());
    }
}
