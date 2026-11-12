package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.*;
import com.qingfeng.music.sharing.application.*;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentShareTitleTest {
    WorkApi works=mock(WorkApi.class); LyricsApi lyrics=mock(LyricsApi.class); MediaApi media=mock(MediaApi.class);
    ShareRepository repository=mock(ShareRepository.class);SharedMediaAccess audio=mock(SharedMediaAccess.class);SharedCoverAccess covers=mock(SharedCoverAccess.class);
    SharingService shares=new SharingService(works,lyrics,media,repository,audio,covers);
    AgentShareTitleTest(){
        when(works.get("owner","W")).thenReturn(new WorkApi.Work("W","妈妈","生日","私有故事","生日快乐","民谣","新作品名",2,Instant.EPOCH,Instant.EPOCH));
        when(lyrics.get("owner","W","R")).thenReturn(new LyricsApi.Revision("R","W",1,"原歌词标题","原歌曲歌词","AI",true,Instant.EPOCH));
        when(media.get("owner","W","A")).thenReturn(new MediaApi.Asset("A","W","GENERATED","audio/wav",10,"sha",Instant.EPOCH));
        when(media.matchesGenerationRevision("owner","W","A","R")).thenReturn(true);
    }
    @Test void explicitSavedWorkTitleChangesOnlyTheGiftTitle(){
        shares.createWithWorkTitle("owner","W","R","A","新作品名");
        var capture=org.mockito.ArgumentCaptor.forClass(ShareRepository.Snapshot.class);verify(repository).insert(capture.capture());
        assertEquals("新作品名",capture.getValue().title());assertEquals("原歌曲歌词",capture.getValue().lyrics());
        assertEquals("妈妈",capture.getValue().recipient());assertEquals("生日快乐",capture.getValue().message());assertEquals("A",capture.getValue().assetId());
    }
    @Test void renameDoesNotBypassAudioRevisionValidation(){
        when(media.matchesGenerationRevision("owner","W","A","R")).thenReturn(false);
        assertThrows(DomainException.class,()->shares.createWithWorkTitle("owner","W","R","A","新作品名"));verifyNoInteractions(repository);
    }
    @Test void inventedOrStaleTitleCannotBeSharedAndLegacySharingKeepsRevisionTitle(){
        assertThrows(DomainException.class,()->shares.createWithWorkTitle("owner","W","R","A","凭空标题"));verifyNoInteractions(repository);
        shares.create("owner","W","R","A");var capture=org.mockito.ArgumentCaptor.forClass(ShareRepository.Snapshot.class);verify(repository).insert(capture.capture());assertEquals("原歌词标题",capture.getValue().title());
    }
}
