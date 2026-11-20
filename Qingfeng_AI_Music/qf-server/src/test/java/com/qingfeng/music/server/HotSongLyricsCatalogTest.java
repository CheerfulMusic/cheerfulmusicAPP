package com.qingfeng.music.server;

import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.server.hotsong.RecommendationSamples;
import com.qingfeng.music.server.lyrics.LyricsPreparation;
import com.qingfeng.music.server.web.publicapi.HotSongLyricsController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Packaged catalogue playback must work without SQL, providers or user billing. */
class HotSongLyricsCatalogTest {
    @Test void everyCuratedSongHasVersionBoundLyricsOrAnExplicitInstrumentalMarker() throws Exception {
        var db=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);
        var repository=mock(PlaybackLyricsRepository.class);var original=mock(LyricsTimingProvider.class);
        var transcription=mock(AudioTranscriptionProvider.class);
        var preparation=new LyricsPreparation(db,tx,repository,original,transcription,0);
        var controller=new HotSongLyricsController(preparation);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();int ready=0,instrumental=0;
        for(var song:RecommendationSamples.SONGS){
            String key=RecommendationSamples.objectKey(song.audio().substring("/media/".length())).orElseThrow();
            String hash=key.split("/")[4];
            var response=controller.lyrics(song.id(),hash);var value=response.getBody();
            assertEquals(200,response.getStatusCode().value(),song.id());assertEquals("no-store",response.getHeaders().getCacheControl());
            assertNotNull(value);assertFalse(value.canSync(),song.id());
            mvc.perform(get("/api/public/hot-songs/"+song.id()+"/lyrics").queryParam("audioHash",hash))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.status").value(value.status())).andExpect(jsonPath("$.canSync").value(false));
            if(song.id().equals("euphoric-drop")){
                assertEquals("INSTRUMENTAL",value.status());assertTrue(value.lines().isEmpty());instrumental++;
            }else{
                assertEquals("READY",value.status(),song.id());assertFalse(value.lines().isEmpty(),song.id());
                double previous=-1;
                for(var line:value.lines()){
                    assertTrue(line.start()>=previous&&line.end()>line.start(),song.id());previous=line.start();
                    assertFalse(line.text().isBlank());
                }
                ready++;
            }
        }
        assertEquals(21,ready);assertEquals(1,instrumental);
        verifyNoInteractions(db,tx,repository,original,transcription);
    }
    @Test void staleAudioVersionsAndUnknownSongsNeverReturnTheCurrentTimeline() throws Exception {
        var preparation=mock(LyricsPreparation.class);var controller=new HotSongLyricsController(preparation);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        for(var hash:new String[]{"000000000000","../private","","8E59749C37E9"}){
            var error=assertThrows(ResponseStatusException.class,()->controller.lyrics("body-language",hash));
            assertEquals(404,error.getStatusCode().value());
            mvc.perform(get("/api/public/hot-songs/body-language/lyrics").queryParam("audioHash",hash)).andExpect(status().isNotFound());
        }
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.lyrics("unknown",null)).getStatusCode().value());
        verifyNoInteractions(preparation);
    }
    @Test void existingSampleClientsCanStillReadWithoutTheOptionalAudioHash() {
        var preparation=mock(LyricsPreparation.class);
        when(preparation.sample("feng-ye-xing")).thenReturn(new PlaybackLyrics.Snapshot("READY","歌",java.util.List.of(new PlaybackLyrics.Line("歌",1,2)),false));
        assertEquals("READY",new HotSongLyricsController(preparation).lyrics("feng-ye-xing",null).getBody().status());
        verify(preparation).sample("feng-ye-xing");
    }
}
