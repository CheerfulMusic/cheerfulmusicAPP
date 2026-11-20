package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.lyrics.application.PhotoLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ChatPhotoLyricsWriterTest {
    @Test void bothPhotoProvidersUseTheDefaultAbcdSectionsAndRepairFromTheRecognizedScene() throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", request -> {
            received.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String song = json.writeValueAsString(Map.of("title", "海边", "lyrics", "[Verse]\n风吹过海面",
                "photoSummary", "海面与沙滩", "plan", Map.of("requirements", "", "suggestedArrangement", "Warm folk",
                    "negativeTags", "", "summaryZh", "民谣", "summaryEn", "Folk", "vocalGender", "AUTO")));
            String result = received.get().contains("missingSections")
                ? json.writeValueAsString(Map.of("6", "海风吹过沙滩")) : song;
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", result))))).getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (var sink = request.getResponseBody()) { sink.write(body); }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            var work = new WorkApi.Work("photo", "", "", "海边的照片", "", "自动推荐", "照片创作", 0, Instant.now(), Instant.now());
            for (String provider : List.of("DOUBAO", "QWEN")) {
                var writer = new ChatPhotoLyricsWriter(provider, "offline", "offline-model", endpoint, HttpClient.newHttpClient());
                var draft = writer.write(work, "zh-CN", List.of(new byte[]{1, 2, 3}), new LyricsWriter.Direction("民谣", "", "AUTO"));
                String prompt = json.readTree(received.get()).path("messages").path(0).path("content").asText();
                assertTrue(prompt.contains(PhotoLyricsWriter.DEFAULT_STRUCTURE.instruction()));
                assertTrue(prompt.contains("[Verse] → [Pre-Chorus] → [Chorus] → [Interlude] → [Pre-Chorus] → [Chorus] → [Bridge] → [Chorus]"));
                assertEquals(Map.of(6, "海风吹过沙滩"), writer.repairSections(
                    new LyricsWriter.SectionRepair(work, "zh-CN", "", "", draft, Map.of(6, "Bridge"))));
                var repair = json.readTree(received.get()).path("messages").path(1).path("content");
                assertTrue(repair.isString());
                var material = json.readTree(repair.asText());
                assertEquals("海面与沙滩", material.path("photoSummary").asText());
                assertEquals("Bridge", material.path("missingSections").path("6").asText());
            }
        } finally { server.stop(0); }
    }
}
