package com.qingfeng.music.server;

import com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.common.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
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
}
