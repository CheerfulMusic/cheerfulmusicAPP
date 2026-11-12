package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.*;
import com.qingfeng.music.agent.application.handler.*;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Pure unit tests: never calls a provider or database. */
class AgentCreationHandlersTest {
    static final Instant NOW=Instant.parse("2026-11-12T00:00:00Z");
    static final String KEY="11111111-1111-1111-1111-111111111111";
    WorkApi works=mock(WorkApi.class); LyricsApi lyrics=mock(LyricsApi.class); MediaApi media=mock(MediaApi.class);
    GenerationApi generation=mock(GenerationApi.class); LyricsCompositionService composer=mock(LyricsCompositionService.class);
    MusicSettings settings=mock(MusicSettings.class);
    AtomicReference<WorkApi.Work> current=new AtomicReference<>(work("W",1,"AUTO"));
    AgentCreationHandlersTest(){
        when(works.get(eq("owner"),anyString())).thenAnswer(i->current.get());
        when(settings.preview(any())).thenAnswer(i->direction(i.getArgument(0)));
        when(settings.save(eq("owner"),anyString(),anyLong(),any())).thenAnswer(i->{
            MusicSettings.Preferences prefs=i.getArgument(3);
            var old=current.get();var changed=work(old.id(),old.version()+1,prefs.vocalGender());current.set(changed);
            return new MusicSettings.View(changed,direction(prefs));
        });
        when(media.selection(anyString(),anyString())).thenReturn(new MediaApi.Selection(null,0,null));
        when(lyrics.confirm(anyString(),anyString(),anyString())).thenAnswer(i->lyrics.get(i.getArgument(0),i.getArgument(1),i.getArgument(2)));
    }
    static WorkApi.Work work(String id,long version,String vocal){return new WorkApi.Work(id,"","","海边的故事","","温暖民谣","原题",version,NOW,NOW,vocal);}
    static LyricsApi.Revision rev(String id,String text,long version){return new LyricsApi.Revision(id,"W",version,"歌词题",text,"AI",true,NOW,"en",null,null);}
    static MusicSettings.Direction direction(MusicSettings.Preferences p){return new MusicSettings.Direction(p,p.freeText(),p.negativeText(),"","",1);}
    static GenerationApi.Task task(String id,String revision,String request,GenerationStatus status,String asset){return new GenerationApi.Task(id,"W",revision,2,request,"歌词题","选中的歌词","温暖民谣","fake",status,NOW,asset,null,NOW,NOW,"FEMALE");}
    static AgentToolHandler.ToolContext context(String work,String snapshot){return new AgentToolHandler.ToolContext("owner","C",KEY,new Conversation("C","owner",work,NOW,NOW),new ConversationState("C",snapshot,3));}

    @Test void actualMusicSettingsAndChosenLyricsAreSubmittedTogether(){
        var chosen=rev("chosen","选中的歌词",1);var rebased=rev("rebased",chosen.lyrics(),2);
        when(lyrics.get("owner","W","chosen")).thenReturn(chosen);
        when(lyrics.get("owner","W","rebased")).thenReturn(rebased);
        when(lyrics.save("owner","W",2,"歌词题",chosen.lyrics(),"chosen")).thenReturn(rebased);
        when(generation.submit("owner","W","rebased",KEY)).thenReturn(task("T","rebased",KEY,GenerationStatus.QUEUED,null));
        var result=new GenerateSongHandler(works,lyrics,composer,generation,media,settings).execute(context("W",null),
                new AgentTool.GenerateSongArgs("按这版唱","独立摇滚","yue",98,"female",List.of("piano","strings"),"regenerate","chosen"));
        var prefs=org.mockito.ArgumentCaptor.forClass(MusicSettings.Preferences.class);
        verify(settings).save(eq("owner"),eq("W"),eq(1L),prefs.capture());
        assertEquals("FEMALE",prefs.getValue().vocalGender());
        assertTrue(prefs.getValue().freeText().contains("BPM: 98"));
        assertTrue(prefs.getValue().freeText().contains("Cantonese"));
        assertTrue(prefs.getValue().freeText().contains("piano,strings"));
        assertEquals("rebased",result.card().revisionId());assertEquals("T",result.card().taskId());
        verifyNoInteractions(composer);
        var order=inOrder(settings,generation);order.verify(settings).save(anyString(),anyString(),anyLong(),any());order.verify(generation).submit(anyString(),anyString(),eq("rebased"),eq(KEY));
    }

    @Test void rewritingUsesTheExplicitRevisionAndPreservesLanguage(){
        var chosen=rev("chosen","原副歌",1);var written=rev("written","改好的副歌",2);
        when(lyrics.get("owner","W","chosen")).thenReturn(chosen);when(lyrics.get("owner","W","written")).thenReturn(written);
        when(composer.compose("owner","W",2,KEY,"en","让副歌更明亮","chosen")).thenReturn(written);
        var result=new GenerateLyricsHandler(works,lyrics,composer,media,settings).execute(context("W",null),new AgentTool.GenerateLyricsArgs("让副歌更明亮",null,"rewrite","chosen"));
        verify(composer).compose("owner","W",2,KEY,"en","让副歌更明亮","chosen");
        assertEquals("written",result.card().revisionId());assertEquals("",result.card().audioUrl());assertNull(result.card().taskId());
    }

    @Test void newSongCreatesAnIdempotentNewWorkInsteadOfReusingOldLyrics(){
        var created=work(KEY,0,"AUTO");when(works.create(eq("owner"),any(),eq(KEY))).thenAnswer(i->{current.set(created);return created;});
        var written=new LyricsApi.Revision("newlyrics",KEY,1,"新题","新歌词","AI",true,NOW,"zh-CN",null,null);
        when(lyrics.get("owner",KEY,"newlyrics")).thenReturn(written);
        when(composer.compose(eq("owner"),eq(KEY),eq(1L),eq(KEY),eq("zh-CN"),anyString(),isNull())).thenReturn(written);
        when(generation.submit("owner",KEY,"newlyrics",KEY)).thenReturn(new GenerationApi.Task("NEW",KEY,"newlyrics",1,KEY,"新题","新歌词","","fake",GenerationStatus.QUEUED,NOW));
        var result=new GenerateSongHandler(works,lyrics,composer,generation,media,settings).execute(context("W","{\"revisionId\":\"old\"}"),new AgentTool.GenerateSongArgs("另一首关于故乡",null,null,null,null,null,"new",null));
        assertEquals(KEY,result.currentWorkId());verify(lyrics,never()).get("owner","W","old");
        verify(works).create(eq("owner"),any(),eq(KEY));
    }

    @Test void duplicateGenerationReturnsExistingTaskWithoutChangingOrSpending(){
        var task=task("T","r",KEY,GenerationStatus.RUNNING,null);when(generation.list("owner","W")).thenReturn(List.of(task));when(lyrics.get("owner","W","r")).thenReturn(rev("r","选中的歌词",1));
        var result=new GenerateSongHandler(works,lyrics,composer,generation,media,settings).execute(context("W",null),new AgentTool.GenerateSongArgs("再来",null,null,null,null,null));
        assertEquals("T",result.card().taskId());verifyNoInteractions(settings,composer);verify(generation,never()).submit(anyString(),anyString(),anyString(),anyString());
    }

    @Test void projectorNeverPairsNewLyricsWithOldAudioOrUsesMimeAsCover(){
        when(lyrics.get("owner","W","new")).thenReturn(rev("new","新歌词",1));
        var asset=new MediaApi.Asset("old-audio","W","GENERATED","audio/mpeg",100,"sha",NOW);
        when(media.selection("owner","W")).thenReturn(new MediaApi.Selection("old-audio",1,asset));
        var card=new SongCardProjector(works,lyrics,media).withGeneration(generation).project("owner","W","{\"revisionId\":\"new\"}");
        assertEquals("",card.audioUrl());assertNull(card.assetId());assertNull(card.coverUrl());
        when(lyrics.get("owner","W","old")).thenReturn(rev("old","旧歌词",1));when(media.get("owner","W","old-audio")).thenReturn(asset);
        when(generation.list("owner","W")).thenReturn(List.of(task("T","old",KEY,GenerationStatus.SUCCEEDED,"old-audio")));
        var done=new SongCardProjector(works,lyrics,media).withGeneration(generation).project("owner","W","{\"revisionId\":\"old\",\"taskId\":\"T\"}");
        assertEquals("old-audio",done.assetId());assertEquals("old",done.revisionId());assertTrue(done.audioUrl().endsWith("/old-audio"));
    }

    @Test void extensionRejectsWithoutGeneratingOrCharging(){
        var error=assertThrows(DomainException.class,()->new ExtendSongHandler(works,lyrics,composer,generation,media).execute(context("W",null),new AgentTool.ExtendSongArgs("forward",30)));
        assertEquals("EXTEND_UNAVAILABLE",error.code());verifyNoInteractions(composer,generation);
    }

    @Test void instrumentArraysAndExplicitRevisionSurviveSerialization(){
        var input=new AgentTool.GenerateSongArgs("歌","温暖民谣","yue",90,"female",List.of("piano","strings"),"new","r");
        String json=AgentToolArgs.serialize(AgentTool.GENERATE_SONG,input);
        assertFalse(json.contains("\u0001"));
        assertEquals(input,AgentToolArgs.deserialize(AgentTool.GENERATE_SONG,json));
        var parsed=(AgentTool.GenerateSongArgs)AgentToolArgs.deserialize(AgentTool.GENERATE_SONG,"{\"instruments\":[\"piano\",\"strings\"],\"mode\":\"regenerate\",\"revisionId\":\"r\"}");
        assertEquals(List.of("piano","strings"),parsed.instruments());assertEquals("r",parsed.revisionId());
    }
    @Test void changingVoiceKeepsLanguageTempoAndInstrumentsInActualSettings(){
        String snapshot=SongCardProjector.mergeSnapshot(null,"温暖民谣","平静",86,"en",List.of("guitar"));
        var handler=new SetStyleHandler(works,lyrics,media,settings);
        var result=handler.execute(context("W",snapshot),new AgentTool.SetStyleArgs(null,null,null,null,null,"female"));
        var prefs=org.mockito.ArgumentCaptor.forClass(MusicSettings.Preferences.class);
        verify(settings).save(eq("owner"),eq("W"),eq(1L),prefs.capture());
        assertEquals("FEMALE",prefs.getValue().vocalGender());
        assertTrue(prefs.getValue().freeText().contains("English"));
        assertTrue(prefs.getValue().freeText().contains("BPM: 86"));
        assertTrue(prefs.getValue().freeText().contains("guitar"));
        assertEquals("FEMALE",AgentJson.read(result.currentSongJson()).get("vocal"));
        verifyNoInteractions(generation,composer);
    }

    @Test void firstAgentSettingChangePreservesPhotoArrangementBeforePreferencesAreSaved(){
        String arrangement="Warm folk, acoustic guitar, slow tempo";
        var photoPrefs=new MusicSettings.Preferences(1,null,Map.of(),"AUTO",arrangement,"不要说唱");
        when(settings.get("owner","W")).thenReturn(new MusicSettings.View(current.get(),direction(photoPrefs),new MusicSettings.PhotoInspiration("两个人坐在海边。",arrangement)));
        new SetStyleHandler(works,lyrics,media,settings).execute(context("W",null),new AgentTool.SetStyleArgs(null,null,null,null,null,"female"));
        var saved=org.mockito.ArgumentCaptor.forClass(MusicSettings.Preferences.class);verify(settings).save(eq("owner"),eq("W"),eq(1L),saved.capture());
        assertTrue(saved.getValue().freeText().contains(arrangement));assertEquals("不要说唱",saved.getValue().negativeText());assertEquals("FEMALE",saved.getValue().vocalGender());
        verifyNoInteractions(generation,composer);
    }

    @Test void realSettingsCatalogAcceptsTheAdaptedPreferences(){
        var groups=List.of("genre","emotion","tempo","instruments","exclude").stream()
                .map(id->new MusicSettings.Group(id,id,id,8,List.of())).toList();
        var repository=mock(MusicSettings.Repository.class);
        when(repository.find("W")).thenReturn(Optional.empty());
        when(works.update(eq("owner"),eq("W"),eq(1L),any())).thenAnswer(i->{
            WorkApi.Draft draft=i.getArgument(3);var changed=new WorkApi.Work("W","","",draft.story(),"",draft.style(),draft.title(),2,NOW,NOW,draft.vocalGender());current.set(changed);return changed;
        });
        var real=new com.qingfeng.music.settings.MusicSettingsService(new MusicSettings.Catalog(1,groups,List.of()),repository,works);
        var handler=new SetStyleHandler(works,lyrics,media,real);
        handler.execute(context("W",null),new AgentTool.SetStyleArgs("独立摇滚","明亮",120,"yue",List.of("guitar"),"male"));
        var saved=org.mockito.ArgumentCaptor.forClass(MusicSettings.Saved.class);
        verify(repository).save(eq("W"),saved.capture());
        assertEquals("MALE",saved.getValue().vocalGender());
        String compiled=real.preview(saved.getValue().preferences()).style();
        assertTrue(compiled.contains("Cantonese"));assertTrue(compiled.contains("BPM: 120"));assertTrue(compiled.contains("guitar"));
    }

}
