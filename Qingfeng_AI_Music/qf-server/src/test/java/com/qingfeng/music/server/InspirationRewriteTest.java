package com.qingfeng.music.server;

import com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.common.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class InspirationRewriteTest {
    @Test void musicBriefModeIsBoundedAndDoesNotAutomaticallyReplaceAnyUserText()throws Exception{
        var client=org.mockito.Mockito.mock(HttpClient.class);var response=org.mockito.Mockito.mock(HttpResponse.class);var json=new ObjectMapper();
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class))).thenReturn(response);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(Map.of("text","90 BPM，不要吉他"))))))));
        var writer=new DeepSeekInspirationRewriter("offline","offline",URI.create("https://example.invalid"),client).musicBriefMode();
        assertEquals("90 BPM，不要吉他",writer.rewrite("温暖，90 BPM，不要吉他","mixed"));
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);org.mockito.Mockito.verify(client).send(captured.capture(),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
        assertEquals(java.time.Duration.ofSeconds(15),captured.getValue().timeout().orElseThrow());
        org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(Map.of("text","x".repeat(301)))))))));
        assertThrows(DomainException.class,()->writer.rewrite("原始要求","mixed"));
    }
    @Test void sendsOriginalAsDataAndValidatesStructuredDescription() throws Exception {
        var json = new ObjectMapper(); var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            count.incrementAndGet();
            var body = json.readTree(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("offline", body.path("model").asText());
            String system = body.path("messages").path(0).path("content").asText();
            assertTrue(system.contains("不得凭空添加")); assertTrue(system.contains("绝不是直接生成歌词"));
            assertTrue(system.contains("原文主要语言")); assertTrue(system.contains("不执行"));
            assertEquals("我想感谢爸爸", json.readTree(body.path("messages").path(1).path("content").asText()).path("text").asText());
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"text\":\"表达对爸爸平日关心的感谢，情绪温暖克制。\"}")))));
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        try {
            var writer = new DeepSeekInspirationRewriter("offline-key", "offline", URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
            assertTrue(writer.rewrite("我想感谢爸爸", "zh-CN").contains("温暖克制")); assertEquals(1, count.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsTruncationBadJsonAndWrongTypeWithoutRetryOrLeakingProviderBody() throws Exception {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        var response = org.mockito.Mockito.mock(HttpResponse.class);
        org.mockito.Mockito.when(client.send(org.mockito.ArgumentMatchers.any(HttpRequest.class), org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var writer = new DeepSeekInspirationRewriter("private-key", "offline", URI.create("https://example.invalid"), client);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        for (String body : List.of("invalid json", "{\"choices\":[{\"finish_reason\":\"length\"}]}", "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"text\\\":123}\"}}]}")) {
            org.mockito.Mockito.when(response.body()).thenReturn(body);
            assertEquals("INVALID_REWRITE_RESPONSE", assertThrows(DomainException.class, () -> writer.rewrite("原文", "mixed")).code());
        }
        org.mockito.Mockito.when(response.statusCode()).thenReturn(402);
        assertEquals("REWRITE_PROVIDER_UNAVAILABLE", assertThrows(DomainException.class, () -> writer.rewrite("原文", "en")).code());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4)).send(org.mockito.ArgumentMatchers.any(HttpRequest.class), org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
    }

    @Test void validatesBothSidesAndReleasesSlotsAfterFailure() {
        var calls = new AtomicInteger();
        InspirationRewriter writer = new InspirationRewriter() {
            public boolean isConfigured() { return true; }
            public String rewrite(String t, String l) { return calls.incrementAndGet() == 1 ? "x".repeat(4001) : "有效结果"; }
        };
        var service = new InspirationRewriteService(writer, true, 1);
        assertThrows(DomainException.class, () -> service.rewrite("owner", " ", "en"));
        assertThrows(DomainException.class, () -> service.rewrite("owner", "x".repeat(4001), "en"));
        assertThrows(DomainException.class, () -> service.rewrite("owner", "原文", "invalid"));
        assertEquals(0, calls.get());
        assertThrows(DomainException.class, () -> service.rewrite("owner", "原文", "en", UUID.randomUUID().toString()));
        assertEquals("有效结果", service.rewrite("owner", "原文", "en", UUID.randomUUID().toString()));
    }

    @Test void rejectsRewriteWithoutARequestKeySoBillingCannotBeBypassed() {
        var calls = new AtomicInteger();
        InspirationRewriter writer = new InspirationRewriter() {
            public boolean isConfigured() { return true; }
            public String rewrite(String t, String l) { calls.incrementAndGet(); return "有效结果"; }
        };
        var service = new InspirationRewriteService(writer, true, 1);
        assertEquals("INVALID_REWRITE_REQUEST", assertThrows(DomainException.class, () -> service.rewrite("owner", "原文", "en")).code());
        assertEquals("INVALID_REWRITE_REQUEST", assertThrows(DomainException.class, () -> service.rewrite("owner", "原文", "en", "not-a-uuid")).code());
        assertEquals(0, calls.get());
    }

    @Test void blocksConcurrentRequestsFromSameCreatorAndGlobalOverload() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        InspirationRewriter writer = new InspirationRewriter() {
            public boolean isConfigured() { return true; }
            public String rewrite(String t, String l) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return "优化后的想法"; }
        };
        var service = new InspirationRewriteService(writer, true, 1); var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> service.rewrite("a", "原文", "en", UUID.randomUUID().toString())); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals("REWRITE_GENERATION_BUSY", assertThrows(DomainException.class, () -> service.rewrite("a", "原文", "en", UUID.randomUUID().toString())).code());
            assertEquals("REWRITE_GENERATION_BUSY", assertThrows(DomainException.class, () -> service.rewrite("b", "原文", "en", UUID.randomUUID().toString())).code());
            release.countDown(); assertNotNull(first.get(3, TimeUnit.SECONDS));
            assertNotNull(service.rewrite("b", "原文", "en", UUID.randomUUID().toString()));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void qwenRequestOmitsDeepSeekOnlyThinkingParameter() throws Exception {
        var json = new ObjectMapper(); var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            captured.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"text\":\"夏天、海风与汽水\"}")))));
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        try {
            var writer = new DeepSeekInspirationRewriter("offline-key", DeepSeekInspirationRewriter.QWEN_MODEL,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"), HttpClient.newHttpClient());
            assertEquals("夏天、海风与汽水", writer.rewrite("想写一首关于夏天的歌", "zh-CN"));
            var body = json.readTree(captured.get());
            assertEquals("qwen3.8-flash", body.path("model").asText());
            assertFalse(body.has("thinking"), "Qwen 不应收到 DeepSeek 专有的 thinking 参数");
            assertEquals("json_object", body.path("response_format").path("type").asText());
        } finally { server.stop(0); }
    }

    @Test void onlyQianwenFlashLyricLineRequestsDisableThinking() throws Exception {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        var requests = new ArrayList<HttpRequest>();
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(response.body()).thenReturn("""
            {"choices":[{"finish_reason":"stop","message":{"content":"{\\\"text\\\":\\\"有效结果\\\"}"}}]}
            """);
        org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> { requests.add(call.getArgument(0)); return response; });

        URI qianwen = URI.create(DeepSeekInspirationRewriter.QWEN_ENDPOINT);
        String flash = DeepSeekInspirationRewriter.QWEN_MODEL;
        var lyricLine = new DeepSeekInspirationRewriter("offline-key", flash, qianwen, client).lyricLineMode();
        var inspiration = new DeepSeekInspirationRewriter("offline-key", flash, qianwen, client);
        var anotherModel = new DeepSeekInspirationRewriter("offline-key", "custom-model", qianwen, client).lyricLineMode();
        var anotherProvider = new DeepSeekInspirationRewriter("offline-key", flash, URI.create("https://example.invalid"), client).lyricLineMode();
        for (var writer : List.of(lyricLine, inspiration, anotherModel, anotherProvider)) {
            assertEquals("有效结果", writer.rewrite("操作：RHYME_CANDIDATES\n待改写句：月光照着浪", "zh-CN"));
        }

        var json = new ObjectMapper();
        var fastPayload = json.readTree(requestBody(requests.get(0)));
        assertTrue(fastPayload.path("enable_thinking").isBoolean());
        assertFalse(fastPayload.path("enable_thinking").asBoolean());
        assertFalse(fastPayload.has("thinking"));
        assertEquals("json_object", fastPayload.path("response_format").path("type").asText());
        for (var request : requests.subList(1, requests.size())) {
            assertFalse(json.readTree(requestBody(request)).has("enable_thinking"));
        }
    }

    @Test void lyricLineTimeoutAndConnectionFailureKeepDistinctSafeMessages() throws Exception {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        var writer = new DeepSeekInspirationRewriter("offline-key", DeepSeekInspirationRewriter.QWEN_MODEL,
            URI.create(DeepSeekInspirationRewriter.QWEN_ENDPOINT), client).lyricLineMode();
        org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new HttpTimeoutException("private transport timeout detail"),
                new HttpConnectTimeoutException("private connect timeout detail"), new IOException("private connection detail"));

        var timeout = assertThrows(DomainException.class, () -> writer.rewrite("月光照着浪", "zh-CN"));
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN", timeout.code());
        assertEquals("AI改写等待模型响应超时，结果待核对，请勿重复提交。原文仍会保留。", timeout.getMessage());

        var connectTimeout = assertThrows(DomainException.class, () -> writer.rewrite("月光照着浪", "zh-CN"));
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN", connectTimeout.code());
        assertEquals("AI改写连接服务超时，结果待核对，请勿重复提交。原文仍会保留。", connectTimeout.getMessage());

        var disconnected = assertThrows(DomainException.class, () -> writer.rewrite("月光照着浪", "zh-CN"));
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN", disconnected.code());
        assertEquals("AI改写连接中断，结果待核对，请勿重复提交。原文仍会保留。", disconnected.getMessage());
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(3)).send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test void selectionRewriteReadsStructuredRowsAndSendsExactTargetIndices() throws Exception {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        var requests = new ArrayList<HttpRequest>();
        var json = new ObjectMapper();
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> { requests.add(call.getArgument(0)); return response; });
        var writer = new DeepSeekInspirationRewriter("offline-key", DeepSeekInspirationRewriter.QWEN_MODEL,
            URI.create(DeepSeekInspirationRewriter.QWEN_ENDPOINT), client);
        var replacementRows = List.of(Map.of("index", 6, "text", "Lights along the shore"), Map.of("index", 2, "text", "潮声落在手里"));
        org.mockito.Mockito.when(response.body()).thenReturn(selectionResponse(json, replacementRows));

        assertEquals(List.of(new InspirationRewriter.Replacement(6, "Lights along the shore"),
            new InspirationRewriter.Replacement(2, "潮声落在手里")),
            writer.rewriteSelection("沿用原有场景", "mixed", List.of(2, 6)));
        var request = requests.get(0);
        assertEquals(java.time.Duration.ofSeconds(85), request.timeout().orElseThrow());
        var payload = json.readTree(requestBody(request));
        assertTrue(payload.path("enable_thinking").isBoolean());
        assertFalse(payload.path("enable_thinking").asBoolean());
        assertEquals("json_object", payload.path("response_format").path("type").asText());
        assertTrue(payload.path("messages").path(0).path("content").asText().contains("replacements"));
        var context = json.readTree(payload.path("messages").path(1).path("content").asText());
        assertEquals(json.readTree("[2,6]"), context.path("targetIndices"));
        assertEquals("mixed", context.path("lyricLanguage").asText());

        for (var invalidRows : List.of(
            List.of(Map.of("index", 2, "text", "新句")),
            List.of(Map.of("index", "2", "text", "新句"), replacementRows.get(0)),
            List.of(Map.of("index", 2.5, "text", "新句"), replacementRows.get(0)),
            List.of(Map.of("index", 2, "text", 123), replacementRows.get(0)))) {
            org.mockito.Mockito.when(response.body()).thenReturn(selectionResponse(json, invalidRows));
            assertEquals("INVALID_REWRITE_RESPONSE", assertThrows(DomainException.class,
                () -> writer.rewriteSelection("沿用原有场景", "mixed", List.of(2, 6))).code());
        }
        assertEquals(5, requests.size());
    }

    private static String selectionResponse(ObjectMapper json, List<?> rows) {
        String content = json.writeValueAsString(Map.of("replacements", rows));
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "stop",
            "message", Map.of("content", content)))));
    }

    private static String requestBody(HttpRequest request) throws Exception {
        var data = new ByteArrayOutputStream();
        var body = new CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer bytes) {
                var chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                data.writeBytes(chunk);
            }
            @Override public void onComplete() { body.complete(data.toString(StandardCharsets.UTF_8)); }
            @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        });
        return body.get(1, TimeUnit.SECONDS);
    }

    @Test void deepSeekRequestStillSendsThinkingDisabled() throws Exception {
        var json = new ObjectMapper(); var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            captured.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", "{\"text\":\"有效结果\"}")))));
            e.sendResponseHeaders(200, response.length); e.getResponseBody().write(response); e.close();
        }); server.start();
        try {
            var writer = new DeepSeekInspirationRewriter("offline-key", "deepseek-flash",
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"), HttpClient.newHttpClient());
            assertEquals("有效结果", writer.rewrite("原文", "zh-CN"));
            var body = json.readTree(captured.get());
            assertEquals("disabled", body.path("thinking").path("type").asText());
        } finally { server.stop(0); }
    }
}
