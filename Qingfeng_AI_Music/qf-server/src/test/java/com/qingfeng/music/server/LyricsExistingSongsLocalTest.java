package com.qingfeng.music.server;

import com.qingfeng.music.lyrics.playback.PlaybackLyrics;
import com.qingfeng.music.server.hotsong.RecommendationSamples;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in replay of real, read-only exported timelines into synthetic local identities/audio records. */
@EnabledIfEnvironmentVariable(named="QF_LYRIC_DATASET_TEST",matches="true")
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lyrics_dataset;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qingfeng.generation.enabled=true","qingfeng.lyrics.timing.enabled=true","qingfeng.usage.timing.daily-per-creator=2"})
class LyricsExistingSongsLocalTest extends LyricsPreparationIntegrationTest {
    @Test void allPublishedTimelinesAndCuratedSamplesAreReadableInAnIsolatedDatabase()throws Exception {
        var json=new ObjectMapper();
        var root=json.readTree(Files.readAllBytes(Path.of(System.getenv("QF_LYRIC_DATASET_PATH"))));
        assertEquals(27,root.path("songs").size());int rows=0;
        for(var song:root.path("songs")) {
            var f=fixture();var pub=publish(f);
            var words=json.treeToValue(song.path("words"),PlaybackLyrics.Word[].class);
            assertFalse(PlaybackLyrics.lines(List.of(words)).isEmpty());
            jdbc.update("INSERT INTO media_lyrics_timeline(asset_id,work_id,state,words_json,updated_at) VALUES(?,?,'READY',?,CURRENT_TIMESTAMP)",f.asset(),f.work(),json.writeValueAsString(words));
            long version=song.path("version").asLong();
            jdbc.update("UPDATE artist_song_release SET version=? WHERE id=?",version,pub[1]);
            var snapshot=profiles.publicPlaybackLyrics(pub[0],pub[1],version);
            assertEquals("READY",snapshot.status());assertFalse(snapshot.lines().isEmpty());assertFalse(snapshot.canSync());
            assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->profiles.publicPlaybackLyrics(pub[0],pub[1],version+1));
            rows++;
        }
        int samples=0;
        var http=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.qingfeng.music.server.web.publicapi.HotSongLyricsController(preparation)).build();
        for(var song:RecommendationSamples.SONGS) {
            var value=preparation.sample(song.id());assertEquals("READY",value.status(),song.id());
            assertFalse(value.lines().isEmpty(),song.id());assertFalse(value.canSync());
            var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/hot-songs/"+song.id()+"/lyrics")).andReturn().getResponse();
            assertEquals(200,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
            assertEquals("READY",json.readTree(response.getContentAsByteArray()).path("status").asText());samples++;
        }
        assertEquals(404,http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/public/hot-songs/unknown-file/lyrics")).andReturn().getResponse().getStatus());
        assertEquals(0,timing.calls.get());assertEquals(0,transcribe.submits.get());
        var receipt=Map.of("publishedSongs",rows,"hotSongs",samples,"database","isolated-h2","originalRecordsChanged",false,"providerCallsDuringReplay",0);
        Files.writeString(Path.of(System.getenv("QF_LYRIC_DATASET_RECEIPT")),json.writeValueAsString(receipt));
    }
}
