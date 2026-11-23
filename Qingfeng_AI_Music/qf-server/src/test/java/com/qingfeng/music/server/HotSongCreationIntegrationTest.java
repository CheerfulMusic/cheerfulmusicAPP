package com.qingfeng.music.server;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.server.hotsong.HotSongCreationReferences;
import com.qingfeng.music.server.hotsong.HotSongService;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:hot_song_creation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.lyrics.enabled=true", "qingfeng.generation.enabled=true",
    "qingfeng.credits.enabled=true", "qingfeng.credits.lyrics-cost=5", "qingfeng.credits.music-cost=15",
    "qingfeng.generation.scheduler-enabled=false", "qingfeng.quick.scheduler-enabled=false"
})
@ActiveProfiles("test")
@Import(HotSongCreationIntegrationTest.Providers.class)
class HotSongCreationIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired MusicSettings music;
    @Autowired CreditsLedger credits;
    @Autowired TestLyrics writer;
    @Autowired TestMusic provider;

    @Test void allHomeSamplesHaveValidPreferencesAndUnknownIdsAreRejected() throws Exception {
        for (var id : HotSongService.TRACKS) {
            var reference = HotSongCreationReferences.find(id).orElseThrow(() -> new AssertionError(id));
            assertFalse(reference.title().isBlank());
            assertFalse(music.preview(reference.preferences()).style().isBlank());
        }
        var client = new Browser();
        client.call("GET", "public/hot-songs/unknown-track/creation-reference", null, 404);
        client.call("GET", "public/hot-songs/0485811c-0297-4513-9fb1-75f981af8707/creation-reference", null, 404);
    }

    @ParameterizedTest
    @CsvSource({
        "luo-yu-de-cao-chang, zh-CN, pop, nostalgic refrain, Verse 1|Verse 2|Chorus|Verse|Chorus, 毕业后和同桌重开旧书店, 第一排的旧书晒干了雨",
        "red-guofeng, zh-CN, chinese, plucked traditional strings, Verse|Pre-Chorus|Chorus|Interlude|Pre-Chorus|Chorus|Chorus, 驿站灯下守候归人, 一盏灯照在空阶上",
        "body-language, en, electronic, syncopated bass, Verse|Pre-Chorus|Chorus, Find a friend on the last train, We ride until the morning"
    })
    void referenceIsFreeAndOnlyConfirmedNewLyricsCanBeSubmitted(String track, String language, String genre,
            String style, String sections, String story, String lyric) throws Exception {
        var client = new Browser();
        client.csrf = client.call("GET", "public/csrf", null, 200).get("token").asText();
        var owner = client.call("POST", "public/session", Map.of(), 200).get("creatorId").asText();
        credits.credit(owner, 50, "seed-" + owner);
        long worksBefore = count("music_work");
        long tasksBefore = count("generation_task");
        int priorWrites = writer.calls.get();

        var reference = client.call("GET", "public/hot-songs/" + track + "/creation-reference", null, 200);
        assertEquals(50, credits.balance(owner));
        assertEquals(worksBefore, count("music_work"));
        assertEquals(tasksBefore, count("generation_task"));
        assertEquals(priorWrites, writer.calls.get());
        assertEquals(4, reference.size());
        assertFalse(reference.has("lyrics"));
        assertFalse(reference.has("audio"));
        assertFalse(reference.get("title").asText().isBlank());
        assertEquals(language, reference.get("language").asText());
        assertEquals(genre, reference.at("/preferences/choices/genre/0").asText());
        var expected = List.of(sections.split("\\|"));
        assertEquals(json.valueToTree(expected), reference.at("/structure/customSections"));

        var work = client.call("POST", "app/works", Map.of(
            "recipient", "", "occasion", "", "story", story, "message", "", "title", "",
            "style", "自动推荐", "vocalGender", "AUTO", "draftKey", key()), 200);
        var root = "app/works/" + work.get("id").asText();
        var settings = client.call("PUT", root + "/music-settings", Map.of(
            "expectedVersion", work.get("version").asLong(), "preferences", reference.get("preferences")), 200);

        writer.result = new LyricsWriter.Draft(language.equals("en") ? "The last train" : "灯照归途",
            expected.stream().map(section -> "[" + section + "]"
                + (section.equals("Interlude") ? "" : "\n" + lyric)).collect(Collectors.joining("\n\n")));
        var revision = client.call("POST", root + "/lyrics/generate", Map.of(
            "workVersion", settings.at("/work/version").asLong(), "requestKey", key(), "language", language,
            "templateId", "CUSTOM", "chorusFirst", false, "customSections", expected), 200);
        assertFalse(revision.get("confirmed").asBoolean());
        assertEquals(writer.result.lyrics(), revision.get("lyrics").asText());
        assertEquals(story, writer.work.story());
        assertTrue(writer.work.title().isBlank());
        assertTrue(writer.previous.isBlank());
        assertTrue(writer.direction.style().contains(style));
        assertEquals(priorWrites + 1, writer.calls.get());
        assertEquals(tasksBefore, count("generation_task"));

        long beforeMusic = credits.balance(owner);
        var revisionId = revision.get("id").asText();
        var submission = Map.of("revisionId", revisionId, "requestKey", key());
        assertEquals("LYRICS_NOT_CONFIRMED", client.call("POST", root + "/generations", submission, 409).get("code").asText());
        assertEquals(beforeMusic, credits.balance(owner));
        assertEquals(tasksBefore, count("generation_task"));
        assertTrue(client.call("POST", root + "/lyrics/" + revisionId + "/confirm", Map.of(), 200).get("confirmed").asBoolean());
        var task = client.call("POST", root + "/generations", submission, 200);
        assertEquals(revisionId, task.get("revisionId").asText());
        assertEquals(revision.get("lyrics").asText(), provider.validated.confirmedLyrics());
        assertTrue(provider.validated.style().contains(style));
        assertEquals(beforeMusic - 15, credits.balance(owner));
        assertEquals(task.get("id"), client.call("POST", root + "/generations", submission, 200).get("id"));
        assertEquals(beforeMusic - 15, credits.balance(owner));
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String csrf = "";

        JsonNode call(String method, String path, Object body, int expectedStatus) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/" + path))
                .header("Accept", "application/json");
            var payload = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                request.header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrf);
                payload = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            }
            var response = http.send(request.method(method, payload).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expectedStatus, response.statusCode(), method + " " + path + " " + response.body());
            return json.readTree(response.body());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {
        @Bean @Primary @Qualifier("customLyricsWriter") TestLyrics testLyrics() { return new TestLyrics(); }
        @Bean @Primary TestMusic testMusic() { return new TestMusic(); }
    }

    static class TestLyrics implements LyricsWriter {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        volatile Draft result;
        volatile WorkApi.Work work;
        volatile String previous;
        volatile Direction direction;
        public boolean isConfigured() { return true; }
        public Draft write(WorkApi.Work work, String language) { throw new AssertionError("Expected music direction"); }
        public Draft write(WorkApi.Work work, String language, String instructions, String previous, Direction direction) {
            calls.incrementAndGet();
            this.work = work;
            this.previous = previous;
            this.direction = direction;
            return result;
        }
    }

    static class TestMusic implements MusicProvider {
        volatile Request validated;
        public boolean isConfigured() { return true; }
        public String name() { return "local-reference-test"; }
        public void validate(Request request) { validated = request; }
        public Submission submit(Request request, String key) { throw new AssertionError("Scheduling is disabled"); }
        public Result query(String id) { throw new AssertionError("Scheduling is disabled"); }
    }
}
