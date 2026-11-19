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
    @Test void bothPhotoProvidersRequestTheDefaultAbcdSections() throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", request -> {
            received.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String song = json.writeValueAsString(Map.of("title", "海边", "lyrics", "[Verse]\n风吹过海面",
                "photoSummary", "海面与沙滩", "plan", Map.of("requirements", "", "suggestedArrangement", "Warm folk",
                    "negativeTags", "", "summaryZh", "民谣", "summaryEn", "Folk", "vocalGender", "AUTO")));
            byte[] body = json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                "message", Map.of("content", song))))).getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(200, body.length);
            try (var sink = request.getResponseBody()) { sink.write(body); }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            var work = new WorkApi.Work("photo", "", "", "海边的照片", "", "自动推荐", "照片创作", 0, Instant.now(), Instant.now());
            for (String provider : List.of("DOUBAO", "QWEN")) {
                var writer = new ChatPhotoLyricsWriter(provider, "offline", "offline-model", endpoint, HttpClient.newHttpClient());
                writer.write(work, "zh-CN", List.of(new byte[]{1, 2, 3}), new LyricsWriter.Direction("民谣", "", "AUTO"));
                String prompt = json.readTree(received.get()).path("messages").path(0).path("content").asText();
                assertTrue(prompt.contains(PhotoLyricsWriter.DEFAULT_STRUCTURE.instruction()));
                assertTrue(prompt.contains("[Verse] → [Pre-Chorus] → [Chorus] → [Interlude] → [Pre-Chorus] → [Chorus] → [Bridge] → [Chorus]"));
            }
        } finally { server.stop(0); }
    }
}
