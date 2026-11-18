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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:lyrics_dataset;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qingfeng.invite-gate.enabled=true","qingfeng.invite-gate.codes=LYRICS-LOCAL-TEST",
        "qingfeng.invite-gate.signing-key=local-only-lyrics-signing-key-with-more-than-32-characters",
        "qingfeng.generation.enabled=true","qingfeng.lyrics.timing.enabled=true","qingfeng.usage.timing.daily-per-creator=2"})
class LyricsExistingSongsLocalTest extends LyricsPreparationIntegrationTest {
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final java.net.http.HttpClient browser=java.net.http.HttpClient.newHttpClient();
    private java.net.http.HttpResponse<String> request(String method,String path)throws Exception {
        return browser.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(5)).method(method,java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
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
            var response=request("GET","/api/public/artists/"+pub[0]+"/songs/"+pub[1]+"/"+version+"/lyrics");
            assertEquals(200,response.statusCode(),response.body());assertEquals("READY",json.readTree(response.body()).path("status").asText());
            assertEquals(snapshot.text(),json.readTree(response.body()).path("text").asText());
            if(rows==0) {
                assertEquals(404,request("GET","/api/public/artists/"+pub[0]+"/songs/"+pub[1]+"/"+(version+1)+"/lyrics").statusCode());
                jdbc.update("UPDATE artist_song_release SET show_on_profile=FALSE WHERE id=?",pub[1]);
                assertEquals(404,request("GET","/api/public/artists/"+pub[0]+"/songs/"+pub[1]+"/"+version+"/lyrics").statusCode());
            }
            assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->profiles.publicPlaybackLyrics(pub[0],pub[1],version+1));
            rows++;
        }
        int samples=0;
        for(var song:RecommendationSamples.SONGS) {
            var value=preparation.sample(song.id());assertEquals("READY",value.status(),song.id());
            assertFalse(value.lines().isEmpty(),song.id());assertFalse(value.canSync());
            var response=request("GET","/api/public/hot-songs/"+song.id()+"/lyrics");
            assertEquals(200,response.statusCode(),response.body());assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("READY",json.readTree(response.body()).path("status").asText());samples++;
        }
        assertEquals(404,request("GET","/api/public/hot-songs/unknown-file/lyrics").statusCode());
        assertEquals(403,request("POST","/api/public/hot-songs/feng-ye-xing/lyrics").statusCode());
        assertEquals(403,request("GET","/api/app/works").statusCode());
        assertEquals(0,timing.calls.get());assertEquals(0,transcribe.submits.get());
        var receipt=Map.of("publishedSongs",rows,"hotSongs",samples,"database","isolated-h2","originalRecordsChanged",false,"providerCallsDuringReplay",0);
        Files.writeString(Path.of(System.getenv("QF_LYRIC_DATASET_RECEIPT")),json.writeValueAsString(receipt));
    }
}
