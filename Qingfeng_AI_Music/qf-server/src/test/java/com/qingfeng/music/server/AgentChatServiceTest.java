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
        when(conversations.listMessages("owner","conversation")).thenAnswer(call->List.copyOf(messages));
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
