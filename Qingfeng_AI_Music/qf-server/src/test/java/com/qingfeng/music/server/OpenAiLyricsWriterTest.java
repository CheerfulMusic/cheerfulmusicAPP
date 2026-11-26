package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
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
                List.of(Map.of("type", "output_text", "text", (result instanceof String ? (String) result : JSON.writeValueAsString(result)))))));
    }

    @Test void preservesWritingContextAndUsesOfficialStructuredOutputContract() throws Exception {
        try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(result())))) {
            String previous = "[Verse 2]\n门口的小碗还在\nTake your time, my child";
            var draft = fixture.writer().write(WORK, "mixed", "保留第二段原文", previous, "轻快民谣 女声");
            var request = fixture.received.get();
            assertEquals("gpt-6.1-sol", request.path("model").asText());
            assertEquals("Bearer offline-key", fixture.authorization.get());
            assertEquals("/v1/responses", fixture.path.get());
            assertFalse(request.path("store").asBoolean());
            assertFalse(request.path("stream").asBoolean());
            assertEquals(8000, request.path("max_output_tokens").asInt());
            assertEquals("medium", request.path("reasoning").path("effort").asText());
            assertFalse(request.has("thinking"));assertFalse(request.has("max_tokens"));
            String prompt = request.path("instructions").asText();
            assertTrue(prompt.contains("中英混合"));assertTrue(prompt.contains("明确保留的原句优先遵守"));
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

    @Test void preservesLongNarrativeAndSelectedStructureWithoutCompressingMaterial() throws Exception {
        String story = "五年级开始看比赛，现在二十五岁。昨天总决赛结束了，遗憾，但我还想继续看。";
        var work = new WorkApi.Work("narrative", "自己", "比赛之后", story, "不编造比赛结果", "抒情摇滚", "", 0, Instant.now(), Instant.now());
        String structure = new com.qingfeng.music.lyrics.application.LyricsStructure("ABCD", false).instruction();
        var output = result();
        String lyrics = "[Verse 1]\n" + "屏幕亮着，我还记得那年夏天\n".repeat(45) + "[Chorus]\n下一波，我还在";
        output.put("lyrics", lyrics);
        try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(output)))) {
            var draft = fixture.writer().write(work, "zh-CN", structure, "");
            var request = fixture.received.get();
            var material = JSON.readTree(request.path("input").path(0).path("content").asText());
            assertEquals(story, material.path("story").asText());
            assertEquals(structure, material.path("revisionInstructions").asText());
            assertEquals(lyrics, draft.lyrics());
            assertEquals(2, fixture.calls.get());
            var review = JSON.readTree(fixture.lastReceived.get().path("input").path(0).path("content").asText());
            assertEquals(story, review.path("originalMaterial").path("story").asText());
            assertEquals(structure, review.path("originalMaterial").path("revisionInstructions").asText());
            assertEquals(lyrics, review.path("candidate").path("lyrics").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"incomplete", "failed", "queued", "in_progress"})
    void incompleteOrFailedResponsesNeverBecomeLyrics(String status) throws Exception {
        var response = new LinkedHashMap<>(completed(result()));response.put("status", status);
        assertInvalid(JSON.writeValueAsString(response));
    }

    @Test void photoLyricsUseMultimodalDraftThenRefinementAndPreservePhotoSummary() throws Exception {
        var output=result();
        output.put("photoSummary","夕阳下，两个人坐在海边。");
        try(var fixture=new Fixture(200,JSON.writeValueAsString(completed(output)))){
            byte[] jpeg={-1,-40,-1,-39};
            byte[] second={-1,-40,2,-39};
            var direction=new com.qingfeng.music.lyrics.application.LyricsWriter.Direction("","","AUTO");
            var draft=fixture.writer().writePhoto(WORK,"zh-CN",List.of(jpeg,second),direction);
            var request=fixture.received.get();
            var parts=request.path("input").path(0).path("content");
            assertEquals("input_text",parts.path(0).path("type").asText());
            var material=JSON.readTree(parts.path(0).path("text").asText());
            assertEquals(WORK.story(),material.path("story").asText());
            assertEquals("",material.path("preferredTitle").asText());
            assertEquals("input_image",parts.path(1).path("type").asText());
            assertEquals("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(jpeg),parts.path(1).path("image_url").asText());
            assertEquals("input_image",parts.path(2).path("type").asText());
            assertEquals("data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(second),parts.path(2).path("image_url").asText());
            assertEquals(3,parts.size());
            assertTrue(request.path("text").path("format").path("schema").path("properties").has("photoSummary"));
            assertEquals(output.get("photoSummary"),draft.plan().photoSummary());
            assertEquals("Acoustic guitar",draft.plan().suggestedArrangement());
            assertEquals("OPENAI",draft.source());
            assertEquals(2,fixture.calls.get());
        }
        try(var fixture=new Fixture(200,JSON.writeValueAsString(completed(result())))){
            var direction=new com.qingfeng.music.lyrics.application.LyricsWriter.Direction("","","AUTO");
            assertEquals("LYRICS_RESPONSE_INVALID",assertThrows(DomainException.class,
                ()->fixture.writer().writePhoto(WORK,"zh-CN",List.of(new byte[]{1}),direction)).code());
            assertEquals(1,fixture.calls.get());
        }
    }

    @Test void rejectsRefusalsEvenOnCompletedResponse() throws Exception {
        var refusal = Map.of("type", "refusal", "refusal", "private-provider-detail");
        var message = Map.of("type", "message", "role", "assistant", "status", "completed", "content", List.of(refusal));
        String response = JSON.writeValueAsString(Map.of("status", "completed", "output", List.of(message)));
        try (var fixture = new Fixture(200, response)) {
            var backup = mock(LyricsWriter.class);
            when(backup.isConfigured()).thenReturn(true);
            var error = assertThrows(DomainException.class,
                () -> new FallbackLyricsWriter(fixture.writer(), backup).write(WORK, "zh-CN"));
            assertEquals("LYRICS_CONTENT_REJECTED", error.code());
            assertFalse(error.getMessage().contains("private-provider-detail"));
            verify(backup, never()).write(any(), anyString());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"title", "lyrics"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var invalid = result();invalid.remove(field);assertInvalid(JSON.writeValueAsString(completed(invalid)));
    }

    @Test void keepsCompleteLyricsWhenAuxiliaryPlanIsMissingOrInvalid() throws Exception {
        var noPlan = result();
        noPlan.remove("plan");
        var partialPlan = result();
        partialPlan.put("plan", Map.of("vocalGender", "UNKNOWN", "suggestedArrangement", 42,
            "requirements", "写成民谣", "summaryZh", "字".repeat(201)));
        for (var candidate : List.of(noPlan, partialPlan)) {
            try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(candidate)))) {
                var draft = fixture.writer().write(WORK, "ja", "", "",
                    new LyricsWriter.Direction("", "", "FEMALE"));
                assertEquals(candidate.get("lyrics"), draft.lyrics());
                assertEquals("", draft.plan().suggestedArrangement());
                assertEquals("", draft.plan().summaryZh());
                assertEquals("FEMALE", draft.plan().vocalGender());
                assertEquals(candidate == noPlan ? "" : "写成民谣", draft.plan().requirements());
                assertEquals(2, fixture.calls.get());
            }
        }
    }

    @Test void rejectsBlankOrOversizedLyricsAndDistinguishesMalformedResponses() throws Exception {
        for (Object lyrics : List.of(" ", "字".repeat(5001), 123)) {
            var invalid = result();invalid.put("lyrics", lyrics);assertInvalid(JSON.writeValueAsString(completed(invalid)));
        }
        assertInvalid("not-json", "LYRICS_RESPONSE_FORMAT");
        assertInvalid(JSON.writeValueAsString(completed("not-json")), "LYRICS_RESPONSE_FORMAT");
        assertInvalid("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}", "LYRICS_RESPONSE_TRUNCATED");
        assertInvalid("{\"status\":\"completed\",\"output\":[]}");
        var invalid=result();invalid.put("title","字".repeat(81));assertInvalid(JSON.writeValueAsString(completed(invalid)));
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
        @SuppressWarnings("unchecked") HttpResponse<String> editorial = mock(HttpResponse.class);
        when(editorial.statusCode()).thenReturn(200);
        when(editorial.body()).thenReturn(JSON.writeValueAsString(completed(result().get("lyrics"))));
        when(client.<String>send(any(), any())).thenThrow(new ConnectException()).thenReturn(response, editorial);
        var writer = new OpenAiLyricsWriter("offline-key", "custom-model", URI.create("https://example.invalid"), client);

        assertEquals("门口的小碗", writer.write(WORK, "zh-CN").title());
        verify(client, times(3)).send(any(), any());
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

    @Test void refinementPreservesTitleAndPlan() throws Exception {
        String lyrics="[Verse]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己";
        var draft=writeWithReview(200,JSON.writeValueAsString(completed(lyrics)));
        assertEquals(lyrics,draft.lyrics());assertEquals("门口的小碗",draft.title());
        assertEquals("Acoustic guitar",draft.plan().suggestedArrangement());assertEquals("AUTO",draft.plan().vocalGender());
    }
    @Test void invalidRefinementNeverReturnsInitialDraft() throws Exception {
        for(String text:List.of(" ","字".repeat(5001),"{}","```lyrics\n正文", "{\"lyrics\":\"正文\"} {\"lyrics\":\"续文\"}"))
            assertReviewUnverified(200,JSON.writeValueAsString(completed(text)));
        for(int status:List.of(401,429,500,503))assertReviewUnverified(status,"private-provider-detail");
    }

    @Test void sectionRepairUsesOneBoundedStructuredRequestForMissingPositions() throws Exception {
        try (var fixture = new Fixture(200, JSON.writeValueAsString(completed(Map.of("2", "Missing bridge lyrics"))))) {
            var repair = new LyricsWriter.SectionRepair(WORK, "en", "Keep the story", "",
                new LyricsWriter.Draft("Original title", "[Verse]\nKeep these lyrics"), Map.of(2, "Bridge"));
            assertEquals(Map.of(2, "Missing bridge lyrics"), fixture.writer().repairSections(repair));
            var sent = fixture.received.get();
            var format = sent.path("text").path("format");
            assertTrue(format.path("strict").asBoolean());
            assertEquals(JSON.readTree("[\"2\"]"), format.path("schema").path("required"));
            var material = JSON.readTree(sent.path("input").path(0).path("content").asText());
            assertEquals(JSON.readTree("{\"2\":\"Bridge\"}"), material.path("missingSections"));
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void eachEditorialNetworkFailurePreservesUnknownOutcomeWithoutAnotherCall() throws Exception {
        for (Exception failure : List.of(new HttpTimeoutException("private transport detail"),
                new java.io.IOException("private network detail"), new InterruptedException("private interrupt"))) {
            var client = mock(HttpClient.class);
            @SuppressWarnings("unchecked") HttpResponse<String> first = mock(HttpResponse.class);
            when(first.statusCode()).thenReturn(200); when(first.body()).thenReturn(JSON.writeValueAsString(completed(result())));
            when(client.<String>send(any(), any())).thenReturn(first).thenThrow(failure);
            var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
            try {
                var error = assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN"));
                assertEquals(failure instanceof HttpTimeoutException ? "LYRICS_PROVIDER_TIMEOUT"
                    : failure instanceof InterruptedException ? "LYRICS_REQUEST_INTERRUPTED" : "LYRICS_PROVIDER_NETWORK_ERROR", error.code());
                assertFalse(error.getMessage().contains("private"));
                assertEquals(failure instanceof InterruptedException, Thread.currentThread().isInterrupted());
                verify(client, times(2)).send(any(), any());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void editorialConnectionFailureCannotMasqueradeAsRejectedGenerationAndStartQwen() throws Exception {
        var client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> first = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(first.body()).thenReturn(JSON.writeValueAsString(completed(result())));
        when(client.<String>send(any(), any())).thenReturn(first).thenThrow(new ConnectException());
        var primary = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        var backup = mock(LyricsWriter.class); when(backup.isConfigured()).thenReturn(true);
        var error = assertThrows(DomainException.class, () -> new FallbackLyricsWriter(primary, backup).write(WORK, "zh-CN"));
        assertEquals("LYRICS_QUALITY_UNVERIFIED", error.code());
        // One real generation, then at most two safe connection attempts before the editor body is used.
        verify(client, times(3)).send(any(), any());
        verify(backup, never()).write(any(), anyString());
    }

    @Test void defaultGenerationAndEditorialTimeoutsHaveFiniteStageAndOverallLimits() throws Exception {
        var client = mock(HttpClient.class);
        var sent = new ArrayList<HttpRequest>();
        when(client.<String>send(any(), any())).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(JSON.writeValueAsString(completed(sent.size() == 1 ? result() : result().get("lyrics"))));
            return response;
        });
        var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        assertEquals(result().get("lyrics"), writer.write(WORK, "zh-CN").lyrics());
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).timeout().orElseThrow().compareTo(Duration.ofSeconds(75)) <= 0);
        assertTrue(sent.get(1).timeout().orElseThrow().compareTo(Duration.ofSeconds(110)) <= 0);
        assertTrue(sent.stream().allMatch(request -> !request.timeout().orElseThrow().isZero() && !request.timeout().orElseThrow().isNegative()));
    }

    @Test void refinementUsesMediumReasoningWithinSharedDeadlineAndReceivesOriginalFacts() throws Exception {
        var client = mock(HttpClient.class);
        var sent = new ArrayList<HttpRequest>();
        when(client.<String>send(any(), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); sent.add(request);
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(JSON.writeValueAsString(completed(sent.size() == 1 ? result() : result().get("lyrics"))));
            return response;
        });
        var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        try (var scope = new AgentRequestScope(Duration.ofSeconds(40), 65536).enter()) {
            assertEquals(result().get("lyrics"), writer.write(WORK, "zh-CN").lyrics());
        }
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).timeout().orElseThrow().compareTo(Duration.ofSeconds(40)) <= 0);
        assertTrue(sent.get(1).timeout().orElseThrow().compareTo(sent.get(0).timeout().orElseThrow()) < 0,
            "Editorial timeout must consume the same request deadline, never reset it");
        var first = JSON.readTree(readRequestBody(sent.get(0)));
        var second = JSON.readTree(readRequestBody(sent.get(1)));
        assertEquals("medium", first.path("reasoning").path("effort").asText());
        assertEquals("medium", second.path("reasoning").path("effort").asText());
        var input = JSON.readTree(second.path("input").path(0).path("content").asText());
        assertEquals(WORK.story(), input.path("originalMaterial").path("story").asText());
        assertEquals(result().get("lyrics"), input.path("candidate").path("lyrics").asText());
        assertEquals("text",second.path("text").path("format").path("type").asText());
        assertTrue(second.path("instructions").asText().contains("深度二次创作"));
    }

    @Test void agentCancellationBeforeEditorialDispatchIsDeterministicallyUndelivered() throws Exception {
        var client = mock(HttpClient.class);
        try (var scope = new AgentRequestScope(Duration.ofSeconds(5), 65536).enter()) {
            when(client.<String>send(any(), any())).thenAnswer(call -> {
                scope.cancel();
                @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
                when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(JSON.writeValueAsString(completed(result())));
                return response;
            });
            var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
            assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
            verify(client, times(1)).send(any(), any());
        }
    }

    private static String readRequestBody(HttpRequest request) {
        var body = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer value) { byte[] data = new byte[value.remaining()]; value.get(data); body.writeBytes(data); }
            public void onError(Throwable error) { throw new AssertionError(error); }
            public void onComplete() {}
        });
        return body.toString(StandardCharsets.UTF_8);
    }

    private void assertReviewUnverified(int status, String body) throws Exception {
        var error = assertThrows(DomainException.class, () -> writeWithReview(status, body));
        assertEquals("LYRICS_QUALITY_UNVERIFIED", error.code());
        assertFalse(error.getMessage().contains("private"));
        assertFalse(error.getMessage().contains("offline-key"));
    }

    private com.qingfeng.music.lyrics.application.LyricsWriter.Draft writeWithReview(int status, String body) throws Exception {
        var client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> first = mock(HttpResponse.class);
        @SuppressWarnings("unchecked") HttpResponse<String> second = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(first.body()).thenReturn(JSON.writeValueAsString(completed(result())));
        when(second.statusCode()).thenReturn(status); when(second.body()).thenReturn(body);
        when(client.<String>send(any(), any())).thenReturn(first, second);
        var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        try { return writer.write(WORK, "zh-CN"); }
        finally { verify(client, times(2)).send(any(), any()); }
    }

    private static void assertInvalid(String body) throws Exception {
        assertInvalid(body, "LYRICS_RESPONSE_INVALID");
    }

    private static void assertInvalid(String body, String code) throws Exception {
        try (var fixture = new Fixture(200, body)) {
            var error = assertThrows(DomainException.class, () -> fixture.writer().write(WORK, "zh-CN"));
            assertEquals(code, error.code());assertFalse(error.getMessage().contains("private"));
            assertEquals(1, fixture.calls.get());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> received = new AtomicReference<>(), lastReceived = new AtomicReference<>();
        final AtomicReference<String> authorization = new AtomicReference<>(), path = new AtomicReference<>();
        Fixture(int status, String body) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", e -> {
                calls.incrementAndGet();authorization.set(e.getRequestHeaders().getFirst("Authorization"));
                path.set(e.getRequestURI().getPath());
                lastReceived.set(JSON.readTree(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                received.compareAndSet(null, lastReceived.get());
                String response = body;
                if (calls.get() == 2 && status == 200) {
                    var completed = JSON.readTree(body);
                    for (var item : completed.path("output")) {
                        if (!"message".equals(item.path("type").asText())) continue;
                        for (var part : item.path("content")) {
                            if (!"output_text".equals(part.path("type").asText())) continue;
                            @SuppressWarnings("unchecked") var candidate = (Map<String, Object>) JSON.readValue(part.path("text").asText(), Map.class);
                            response = JSON.writeValueAsString(completed(candidate.get("lyrics")));
                        }
                    }
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
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
