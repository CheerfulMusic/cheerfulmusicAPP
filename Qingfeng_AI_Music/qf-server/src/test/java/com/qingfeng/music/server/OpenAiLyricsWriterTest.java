package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiLyricsWriterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WorkApi.Work WORK = new WorkApi.Work("offline-work", "妈妈", "生日",
        "妈妈把剥好的橘子放在门口的小碗", "想让她少操心", "温暖民谣", "门口的小碗", 0, Instant.now(), Instant.now());
    private static Map<String, Object> result() {
        return new LinkedHashMap<>(Map.of("title", "门口的小碗", "lyrics", "[Verse 1]\n谢谢你陪着我\n[Chorus]\nYou are my home",
            "plan", Map.of("requirements", "", "suggestedArrangement", "Acoustic guitar", "negativeTags", "",
                "summaryZh", "按所选风格编配", "summaryEn", "Follow the selected style", "vocalGender", "AUTO")));
    }
    private static Map<String, Object> completed(Object result) {
        return Map.of("status", "completed", "output", List.of(
            Map.of("type", "reasoning", "summary", List.of()),
            Map.of("type", "message", "role", "assistant", "status", "completed", "content",
                List.of(Map.of("type", "output_text", "text", JSON.writeValueAsString(result))))));
    }

    @Test void preservesWritingContextAndUsesOfficialStructuredOutputContract() throws Exception {
        try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(result())))) {
            String previous = "[Verse 2]\n门口的小碗还在\nTake your time, my child";
            var draft = fixture.writer().write(WORK, "mixed", "保留第二段原文", previous, "轻快民谣 女声");
            var request = fixture.received.get();
            assertEquals(OpenAiLyricsWriter.DEFAULT_MODEL, request.path("model").asText());
            assertEquals("Bearer offline-key", fixture.authorization.get());
            assertEquals("/v1/responses", fixture.path.get());
            assertFalse(request.path("store").asBoolean());
            assertFalse(request.path("stream").asBoolean());
            assertEquals(6000, request.path("max_output_tokens").asInt());
            assertEquals("low", request.path("reasoning").path("effort").asText());
            assertFalse(request.has("thinking"));assertFalse(request.has("max_tokens"));
            String prompt = request.path("instructions").asText();
            assertTrue(prompt.contains("中英混合"));assertTrue(prompt.contains("该段应原样保留"));
            var material = JSON.readTree(request.path("input").path(0).path("content").asText());
            assertEquals(WORK.story(), material.path("story").asText());
            assertEquals(WORK.title(), material.path("preferredTitle").asText());
            assertEquals(previous, material.path("previousLyrics").asText());
            assertEquals("保留第二段原文", material.path("revisionInstructions").asText());
            assertEquals("轻快民谣 女声", material.path("musicDirection").asText());
            assertEquals(WORK.vocalGender(), material.path("vocalGender").asText());
            var format = request.path("text").path("format");
            assertEquals("json_schema", format.path("type").asText());
            assertTrue(format.path("strict").asBoolean());
            assertFalse(format.path("schema").path("additionalProperties").asBoolean(true));
            assertEquals(3, format.path("schema").path("required").size());
            assertEquals(6, format.path("schema").path("properties").path("plan").path("required").size());
            assertEquals(result().get("lyrics"), draft.lyrics());
            assertEquals("Acoustic guitar", draft.plan().suggestedArrangement());
            assertEquals(1, fixture.calls.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"incomplete", "failed", "queued", "in_progress"})
    void incompleteOrFailedResponsesNeverBecomeLyrics(String status) throws Exception {
        var response = new LinkedHashMap<>(completed(result()));response.put("status", status);
        assertInvalid(JSON.writeValueAsString(response));
    }

    @Test void rejectsRefusalsEvenOnCompletedResponse() throws Exception {
        assertInvalid(JSON.writeValueAsString(Map.of("status", "completed", "output", List.of(
            Map.of("type", "message", "role", "assistant", "status", "completed", "content",
                List.of(Map.of("type", "refusal", "refusal", "private-provider-detail")))))));
    }

    @ParameterizedTest @ValueSource(strings = {"title", "lyrics", "plan"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var invalid = result();invalid.remove(field);assertInvalid(JSON.writeValueAsString(completed(invalid)));
    }

    @Test void rejectsInvalidPlansBlankOrOversizedLyricsAndMalformedResponses() throws Exception {
        for (Object lyrics : List.of(" ", "字".repeat(5001), 123)) {
            var invalid = result();invalid.put("lyrics", lyrics);assertInvalid(JSON.writeValueAsString(completed(invalid)));
        }
        var invalid = result();invalid.put("plan", Map.of("vocalGender", "UNKNOWN"));
        assertInvalid(JSON.writeValueAsString(completed(invalid)));
        invalid.put("plan", Map.of("requirements", "", "suggestedArrangement", "", "negativeTags", "",
            "summaryZh", "", "summaryEn", "", "vocalGender", "UNKNOWN"));
        assertInvalid(JSON.writeValueAsString(completed(invalid)));
        assertInvalid("not-json");assertInvalid("{\"status\":\"completed\",\"output\":[]}");
        invalid=result();invalid.put("title","字".repeat(81));assertInvalid(JSON.writeValueAsString(completed(invalid)));
    }

    @Test void configuredModelReachesRequestAndAdapterReportsOpenAiSource() throws Exception {
        try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(result())))) {
            var writer=new OpenAiLyricsWriter("offline-key"," custom-model ",
                URI.create("http://127.0.0.1:"+fixture.server.getAddress().getPort()+"/v1/responses"),HttpClient.newHttpClient());
            writer.write(WORK,"en");
            assertEquals("custom-model",fixture.received.get().path("model").asText());
            assertTrue(fixture.received.get().path("instructions").asText().contains("英语"));
            assertEquals("OPENAI",writer.source());
        }
    }
    @Test void currentMusicExclusionsAndVoiceReachTheWritingRequest() throws Exception {
        try(var fixture=new Fixture(200,JSON.writeValueAsString(completed(result())))){
            fixture.writer().write(WORK,"zh-CN","","",new com.qingfeng.music.lyrics.application.LyricsWriter.Direction("rhythmic rap, fast tempo","no sad lyrics","FEMALE"));
            var material=JSON.readTree(fixture.received.get().path("input").path(0).path("content").asText());
            assertEquals("rhythmic rap, fast tempo",material.path("musicDirection").asText());
            assertEquals("no sad lyrics",material.path("musicExclusions").asText());
            assertEquals("FEMALE",material.path("vocalGender").asText());
        }
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 408, 429, 500, 503})
    void providerErrorsAreSafeAndNeverRetried(int status) throws Exception {
        try (var fixture = new Fixture(status, "private-provider-detail offline-key")) {
            var error = assertThrows(DomainException.class, () -> fixture.writer().write(WORK, "en"));
            String expected = switch (status) {
                case 401, 403, 404, 429 -> "LYRICS_PROVIDER_REJECTED";
                case 408, 500, 503 -> "LYRICS_PROVIDER_NETWORK_ERROR";
                default -> "LYRICS_PROVIDER_UNAVAILABLE";
            };
            assertEquals(expected, error.code());
            assertFalse(error.getMessage().contains("private"));assertFalse(error.getMessage().contains("offline-key"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void unconfiguredWriterMakesNoRequestAndAllowsExplicitModelSelection() {
        var client = mock(HttpClient.class);
        var writer = new OpenAiLyricsWriter(" ", " custom-model ", URI.create("https://example.invalid"), client);
        assertFalse(writer.isConfigured());
        assertEquals("LYRICS_PROVIDER_UNAVAILABLE", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
        verifyNoInteractions(client);
    }

    @Test void retriesOnceIfConnectionFailsBeforeTheRequestBodyIsUsed() throws Exception {
        var client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(JSON.writeValueAsString(completed(result())));
        when(client.<String>send(any(), any())).thenThrow(new ConnectException()).thenReturn(response);
        var writer = new OpenAiLyricsWriter("offline-key", "custom-model", URI.create("https://example.invalid"), client);

        assertEquals("门口的小碗", writer.write(WORK, "zh-CN").title());
        verify(client, times(2)).send(any(), any());
    }

    @Test void doesNotRetryAConnectionFailureAfterTheRequestBodyIsUsed() throws Exception {
        var client = mock(HttpClient.class);
        doAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.cancel(); }
                public void onNext(java.nio.ByteBuffer value) {}
                public void onError(Throwable error) {}
                public void onComplete() {}
            });
            throw new ConnectException();
        }).when(client).send(any(), any());
        var writer = new OpenAiLyricsWriter("offline-key", "custom-model", URI.create("https://example.invalid"), client);

        assertEquals("LYRICS_PROVIDER_NETWORK_ERROR", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
        verify(client, times(1)).send(any(), any());
    }

    @Test void timeoutNetworkAndInterruptKeepDistinctSafeErrors() throws Exception {
        var client = mock(HttpClient.class);
        var writer = new OpenAiLyricsWriter("offline-key", "custom-model", URI.create("https://example.invalid"), client);
        doThrow(new HttpTimeoutException("private transport detail")).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals("LYRICS_PROVIDER_TIMEOUT", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
        doThrow(new java.io.IOException("private network detail")).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals("LYRICS_PROVIDER_NETWORK_ERROR", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
        doThrow(new InterruptedException()).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            assertEquals("LYRICS_REQUEST_INTERRUPTED", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private static void assertInvalid(String body) throws Exception {
        try (var fixture = new Fixture(200, body)) {
            var error = assertThrows(DomainException.class, () -> fixture.writer().write(WORK, "zh-CN"));
            assertEquals("LYRICS_RESPONSE_INVALID", error.code());assertFalse(error.getMessage().contains("private"));
            assertEquals(1, fixture.calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> received = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>(), path = new AtomicReference<>();
        Fixture(int status, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", e -> {
                calls.incrementAndGet();authorization.set(e.getRequestHeaders().getFirst("Authorization"));
                path.set(e.getRequestURI().getPath());
                received.set(JSON.readTree(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(status, bytes.length);e.getResponseBody().write(bytes);e.close();
            });
            server.start();
        }
        OpenAiLyricsWriter writer() {
            return new OpenAiLyricsWriter("offline-key", OpenAiLyricsWriter.DEFAULT_MODEL,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses"), HttpClient.newHttpClient());
        }
        @Override public void close() { server.stop(0); }
    }
}
