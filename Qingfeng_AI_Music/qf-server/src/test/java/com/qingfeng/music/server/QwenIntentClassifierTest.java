package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.IntentResult;
import com.qingfeng.music.infrastructure.provider.QwenIntentClassifier;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class QwenIntentClassifierTest {
    @Test void sendsQwenJsonRequestWithoutLegacyThinkingParameter() throws Exception {
        var json = new ObjectMapper();
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var content = "{\"tool\":\"generate_lyrics\",\"confidence\":0.91,\"replyText\":\"准备写歌词\"}";
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                "finish_reason", "stop", "message", Map.of("content", content)))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var classifier = new QwenIntentClassifier("offline-key", "qwen3.8-flash",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"),
                HttpClient.newHttpClient());
            var result = classifier.classify("帮我写歌词", "zh-CN");
            assertNotNull(result);
            assertEquals("generate_lyrics", result.tool());
            assertEquals(IntentResult.Source.LLM, result.source());
            assertEquals("帮我写歌词", result.args().get("text"));

            var body = json.readTree(received.get());
            assertEquals("qwen3.8-flash", body.path("model").asText());
            assertEquals("json_object", body.path("response_format").path("type").asText());
            assertTrue(body.path("enable_thinking").isBoolean());
            assertFalse(body.path("enable_thinking").asBoolean());
            assertFalse(body.has("thinking"));
            assertTrue(body.path("messages").path(0).path("content").asText().contains("JSON"));
            assertFalse(received.get().contains("offline-key"));
        } finally {
            server.stop(0);
        }
    }
}
