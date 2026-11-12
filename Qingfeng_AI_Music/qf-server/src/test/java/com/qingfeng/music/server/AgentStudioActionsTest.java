package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.*;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.application.SectionEditingService;
import com.qingfeng.music.lyrics.application.LyricLineRewriteService;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.server.agent.AgentStudioActions;
import com.qingfeng.music.settings.*;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentStudioActionsTest {
    static final String KEY="11111111-1111-1111-1111-111111111111";
    WorkApi works=mock(WorkApi.class);MusicSettings settings=mock(MusicSettings.class);MusicBriefService briefs=mock(MusicBriefService.class);
    LyricLineRewriteService lines=mock(LyricLineRewriteService.class);PlaybackLyricsService playback=mock(PlaybackLyricsService.class);
    SectionEditingService sections=mock(SectionEditingService.class);ConversationService conversations=mock(ConversationService.class);
    AgentStudioActions actions=new AgentStudioActions(works,settings,briefs,lines,playback,sections,conversations);
    WorkApi.Work work=new WorkApi.Work("W","","","故事","","民谣","标题",2,Instant.EPOCH,Instant.EPOCH,"FEMALE");
    MusicSettings.Preferences prefs=new MusicSettings.Preferences(1,null,Map.of("genre",List.of("folk"),"instruments",List.of("piano")),"FEMALE","安静温暖","不要说唱");
    AgentStudioActionsTest(){
        when(works.get("owner","W")).thenReturn(work);
        when(settings.get("owner","W")).thenReturn(new MusicSettings.View(work,direction(prefs)));
        when(conversations.current("owner")).thenReturn(new Conversation("C","owner","W",Instant.EPOCH,Instant.EPOCH));
        when(conversations.state("owner","C")).thenReturn(new ConversationState("C","{\"style\":\"旧曲风\",\"language\":\"yue\",\"revisionId\":\"R\",\"assetId\":\"A\"}",4));
        when(settings.save(eq("owner"),eq("W"),eq(2L),any())).thenAnswer(i->new MusicSettings.View(work,direction(i.getArgument(3))));
    }
    static MusicSettings.Direction direction(MusicSettings.Preferences p){return new MusicSettings.Direction(p,"compiled",p.negativeText(),"概要","summary",1);}
    AgentApplicationActions.Result run(String action,Map<String,Object> args){return actions.execute("owner","C",action,args,"用户要求",KEY,new SongCard("W","标题","民谣","歌词","/audio","READY",null,2,"R","A","T"));}
    @Test void patchingOneSettingPreservesUnspecifiedChoicesVoiceAndNegativeText(){
        run("update_music_settings",Map.of("freeText","更温暖"));
        var changed=org.mockito.ArgumentCaptor.forClass(MusicSettings.Preferences.class);
        verify(settings).save(eq("owner"),eq("W"),eq(2L),changed.capture());
        assertEquals(prefs.choices(),changed.getValue().choices());assertEquals("FEMALE",changed.getValue().vocalGender());assertEquals("不要说唱",changed.getValue().negativeText());
        var snapshot=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq("W"),snapshot.capture(),isNull(),eq(4L));
        var state=AgentJson.read(snapshot.getValue());assertFalse(state.containsKey("style"));assertEquals("A",state.get("assetId"));assertEquals("R",state.get("revisionId"));assertEquals("yue",state.get("language"));assertEquals("更温暖",state.get("baseFreeText"));
    }
    @Test void aNewDraftWithoutSavedSettingsCanReadAndUpdateItsMusicSettings(){
        when(settings.get("owner","W")).thenReturn(new MusicSettings.View(work,null));
        when(settings.preview(any())).thenAnswer(i->direction(i.getArgument(0)));
        assertEquals("get_music_settings",run("get_music_settings",Map.of()).action());
        run("update_music_settings",Map.of("freeText","温暖民谣"));
        var saved=org.mockito.ArgumentCaptor.forClass(MusicSettings.Preferences.class);verify(settings).save(eq("owner"),eq("W"),eq(2L),saved.capture());
        assertEquals("FEMALE",saved.getValue().vocalGender());assertEquals("温暖民谣",saved.getValue().freeText());
    }
    @Test void malformedChoicesNeverReachTheSettingsService(){
        assertThrows(DomainException.class,()->run("update_music_settings",Map.of("choices",Map.of("genre","folk"))));
        verify(settings,never()).save(any(),any(),anyLong(),any());
    }
    @Test void previewDoesNotWriteOrClearConversation(){
        when(settings.previewForWork(eq("owner"),eq("W"),any())).thenReturn(direction(prefs));
        run("preview_music_settings",Map.of("vocalGender","MALE"));
        verify(settings,never()).save(any(),any(),anyLong(),any());verify(conversations,never()).applyToolResult(any(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    @Test void foreignWorkIsRejectedBeforeAnyProviderCall(){
        when(works.get("owner","foreign")).thenThrow(new DomainException("WORK_NOT_FOUND","不存在"));
        assertThrows(DomainException.class,()->run("sync_playback_lyrics",Map.of("workId","foreign","assetId","A")));
        verifyNoInteractions(playback);
    }
    @Test void playbackLyricsUsesExactChosenAssetAndExistingProviderStatus(){
        when(playback.sync("owner","W","A",KEY)).thenReturn(new PlaybackLyrics.Snapshot("READY","原歌词",List.of(new PlaybackLyrics.Line("第一句",1.2,3.4)),true));
        var result=run("sync_playback_lyrics",Map.of());assertEquals("第一句",result.items().get(0).title());assertEquals(1.2,result.items().get(0).data().get("start"));
    }
    @Test void lineRewriteIsOnlyAPreviewAndUsesOriginalIdempotencyKey(){
        when(lines.preview("owner","W",UUID.fromString(KEY),LyricLineRewriteService.Action.RHYME,"晚风吹过","","zh-CN")).thenReturn("晚风轻轻说");
        var result=run("rewrite_lyric_line",Map.of("mode","RHYME","line","晚风吹过"));assertTrue(result.reply().contains("原歌词尚未改动"));assertTrue(result.reply().contains("晚风轻轻说"));
        verify(works,never()).update(any(),any(),anyLong(),any());
    }
}
