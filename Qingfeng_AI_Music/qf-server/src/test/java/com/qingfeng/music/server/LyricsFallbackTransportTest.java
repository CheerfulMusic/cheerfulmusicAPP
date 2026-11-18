package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.infrastructure.provider.QwenLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real adapters and loopback HTTP only; no credentials, model billing or business database. */
class LyricsFallbackTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LYRICS = "[Chorus]\n妈，也留一瓣给自己";
    private static final WorkApi.Work WORK = new WorkApi.Work("offline-work", "妈妈", "生日", "门口的小碗",
        "想让她少操心", "民谣", "小碗", 0, Instant.now(), Instant.now());

    static Stream<IOException> connectionFailures() {
        return Stream.of(new ConnectException("private-key"), new HttpConnectTimeoutException("private-key"),
            new UnknownHostException("private-key"));
    }

    @ParameterizedTest @MethodSource("connectionFailures")
    void unsubmittedConnectionFailureSwitchesToQwenAndCompletesItsDraftAndReview(IOException failure) throws Exception {
        var client = mock(HttpClient.class);
        when(client.<String>send(any(), any())).thenThrow(failure);
        try (var qwen = new QwenFixture()) {
            var writer = new FallbackLyricsWriter(primary(client), qwen.writer());
            var draft = writer.write(WORK, "zh-CN");
            assertEquals("QWEN", draft.source()); assertEquals(LYRICS, draft.lyrics());
            verify(client, times(2)).send(any(), any());
            assertEquals(2, qwen.calls.get());
        }
    }

    @Test void successfulConnectionRetryDoesNotInvokeBackup() throws Exception {
        var client = mock(HttpClient.class);
        var song = Map.of("title", "小碗", "lyrics", LYRICS, "plan", Map.of("requirements", "",
            "suggestedArrangement", "Acoustic guitar", "negativeTags", "", "summaryZh", "温暖民谣",
            "summaryEn", "Warm folk", "vocalGender", "AUTO"));
        var response = response(200, JSON.writeValueAsString(Map.of("status", "completed", "output", List.of(
            Map.of("type", "message", "role", "assistant", "status", "completed", "content", List.of(
                Map.of("type", "output_text", "text", JSON.writeValueAsString(song))))))));
        when(client.<String>send(any(), any())).thenThrow(new ConnectException()).thenReturn(response);
        var backup = mock(LyricsWriter.class);
        var draft = new FallbackLyricsWriter(primary(client), backup).write(WORK, "zh-CN", "保留原文", LYRICS);
        assertEquals("OPENAI", draft.source()); assertEquals(LYRICS, draft.lyrics());
        verify(client, times(2)).send(any(), any()); verify(backup, never()).write(any(), any(), any(), any());
    }

    @Test void connectionFailureAfterBodyIsTakenDoesNotRetryOrFailOver() throws Exception {
        var client = mock(HttpClient.class);
        when(client.<String>send(any(), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer value) {}
                public void onError(Throwable error) {}
                public void onComplete() {}
            });
            throw new ConnectException("private-key");
        });
        var backup = mock(LyricsWriter.class); when(backup.isConfigured()).thenReturn(true);
        var failure = assertThrows(DomainException.class, () -> new FallbackLyricsWriter(primary(client), backup).write(WORK, "zh-CN"));
        assertEquals("LYRICS_PROVIDER_NETWORK_ERROR", failure.code());
        assertFalse(failure.getMessage().contains("private-key"));
        verify(client, times(1)).send(any(), any()); verify(backup, never()).write(any(), any());
    }

    @Test void unclassifiedIoFailureIsNotAssumedToBeSafeForFailover() throws Exception {
        var client = mock(HttpClient.class);
        when(client.<String>send(any(), any())).thenThrow(new IOException("private-key"));
        var backup = mock(LyricsWriter.class); when(backup.isConfigured()).thenReturn(true);
        var failure = assertThrows(DomainException.class, () -> new FallbackLyricsWriter(primary(client), backup).write(WORK, "zh-CN"));
        assertEquals("LYRICS_PROVIDER_NETWORK_ERROR", failure.code());
        verify(client, times(1)).send(any(), any()); verify(backup, never()).write(any(), any());
    }

    @Test void backupConnectionFailureStopsInsteadOfLoopingBetweenProviders() throws Exception {
        var first = mock(HttpClient.class); var second = mock(HttpClient.class);
        when(first.<String>send(any(), any())).thenThrow(new ConnectException());
        when(second.<String>send(any(), any())).thenThrow(new UnknownHostException());
        var backup = new QwenLyricsWriter("offline-key", "offline-model", URI.create("https://backup.invalid"), second);
        var failure = assertThrows(DomainException.class, () -> new FallbackLyricsWriter(primary(first), backup).write(WORK, "zh-CN"));
        assertEquals("LYRICS_PROVIDER_CONNECT_FAILED", failure.code());
        verify(first, times(2)).send(any(), any()); verify(second, times(2)).send(any(), any());
    }

    private static OpenAiLyricsWriter primary(HttpClient client) {
        return new OpenAiLyricsWriter("offline-key", "offline-model", URI.create("https://primary.invalid"), client);
    }
    @SuppressWarnings("unchecked") private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
        return response;
    }
    private static final class QwenFixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        QwenFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                Object song = Map.of("title", "小碗", "lyrics", LYRICS, "plan", Map.of("requirements", "",
                    "suggestedArrangement", "Acoustic guitar", "negativeTags", "", "summaryZh", "温暖民谣",
                    "summaryEn", "Warm folk", "vocalGender", "AUTO"));
                String content = calls.incrementAndGet() == 1 ? JSON.writeValueAsString(song) : LYRICS;
                byte[] response = JSON.writeValueAsBytes(Map.of("choices", List.of(Map.of("finish_reason", "stop",
                    "message", Map.of("content", content)))));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response); exchange.close();
            });
            server.start();
        }
        QwenLyricsWriter writer() {
            return new QwenLyricsWriter("offline-key", "offline-model", URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        }
        public void close() { server.stop(0); }
    }
}
