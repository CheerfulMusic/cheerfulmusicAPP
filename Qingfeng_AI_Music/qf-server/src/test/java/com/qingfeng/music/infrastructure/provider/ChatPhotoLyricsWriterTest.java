package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.FallbackPhotoLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.lyrics.application.PhotoLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ChatPhotoLyricsWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkApi.Work WORK = new WorkApi.Work("photo", "", "", "海边的照片", "", "自动推荐",
        "照片创作", 0, Instant.now(), Instant.now());
    private static final List<byte[]> PHOTOS = List.of(new byte[]{1, 2, 3});
    private static final LyricsWriter.Direction DIRECTION = new LyricsWriter.Direction("民谣", "", "AUTO");
    private static final String FIRST_LYRICS = "[Verse]\n风吹过海面";
    private static final String REFINED_LYRICS = "[Verse]\n海风把潮声留在沙滩";
    private static final Reply SONG = new Reply(200, JSON.writeValueAsString(Map.of(
        "title", "海边", "lyrics", FIRST_LYRICS, "photoSummary", "海面与沙滩",
        "plan", Map.of("requirements", "", "suggestedArrangement", "Warm folk", "negativeTags", "",
            "summaryZh", "民谣", "summaryEn", "Folk", "vocalGender", "AUTO"))));

    @ParameterizedTest @ValueSource(strings = {"DOUBAO", "QWEN"})
    void refinesPhotoLyricsSeparatelyAndKeepsTheirMetadataAndRepairInput(String provider) throws Exception {
        try (var model = new PhotoModel(SONG, new Reply(200, REFINED_LYRICS), new Reply(200, "{\"6\":\"海风吹过沙滩\"}"))) {
            var writer = model.writer(provider);
            var draft = writer.writeValidated(WORK, "zh-CN", PHOTOS, DIRECTION, (selected, candidate) -> {
                assertSame(writer, selected);
                assertEquals(REFINED_LYRICS, candidate.lyrics());
                return candidate;
            });
            assertEquals(2, model.calls.size());

            var initial = model.calls.get(0);
            String prompt = initial.at("/messages/0/content").asText();
            assertTrue(prompt.contains(PhotoLyricsWriter.DEFAULT_STRUCTURE.instruction()));
            assertTrue(prompt.contains("[Verse] → [Pre-Chorus] → [Chorus] → [Interlude] → [Pre-Chorus] → [Chorus] → [Bridge] → [Chorus]"));
            assertEquals("image_url", initial.at("/messages/1/content/1/type").asText());
            assertEquals("json_object", initial.at("/response_format/type").asText());

            var review = model.calls.get(1);
            assertEquals(initial.path("model"), review.path("model"));
            assertEquals(LyricsWritingPrompt.refinement("zh-CN"), review.at("/messages/0/content").asText());
            assertFalse(review.has("response_format"));
            assertTrue(review.at("/messages/1/content").isString());
            var input = JSON.readTree(review.at("/messages/1/content").asText());
            assertEquals(FIRST_LYRICS, input.at("/candidate/lyrics").asText());
            assertEquals("海边的照片", input.at("/originalMaterial/story").asText());
            assertEquals("海面与沙滩", input.at("/originalMaterial/photoSummary").asText());
            assertEquals(PhotoLyricsWriter.DEFAULT_STRUCTURE.instruction(), input.at("/originalMaterial/revisionInstructions").asText());

            assertEquals(REFINED_LYRICS, draft.lyrics());
            assertEquals("海边", draft.title());
            assertEquals("Warm folk", draft.plan().suggestedArrangement());
            assertEquals("海面与沙滩", draft.plan().photoSummary());
            assertEquals(provider, draft.source());

            assertEquals(Map.of(6, "海风吹过沙滩"), writer.repairSections(
                new LyricsWriter.SectionRepair(WORK, "zh-CN", "", "", draft, Map.of(6, "Bridge"))));
            assertEquals(3, model.calls.size());
            var repair = JSON.readTree(model.calls.get(2).at("/messages/1/content").asText());
            assertEquals("海面与沙滩", repair.path("photoSummary").asText());
            assertEquals(REFINED_LYRICS, repair.path("lyrics").asText());
            assertEquals("Bridge", repair.at("/missingSections/6").asText());
        }
    }

    @ParameterizedTest @ValueSource(ints = {200, 503, 504})
    void failedRefinementNeitherReturnsTheInitialDraftNorRestartsPhotoGeneration(int status) throws Exception {
        try (var model = new PhotoModel(SONG, new Reply(status, "{\"title\":\"格式错误\"}"))) {
            var route = new FallbackPhotoLyricsWriter(model.writer("DOUBAO"), (work, language, photos, direction) -> {
                throw new AssertionError("A failed refinement must not start generation on the backup");
            });
            var failure = assertThrows(DomainException.class, () -> route.write(WORK, "zh-CN", PHOTOS, DIRECTION));
            assertEquals(status == 504 ? "LYRICS_PROVIDER_TIMEOUT" : "LYRICS_QUALITY_UNVERIFIED", failure.code());
            assertEquals(2, model.calls.size());
        }
    }

    private record Reply(int status, String content) {}

    private static final class PhotoModel implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> calls = new CopyOnWriteArrayList<>();

        PhotoModel(Reply... replies) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat", exchange -> {
                try (exchange) {
                    calls.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                    if (calls.size() > replies.length) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    Reply reply = replies[calls.size() - 1];
                    byte[] body = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                        "message", Map.of("content", reply.content())))));
                    exchange.sendResponseHeaders(reply.status(), body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
        }

        ChatPhotoLyricsWriter writer(String source) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            return new ChatPhotoLyricsWriter(source, "offline", "offline-model", endpoint, HttpClient.newHttpClient());
        }

        @Override public void close() { server.stop(0); }
    }
}
