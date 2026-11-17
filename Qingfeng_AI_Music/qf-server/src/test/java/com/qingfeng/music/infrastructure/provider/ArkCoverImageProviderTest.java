package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.cover.application.CoverImageProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ArkCoverImageProviderTest {
    @Test void usesThePinnedModelAndDistinguishesRejectionFromAnUnknownOutcome() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var code = new AtomicInteger(200);
        var payload = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        server.createContext("/api/v3/images/generations", request -> {
            auth.set(request.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":[{\"url\":\"https://test.example/image.jpg\"}]}".getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(code.get(), body.length);
            try (var sink = request.getResponseBody()) { sink.write(body); }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/images/generations");
            var provider = new ArkCoverImageProvider("offline", ArkCoverImageProvider.MODEL, uri, HttpClient.newHttpClient());
            assertEquals("https://test.example/image.jpg", provider.generate("雨夜的车站"));
            var json = new ObjectMapper().readTree(payload.get());
            assertEquals("Bearer offline", auth.get());
            assertEquals("doubao-seedream-5-5-261028", json.path("model").asText());
            assertEquals("1024x1024", json.path("size").asText());
            assertEquals("jpeg", json.path("output_format").asText());
            assertEquals("雨夜的车站", json.path("prompt").asText());
            assertTrue(json.path("watermark").asBoolean());
            assertFalse(json.has("sequential_image_generation"));
            assertEquals("https://test.example/image.jpg", provider.edit(new byte[]{1,2,3}, "只修正歌名"));
            var edit = new ObjectMapper().readTree(payload.get());
            assertEquals("只修正歌名", edit.path("prompt").asText());
            assertEquals("data:image/jpeg;base64,AQID", edit.path("image").asText());
            code.set(429);
            assertEquals(429, assertThrows(CoverImageProvider.Rejected.class, () -> provider.generate("封面")).status());
            code.set(503);
            assertThrowsExactly(IOException.class, () -> provider.generate("封面"));
        } finally { server.stop(0); }
    }
}
