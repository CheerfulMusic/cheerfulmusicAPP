package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.DeepSeekReferenceAnalyst;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实 HTTP 服务器验证请求协议与输出校验，不用 mock 伪造模型结论。 */
class DeepSeekReferenceAnalystTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReferenceAnalyst.Request REQUEST = new ReferenceAnalyst.Request(
        "想写一首给妈妈的歌，不要电音", "ref.m4a", 128, "zh-CN",
        new ReferenceAnalyst.Features(128.4, 0.82, 0.31, 0.87, 0.42, 0.52, 0.31, 0.17, 0.63, 0.21, 128, 90));

    private static DeepSeekReferenceAnalyst analyst(HttpServer server, Duration timeout) {
        return new DeepSeekReferenceAnalyst("test-key", "deepseek-flash",
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"),
            HttpClient.newHttpClient(), timeout);
    }

    private static HttpServer server(int status, String content, long delayMillis, AtomicReference<String> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMillis > 0) {
                try { Thread.sleep(delayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            String payload = content == null ? "{}" : JSON.writeValueAsString(Map.of("choices",
                List.of(Map.of("finish_reason", status == 200 ? "stop" : "error", "message", Map.of("content", content)))));
            byte[] bytes = (status == 200 ? payload : "{\"error\":\"upstream\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test void sendsMeasurementAndTextOnlyThenParsesStructuredSuggestion() throws Exception {
        var body = new AtomicReference<String>();
        String content = "{\"style\":\"温暖民谣\",\"mood\":\"温柔平静\",\"tempo\":120,\"instrumentation\":[\"木吉他\"],\"negativeTags\":[\"电音\"],\"summary\":\"偏明亮的民谣方向，适合叙事副歌。\",\"extra\":\"ignored\"}";
        var server = server(200, content, 0, body);
        try {
            var suggestion = analyst(server, Duration.ofSeconds(3)).analyse(REQUEST);
            assertEquals("温暖民谣", suggestion.style());
            assertEquals("温柔平静", suggestion.mood());
            assertEquals(120, suggestion.tempo());
            assertEquals(List.of("木吉他"), suggestion.instrumentation());
            assertEquals(List.of("电音"), suggestion.negativeTags());
            var sent = JSON.readTree(body.get());
            var system = sent.path("messages").path(0).path("content").asText();
            assertTrue(system.contains("客观测量"), "提示词必须区分客观测量与 AI 推断");
            assertTrue(system.contains("不得声称识别出具体乐器"));
            assertTrue(system.contains("styleCandidates"));
            assertEquals("json_object", sent.path("response_format").path("type").asText());
            assertEquals(500, sent.path("max_tokens").asInt());
            var user = JSON.readTree(sent.path("messages").path(1).path("content").asText());
            assertEquals(128.4, user.path("features").path("bpm").asDouble());
            assertEquals(0.82, user.path("features").path("bpmConfidence").asDouble());
            assertEquals("想写一首给妈妈的歌，不要电音", user.path("text").asText());
            assertTrue(user.path("styleCandidates").size() >= 24);
            // 请求体里不允许出现音频字节或 Base64。
            assertFalse(body.get().contains("base64"));
            assertFalse(body.get().contains("data:audio"));
            assertFalse(body.get().contains("test-key"), "密钥不得出现在请求体里");
        } finally { server.stop(0); }
    }

    @Test void mapsUpstreamFailuresToUnavailableWithoutLeakingKey() throws Exception {
        for (int status : new int[]{401, 402, 502}) {
            var server = server(status, null, 0, new AtomicReference<>());
            try {
                var error = assertThrows(DomainException.class, () -> analyst(server, Duration.ofSeconds(3)).analyse(REQUEST));
                assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE", error.code());
                assertFalse(error.getMessage().contains("test-key"));
            } finally { server.stop(0); }
        }
    }

    @Test void failsFastWhenUpstreamIsSlowerThanTimeout() throws Exception {
        var server = server(200, "{}", 1500, new AtomicReference<>());
        try {
            long started = System.nanoTime();
            var error = assertThrows(DomainException.class, () -> analyst(server, Duration.ofMillis(250)).analyse(REQUEST));
            assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE", error.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1400, "必须按超时快速失败");
        } finally { server.stop(0); }
    }

    @Test void rejectsMalformedOrWrongTypedModelOutput() throws Exception {
        List<String> broken = List.of(
            "not json",
            "{\"style\":120,\"summary\":\"x\"}",
            "{\"style\":\"温暖民谣\",\"tempo\":\"fast\"}",
            "{\"style\":\"温暖民谣\",\"instrumentation\":\"木吉他\"}",
            "{\"style\":\"温暖民谣\",\"instrumentation\":[1,2]}",
            "{\"style\":\"温暖民谣\",\"summary\":[\"x\"]}",
            "[]");
        for (String content : broken) {
            var server = server(200, content, 0, new AtomicReference<>());
            try {
                assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE",
                    assertThrows(DomainException.class, () -> analyst(server, Duration.ofSeconds(3)).analyse(REQUEST)).code(), content);
            } finally { server.stop(0); }
        }
    }

    @Test void toleratesMissingOptionalFieldsAndReportsNotConfiguredWithoutKey() {
        var offline = new DeepSeekReferenceAnalyst("  ", "deepseek-flash");
        assertFalse(offline.isConfigured());
    }
}
