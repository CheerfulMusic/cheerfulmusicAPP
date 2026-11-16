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
                List.of(Map.of("type", "output_text", "text", JSON.writeValueAsString(result))))));
    }

    private static Map<String, Object> reviewed(Map<String, Object> candidate, String decision,
                                                List<Map<String, Object>> issues) {
        var review = new LinkedHashMap<>(candidate);
        var lines = ((String) candidate.get("lyrics")).lines()
            .filter(line -> !line.isBlank() && !line.startsWith("[")).toList();
        String first = lines.get(0), last = lines.get(lines.size() - 1);
        review.put("quality", new LinkedHashMap<>(Map.of("decision", decision, "issues", issues,
            "hookQuote", last, "progressionQuote", first, "rhythmQuote", last,
            "reason", "已根据原始素材检查核心表达、段落推进及自然韵律。")));
        return review;
    }

    private static Map<String, Object> issue(String code, String quote) {
        return Map.of("code", code, "quote", quote, "reason", "副歌仍为通用表达，需要围绕原素材中的小碗和橘子修改。");
    }

    private static Map<String, Object> keep(Map<String, Object> candidate) {
        return reviewed(candidate, "KEEP", List.of());
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
            assertTrue(prompt.contains("中英混合"));assertTrue(prompt.contains("用户指定保留的段落必须原样保留"));
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

    @Test void photoLyricsUseOneMultimodalRequestAndRequireBothCreativeOutputs() throws Exception {
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
            assertEquals(1,fixture.calls.get());
        }
        try(var fixture=new Fixture(200,JSON.writeValueAsString(completed(result())))){
            var direction=new com.qingfeng.music.lyrics.application.LyricsWriter.Direction("","","AUTO");
            assertEquals("LYRICS_RESPONSE_INVALID",assertThrows(DomainException.class,
                ()->fixture.writer().writePhoto(WORK,"zh-CN",List.of(new byte[]{1}),direction)).code());
            assertEquals(1,fixture.calls.get());
        }
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
        @SuppressWarnings("unchecked") HttpResponse<String> editorial = mock(HttpResponse.class);
        when(editorial.statusCode()).thenReturn(200);
        when(editorial.body()).thenReturn(JSON.writeValueAsString(completed(keep(result()))));
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

    @Test void reviewUsesEditedLyricsButPreservesMusicPlan() throws Exception {
        var edited = result(); edited.put("lyrics", "[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
        edited.put("plan", Map.of("requirements", "changed", "suggestedArrangement", "changed", "negativeTags", "",
            "summaryZh", "", "summaryEn", "", "vocalGender", "FEMALE"));
        var draft = writeWithComparison(edited, input -> comparison(input, (String) edited.get("lyrics")));
        assertEquals(edited.get("lyrics"), draft.lyrics());
        assertEquals("Acoustic guitar", draft.plan().suggestedArrangement());
        assertEquals("AUTO", draft.plan().vocalGender());
    }

    @Test void invalidOrUnavailableReviewNeverClaimsThatTheInitialDraftPassedQuality() throws Exception {
        assertReviewUnverified(503, "private-details");
        assertReviewUnverified(200, "not-json");
        // A legacy complete-song response is not evidence that an editorial review happened.
        assertReviewUnverified(200, JSON.writeValueAsString(completed(result())));
        var changed = keep(result()); changed.put("title", "different");
        assertReviewUnverified(200, JSON.writeValueAsString(completed(changed)));
        changed = keep(result()); changed.put("lyrics", "[Verse 1]\n删掉了副歌");
        assertReviewUnverified(200, JSON.writeValueAsString(completed(changed)));
    }

    @Test void keepDecisionDeliversExactOriginalLyricsAndStopsAfterTheEditorialRequest() throws Exception {
        var draft = writeWithReview(200, JSON.writeValueAsString(completed(keep(result()))));
        assertEquals(result().get("title"), draft.title());
        assertEquals(result().get("lyrics"), draft.lyrics());
    }

    @Test void blindComparisonMayKeepTheOriginalInsteadOfAcceptingAnInferiorRewrite() throws Exception {
        var edited = rewrittenSong();
        var selected = writeWithComparison(edited, input -> comparison(input, (String) result().get("lyrics")));
        assertEquals(result().get("title"), selected.title());
        assertEquals(result().get("lyrics"), selected.lyrics());
        assertEquals("Acoustic guitar", selected.plan().suggestedArrangement());
        assertEquals("OPENAI", selected.source());
    }

    @Test void independentRejectionOfBothCandidatesDoesNotPublishEitherDraft() throws Exception {
        var error = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(),
            input -> comparisonWithoutWinner(input, "NEITHER")));
        assertEquals("LYRICS_QUALITY_NOT_MET", error.code());
        assertFalse(error.getMessage().contains("You are my home"));
    }

    @Test void blindComparisonRequiresLiteralEvidenceAndConsistentJudgment() throws Exception {
        for (String field : List.of("hookQuote", "progressionQuote", "rhythmQuote")) {
            var error = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(), input -> {
                var verdict = comparison(input, (String) rewrittenSong().get("lyrics"));
                verdict.put(field, "这句在两稿中都不存在");
                return verdict;
            }));
            assertEquals("LYRICS_QUALITY_UNVERIFIED", error.code());
        }
        for (String side : List.of("a", "b")) {
            var error = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(), input -> {
                var verdict = comparison(input, (String) rewrittenSong().get("lyrics"));
                @SuppressWarnings("unchecked") var judgment = (Map<String, Object>) verdict.get(side);
                judgment.put("quote", "伪造的候选引文");
                return verdict;
            }));
            assertEquals("LYRICS_QUALITY_UNVERIFIED", error.code());
        }
        var selectedUnacceptable = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(), input -> {
            var verdict = comparison(input, (String) rewrittenSong().get("lyrics"));
            @SuppressWarnings("unchecked") var winner = (Map<String, Object>) verdict.get(((String) verdict.get("choice")).toLowerCase(Locale.ROOT));
            winner.put("acceptable", false);
            return verdict;
        }));
        assertEquals("LYRICS_QUALITY_UNVERIFIED", selectedUnacceptable.code());
        var uncertain = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(),
            input -> comparisonWithoutWinner(input, "UNSURE")));
        assertEquals("LYRICS_QUALITY_UNVERIFIED", uncertain.code());
        var contradictoryNeither = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(), input -> {
            var verdict = comparisonWithoutWinner(input, "NEITHER");
            @SuppressWarnings("unchecked") var first = (Map<String, Object>) verdict.get("a");
            first.put("acceptable", true);
            return verdict;
        }));
        assertEquals("LYRICS_QUALITY_UNVERIFIED", contradictoryNeither.code());
    }

    @Test void unavailableOrMalformedComparisonNeverFallsBackToUnreviewedLyrics() throws Exception {
        assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class,
            () -> writeWithComparison(rewrittenSong(), 503, input -> "private details", null)).code());
        assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class,
            () -> writeWithComparison(rewrittenSong(), 200, input -> "not-json", null)).code());
        assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class,
            () -> writeWithComparison(rewrittenSong(), input -> {
                var verdict = comparison(input, (String) rewrittenSong().get("lyrics"));
                verdict.remove("b");
                return verdict;
            })).code());
    }

    @Test void comparisonTransportFailuresRemainUnknownWithoutAFourthCallOrQwenFallback() throws Exception {
        for (Exception failure : List.of(new HttpTimeoutException("private timeout"),
                new java.io.IOException("private network detail"), new InterruptedException("private interrupt"))) {
            try {
                var error = assertThrows(DomainException.class, () -> writeWithComparison(rewrittenSong(), 200,
                    input -> comparison(input, (String) rewrittenSong().get("lyrics")), failure));
                assertEquals(failure instanceof HttpTimeoutException ? "LYRICS_PROVIDER_TIMEOUT"
                    : failure instanceof InterruptedException ? "LYRICS_REQUEST_INTERRUPTED" : "LYRICS_PROVIDER_NETWORK_ERROR", error.code());
                assertFalse(error.getMessage().contains("private"));
                assertEquals(failure instanceof InterruptedException, Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
        assertEquals("LYRICS_PROVIDER_TIMEOUT", assertThrows(DomainException.class,
            () -> writeWithComparison(rewrittenSong(), 200, input -> comparison(input, (String) rewrittenSong().get("lyrics")),
                new DomainException("AGENT_DEADLINE_EXCEEDED", "private deadline"))).code());
    }

    @Test void insufficientBudgetNeverDispatchesAnEditorialOrComparisonRequest() throws Exception {
        var client = mock(HttpClient.class);
        try (var scope = new AgentRequestScope(Duration.ofSeconds(10), 65536).enter()) {
            var generated = response(200, completed(result()));
            when(client.<String>send(any(), any())).thenReturn(generated);
            var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
            assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
            verify(client, times(1)).send(any(), any());
        }
        var afterReviewClient = mock(HttpClient.class);
        var calls = new AtomicInteger();
        try (var scope = new AgentRequestScope(Duration.ofSeconds(60), 65536).enter()) {
            when(afterReviewClient.<String>send(any(), any())).thenAnswer(call -> {
                if (calls.incrementAndGet() == 1) return response(200, completed(result()));
                scope.cancel();
                return response(200, completed(reviewed(rewrittenSong(), "REWRITE", List.of(issue("HOOK", "You are my home")))));
            });
            var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), afterReviewClient);
            assertEquals("LYRICS_QUALITY_UNVERIFIED", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
            verify(afterReviewClient, times(2)).send(any(), any());
        }
    }

    @Test void keepCannotHideAnUnrequestedRewriteOrContradictoryIssues() throws Exception {
        var changed = keep(result()); changed.put("lyrics", "[Verse 1]\n换掉了原稿\n[Chorus]\nYou are my home");
        assertReviewUnverified(200, JSON.writeValueAsString(completed(changed)));
        assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), "KEEP",
            List.of(issue("HOOK", "You are my home"))))));
    }

    @Test void clearRejectionRequiresRealEvidenceAndDoesNotStartAThirdGeneration() throws Exception {
        var rejected = reviewed(result(), "REJECT", List.of(issue("HOOK", "You are my home")));
        var error = assertThrows(DomainException.class,
            () -> writeWithReview(200, JSON.writeValueAsString(completed(rejected))));
        assertEquals("LYRICS_QUALITY_NOT_MET", error.code());
        assertFalse(error.getMessage().contains("You are my home"));
    }

    @Test void uncertainOrUnsupportedDecisionNeverBecomesSuccessfulLyrics() throws Exception {
        for (String decision : List.of("UNSURE", "EXCELLENT", "rewrite"))
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), decision, List.of()))));
        assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), "REJECT", List.of()))));
    }

    @Test void inventedProblemQuotesCannotTriggerARewriteOrRejectTheUserRequest() throws Exception {
        for (String decision : List.of("REWRITE", "REJECT"))
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), decision,
                List.of(issue("HOOK", "原稿里根本不存在的句子"))))));
        assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), "REJECT",
            List.of(issue("MADE_UP_SCORE", "You are my home"))))));
        for (String decision : List.of("REWRITE", "REJECT"))
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), decision,
                List.of(issue("HOOK", ""))))));
    }

    @Test void rewrittenLyricsNeedActualChangeAndAnchoredPositiveEvidence() throws Exception {
        assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed(result(), "REWRITE",
            List.of(issue("HOOK", "You are my home"))))));
        var edited = result(); edited.put("lyrics", "[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
        var withoutIssues = reviewed(edited, "REWRITE", List.of());
        assertReviewUnverified(200, JSON.writeValueAsString(completed(withoutIssues)));
        for (String field : List.of("hookQuote", "progressionQuote", "rhythmQuote")) {
            var reviewed = reviewed(edited, "REWRITE", List.of(issue("HOOK", "You are my home")));
            @SuppressWarnings("unchecked") var quality = (Map<String, Object>) reviewed.get("quality");
            quality.put(field, "这句在改稿中不存在");
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed)));
            quality.put(field, "");
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed)));
            quality.put(field, "[Chorus]");
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed)));
        }
    }

    @Test void missingEditorialFieldsCannotBeInterpretedAsApproval() throws Exception {
        for (String field : List.of("decision", "issues", "hookQuote", "progressionQuote", "rhythmQuote", "reason")) {
            var reviewed = keep(result());
            @SuppressWarnings("unchecked") var quality = (Map<String, Object>) reviewed.get("quality");
            quality.remove(field);
            assertReviewUnverified(200, JSON.writeValueAsString(completed(reviewed)));
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
            when(response.body()).thenReturn(JSON.writeValueAsString(completed(sent.size() == 1 ? result() : keep(result()))));
            return response;
        });
        var writer = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        assertEquals(result().get("lyrics"), writer.write(WORK, "zh-CN").lyrics());
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).timeout().orElseThrow().compareTo(Duration.ofSeconds(75)) <= 0);
        assertTrue(sent.get(1).timeout().orElseThrow().compareTo(Duration.ofSeconds(110)) <= 0);
        assertTrue(sent.stream().allMatch(request -> !request.timeout().orElseThrow().isZero() && !request.timeout().orElseThrow().isNegative()));
    }

    @Test void editorialUsesLowReasoningWithinSharedDeadlineAndReceivesOriginalFacts() throws Exception {
        var client = mock(HttpClient.class);
        var sent = new ArrayList<HttpRequest>();
        when(client.<String>send(any(), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); sent.add(request);
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(JSON.writeValueAsString(completed(sent.size() == 1 ? result() : keep(result()))));
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
        assertEquals("low", second.path("reasoning").path("effort").asText());
        var input = JSON.readTree(second.path("input").path(0).path("content").asText());
        assertEquals(WORK.story(), input.path("originalMaterial").path("story").asText());
        assertEquals(result().get("lyrics"), input.path("candidate").path("lyrics").asText());
        assertTrue(second.path("text").path("format").path("schema").path("properties").has("quality"));
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

    private static Map<String, Object> rewrittenSong() {
        var edited = result();
        edited.put("lyrics", "[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
        return edited;
    }

    private static Map<String, Object> comparison(JsonNode input, String selectedLyrics) {
        var candidates = input.path("candidates");
        String a = candidates.path("A").path("lyrics").asText(), b = candidates.path("B").path("lyrics").asText();
        var positive = selectedLyrics.lines().filter(line -> !line.isBlank() && !line.startsWith("[")).toList();
        return new LinkedHashMap<>(Map.of("choice", a.equals(selectedLyrics) ? "A" : "B",
            "a", side(a, true), "b", side(b, true),
            "hookQuote", positive.get(positive.size() - 1), "progressionQuote", positive.get(0),
            "rhythmQuote", positive.get(positive.size() - 1), "reason", "根据原素材独立比较两稿，选择具体表达更成立的一稿。"));
    }

    private static Map<String, Object> comparisonWithoutWinner(JsonNode input, String choice) {
        return new LinkedHashMap<>(Map.of("choice", choice,
            "a", side(input.path("candidates").path("A").path("lyrics").asText(), false),
            "b", side(input.path("candidates").path("B").path("lyrics").asText(), false),
            "hookQuote", "", "progressionQuote", "", "rhythmQuote", "", "reason", "两稿尚未形成足够有辨识度的表达。"));
    }

    private static Map<String, Object> side(String lyrics, boolean acceptable) {
        String quote = lyrics.lines().filter(line -> !line.isBlank() && !line.startsWith("[")).findFirst().orElseThrow();
        return new LinkedHashMap<>(Map.of("acceptable", acceptable, "quote", quote, "reason", "结合素材核对该句的观察与表达价值。"));
    }

    private static HttpResponse<String> response(int status, Object body) {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body instanceof String ? (String) body : JSON.writeValueAsString(body));
        return response;
    }

    private LyricsWriter.Draft writeWithComparison(Map<String, Object> edited,
            java.util.function.Function<JsonNode, Object> verdict) throws Exception {
        return writeWithComparison(edited, 200, verdict, null);
    }

    private LyricsWriter.Draft writeWithComparison(Map<String, Object> edited, int status,
            java.util.function.Function<JsonNode, Object> verdict, Exception failure) throws Exception {
        var client = mock(HttpClient.class);
        var sent = new ArrayList<HttpRequest>();
        when(client.<String>send(any(), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0); sent.add(request);
            if (sent.size() == 1) return response(200, completed(result()));
            if (sent.size() == 2) return response(200, completed(reviewed(edited, "REWRITE", List.of(issue("HOOK", "You are my home")))));
            assertEquals(3, sent.size(), "No fourth quality generation or retry is allowed after a dispatched comparison");
            if (failure != null) throw failure;
            var input = JSON.readTree(JSON.readTree(readRequestBody(request)).path("input").path(0).path("content").asText());
            Object selected = verdict.apply(input);
            return response(status, selected instanceof String ? selected : completed(selected));
        });
        var primary = new OpenAiLyricsWriter("offline-key", "gpt-6.1-sol", URI.create("https://example.invalid"), client);
        var backup = mock(LyricsWriter.class); when(backup.isConfigured()).thenReturn(true);
        try { return new FallbackLyricsWriter(primary, backup).write(WORK, "zh-CN"); }
        finally {
            verify(client, times(3)).send(any(), any());
            verify(backup, never()).write(any(), anyString());
            var request = JSON.readTree(readRequestBody(sent.get(2)));
            var input = JSON.readTree(request.path("input").path(0).path("content").asText());
            assertEquals(Set.of("originalMaterial", "candidates"), keys(input));
            assertEquals(Set.of("A", "B"), keys(input.path("candidates")));
            for (String label : List.of("A", "B")) {
                var candidate = input.path("candidates").path(label);
                assertEquals(Set.of("title", "lyrics"), keys(candidate), "Blind comparison must not leak quality, plan, or original/edited attribution");
                assertEquals(result().get("title"), candidate.path("title").asText());
            }
            assertEquals(WORK.story(), input.path("originalMaterial").path("story").asText());
            assertEquals("low", request.path("reasoning").path("effort").asText());
            assertEquals(4000, request.path("max_output_tokens").asInt());
            assertTrue(request.path("text").path("format").path("schema").path("properties").has("choice"));
            assertTrue(sent.get(0).timeout().orElseThrow().compareTo(Duration.ofSeconds(75)) <= 0);
            assertTrue(sent.get(1).timeout().orElseThrow().compareTo(Duration.ofSeconds(90)) < 0,
                "Editor must reserve comparison time from the same total deadline");
            assertTrue(sent.get(2).timeout().orElseThrow().compareTo(Duration.ofSeconds(110)) <= 0);
        }
    }

    private static Set<String> keys(JsonNode node) {
        var names = new HashSet<String>(); node.propertyNames().forEach(names::add); return names;
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
        try (var fixture = new Fixture(200, body)) {
            var error = assertThrows(DomainException.class, () -> fixture.writer().write(WORK, "zh-CN"));
            assertEquals("LYRICS_RESPONSE_INVALID", error.code());assertFalse(error.getMessage().contains("private"));
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
                            response = JSON.writeValueAsString(completed(keep(candidate)));
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
