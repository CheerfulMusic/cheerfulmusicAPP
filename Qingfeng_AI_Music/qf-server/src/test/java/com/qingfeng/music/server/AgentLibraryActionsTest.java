package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.*;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.cover.api.CoverApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.agent.AgentLibraryActions;
import com.qingfeng.music.server.hotsong.HotSongService;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentLibraryActionsTest {
    static final Instant NOW=Instant.parse("2026-11-12T00:00:00Z");
    static final String KEY="11111111-1111-1111-1111-111111111111";
    WorkApi works=mock(WorkApi.class);LyricsApi lyrics=mock(LyricsApi.class);MediaApi media=mock(MediaApi.class);
    GenerationApi generation=mock(GenerationApi.class);CoverApi covers=mock(CoverApi.class);
    ConversationService conversations=mock(ConversationService.class);HotSongService hot=mock(HotSongService.class);
    InspirationRewriteService inspiration=mock(InspirationRewriteService.class);
    AgentLibraryActions actions=new AgentLibraryActions(works,lyrics,media,generation,covers,conversations,hot,inspiration);
    AgentLibraryActionsTest(){
        when(conversations.current("owner")).thenReturn(new Conversation("C","owner","W",NOW,NOW));
        when(conversations.state("owner","C")).thenReturn(new ConversationState("C","{\"style\":\"旧曲风\",\"revisionId\":\"old\"}",5));
        when(works.get("owner","W")).thenReturn(work("W","海风",2));
        when(works.get("owner","W2")).thenReturn(work("W2","夜色",3));
        when(media.selection(anyString(),anyString())).thenReturn(new MediaApi.Selection(null,1,null));
        when(hot.reactions("owner")).thenReturn(new HotSongService.Reactions(List.of(),List.of()));
    }
    static WorkApi.Work work(String id,String title,long version){return new WorkApi.Work(id,"妈妈","生日","原故事","祝福","民谣",title,version,NOW,NOW,"FEMALE");}
    static SongCard card(){return new SongCard("W","海风","民谣","歌词","","LYRICS_READY",null,2,"R",null,null);}
    AgentApplicationActions.Result run(String action,Map<String,Object> args){return actions.execute("owner","C",action,args,"测试指令",KEY,card());}
    @Test void anEmptyConversationCanCreateADraftWithItsRequestKeyWithoutGeneration(){
        var created=new WorkApi.Work(KEY,"","","","","","智能创作",0,NOW,NOW,"AUTO");
        when(works.create(eq("owner"),any(),eq(KEY))).thenReturn(created);when(works.get("owner",KEY)).thenReturn(created);
        var result=actions.execute("owner","C","create_work",Map.of(),"我要导入自己的音频",KEY,null);
        verify(works).create("owner",new WorkApi.Draft("","","","","","智能创作","AUTO"),KEY);
        var snapshot=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq(KEY),snapshot.capture(),isNull(),eq(5L));
        assertFalse(AgentJson.read(snapshot.getValue()).containsKey("style"));assertEquals(KEY,result.items().get(0).data().get("workId"));
        verifyNoInteractions(lyrics,generation,inspiration,media,covers);
    }
    @Test void creatingADraftRejectsOversizedInputBeforeAnyWrite(){
        assertThrows(DomainException.class,()->run("create_work",Map.of("title","名".repeat(101))));
        verify(works,never()).create(anyString(),any(),anyString());
        verify(conversations,never()).applyToolResult(any(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    @Test void switchingWorksClearsUnrelatedSettingsAndPointers(){
        assertEquals("select_work",run("select_work",Map.of("workId","W2")).action());
        var snapshot=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq("W2"),snapshot.capture(),isNull(),eq(5L));
        var saved=AgentJson.read(snapshot.getValue());assertFalse(saved.containsKey("style"));assertNull(saved.get("revisionId"));
    }
    @Test void secondWorkUsesThePersistedVisibleList(){
        when(conversations.listMessages("owner","C")).thenReturn(List.of(message("{\"kind\":\"works\",\"items\":[{\"data\":{\"workId\":\"W\"}},{\"data\":{\"workId\":\"W2\"}}]}")));
        run("select_work",Map.of("index",2));
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq("W2"),anyString(),isNull(),eq(5L));
    }
    @Test void anOrdinalNeverFallsBackToTheCurrentWorkWithoutAList(){
        assertThrows(DomainException.class,()->run("select_work",Map.of("index",2)));
        verify(conversations,never()).applyToolResult(any(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    @Test void ambiguousNamesReturnCandidatesWithoutSelecting(){
        when(works.list("owner",0)).thenReturn(List.of(work("W","夜色",2),work("W2","夜色",3)));
        var result=run("select_work",Map.of("query","夜色"));assertNull(result.action());assertEquals(2,result.items().size());
        verify(conversations,never()).applyToolResult(any(),any(),any(),any(),any(),any(),any(),anyLong());
    }
    @Test void staleDeletionConfirmationDoesNotDelete(){
        var prepared=actions.prepare("owner","C","delete_work",Map.of("workId","W"),card());assertEquals(2L,prepared.get("expectedVersion"));
        when(works.get("owner","W")).thenReturn(work("W","changed",3));
        assertThrows(DomainException.class,()->run("delete_work",prepared));verify(works,never()).delete(anyString(),anyString(),anyBoolean(),anyLong());
    }
    @Test void deleteReauthorizesAndClearsOnlyTheDeletedCurrentPointer(){
        run("delete_work",Map.of("workId","W","expectedVersion",2));verify(works).delete("owner","W",true,2);
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),isNull(),isNull(),isNull(),eq(5L));
        when(works.get("owner","foreign")).thenThrow(new DomainException("WORK_NOT_FOUND","不存在"));
        assertThrows(DomainException.class,()->run("delete_work",Map.of("workId","foreign","expectedVersion",2)));
    }
    @Test void renamePreservesTheWholeDraft(){
        run("rename_work",Map.of("title","新名字"));
        verify(works).update("owner","W",2,new WorkApi.Draft("妈妈","生日","原故事","祝福","民谣","新名字","FEMALE"));
    }
    @Test void deletingAWorkWithAnInFlightTaskDoesNotDelete(){
        when(generation.list("owner","W")).thenReturn(List.of(new GenerationApi.Task("T","W","R",2,KEY,"歌名","歌词","民谣","stub",GenerationStatus.RUNNING,NOW)));
        assertThrows(DomainException.class,()->run("delete_work",Map.of("workId","W","expectedVersion",2)));
        verify(works,never()).delete(anyString(),anyString(),anyBoolean(),anyLong());
    }
    @Test void aNewGenerationDropsThePreviousExplicitAudioSelection(){
        String result=SongCardProjector.withPointers("{\"assetId\":\"old-audio\",\"style\":\"民谣\"}","new-lyrics","new-task");
        assertFalse(AgentJson.read(result).containsKey("assetId"));assertEquals("民谣",AgentJson.read(result).get("style"));
    }
    @Test void reviewedDeletionChecksVersionAfterTakingTheRepositoryLock(){
        var repository=mock(com.qingfeng.music.work.application.WorkRepository.class);
        when(repository.lockActiveOwner("owner")).thenReturn(true);
        when(repository.lockDeletion("owner","W")).thenReturn(Optional.of(new com.qingfeng.music.work.application.WorkRepository.DeletionState(false,false)));
        when(repository.find("owner","W")).thenReturn(Optional.of(work("W","另一个页面刚改名",3)));
        var service=new com.qingfeng.music.work.application.WorkService(repository);
        assertThrows(DomainException.class,()->service.delete("owner","W",true,2));
        var ordered=inOrder(repository);ordered.verify(repository).lockDeletion("owner","W");ordered.verify(repository).find("owner","W");
        verify(repository,never()).delete(any(),any(),any());
    }
    @Test void audioCandidateKeepsItsOwnAudioInsteadOfPrimaryTaskAsset(){
        var asset=new MediaApi.Asset("A2","W","GENERATED","audio/wav",10,"sha",NOW,false,"T","second");
        var revision=new LyricsApi.Revision("R","W",2,"歌名","这版歌词","AI",true,NOW);
        when(media.get("owner","W","A2")).thenReturn(asset);when(lyrics.get("owner","W","R")).thenReturn(revision);
        when(media.matchesGenerationRevision("owner","W","A2","R")).thenReturn(true);
        when(generation.list("owner","W")).thenReturn(List.of(new GenerationApi.Task("T","W","R",2,KEY,"歌名","歌词","民谣","stub",GenerationStatus.SUCCEEDED,NOW,"A1",null,NOW,NOW)));
        run("select_audio",Map.of("assetId","A2"));verify(media).select("owner","W","A2",1);
        var snapshot=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq("W"),snapshot.capture(),eq("T"),eq(5L));
        var card=new SongCardProjector(works,lyrics,media).withGeneration(generation).project("owner","W",snapshot.getValue());
        assertEquals("A2",card.assetId());assertEquals("GENERATED",card.audioOrigin());assertEquals("这版歌词",card.lyrics());
    }
    @Test void importedAudioNeverClaimsGeneratedLyrics(){
        var asset=new MediaApi.Asset("import","W","IMPORTED","audio/wav",10,"sha",NOW);
        when(media.get("owner","W","import")).thenReturn(asset);
        var card=new SongCardProjector(works,lyrics,media).withGeneration(generation).project("owner","W","{\"assetId\":\"import\"}");
        assertEquals("IMPORTED",card.audioOrigin());assertEquals("",card.lyrics());assertNull(card.revisionId());
    }
    @Test void reopeningAWorkKeepsTheSelectedSecondCandidateAndRealCover(){
        var asset=new MediaApi.Asset("A2","W","GENERATED","audio/wav",10,"sha",NOW,false,"T","second");
        when(media.get("owner","W","A2")).thenReturn(asset);when(media.selection("owner","W")).thenReturn(new MediaApi.Selection("A2",2,asset));
        when(generation.list("owner","W")).thenReturn(List.of(new GenerationApi.Task("T","W","R",2,KEY,"歌名","歌词","民谣","stub",GenerationStatus.SUCCEEDED,NOW,"A1",null,NOW,NOW)));
        when(lyrics.get("owner","W","R")).thenReturn(new LyricsApi.Revision("R","W",2,"歌名","当前歌词","AI",true,NOW));
        when(media.matchesGenerationRevision("owner","W","A2","R")).thenReturn(true);
        when(covers.state("owner","W")).thenReturn(new CoverApi.State("cover-1",1,List.of(),List.of(),true));
        var projected=new SongCardProjector(works,lyrics,media).withGeneration(generation).withCovers(covers).project("owner","W",null);
        assertEquals("A2",projected.assetId());assertEquals("/api/app/works/W/covers/cover-1",projected.coverUrl());
    }
    @Test void explicitlyAssociatedImportedLyricsRemainUserProvidedAndShareable(){
        var asset=new MediaApi.Asset("import","W","IMPORTED","audio/wav",10,"sha",NOW);when(media.get("owner","W","import")).thenReturn(asset);
        when(lyrics.get("owner","W","R")).thenReturn(new LyricsApi.Revision("R","W",2,"标题","用户给出的歌词","MANUAL",true,NOW));
        run("select_audio",Map.of("assetId","import","revisionId","R"));
        var snapshot=org.mockito.ArgumentCaptor.forClass(String.class);verify(conversations).applyToolResult(eq("owner"),eq("C"),isNull(),eq("{}"),eq("W"),snapshot.capture(),isNull(),eq(5L));
        var projected=new SongCardProjector(works,lyrics,media).project("owner","W",snapshot.getValue());
        assertEquals("IMPORTED",projected.audioOrigin());assertEquals("用户给出的歌词",projected.lyrics());assertEquals("R",projected.revisionId());
    }
    @Test void noSourceCoverRequestsUploadInsteadOfSubmittingAi(){
        when(covers.state("owner","W")).thenReturn(new CoverApi.State(null,0,List.of(),List.of(),true));
        var result=run("edit_cover",Map.of("prompt","改成蓝色"));assertNull(result.action());assertEquals("upload",result.commands().get(0).kind());
        verify(covers,never()).edit(any(),any(),any(),any(),any());
    }
    @Test void discoveredSamplesAreExplicitAndUseOnlyExistingWhitelist(){
        var result=run("discover_music",Map.of());assertEquals(9,result.items().size());
        assertEquals(HotSongService.TRACKS,new HashSet<>(result.items().stream().map(AgentApplicationActions.Item::id).toList()));
        assertTrue(result.items().stream().allMatch(i->i.subtitle().contains("试听样曲")));
    }
    static AgentMessage message(String body){return new AgentMessage("M","C",1,"assistant","TEXT","DONE",body,null,NOW);}
}
