package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.AgentApplicationActions.Capability;
import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static com.qingfeng.music.server.config.ModelFallbackTestServer.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelTimeoutFallbackTest {
    private static final WorkApi.Work WORK = new WorkApi.Work("offline-work", "妈妈", "生日", "门口的小碗",
        "想让她少操心", "民谣", "", 0, Instant.now(), Instant.now());
    private static final String QWEN_MODEL = "qwen3.8-flash";
    private static final String ARK_MODEL = "ep-offline-text";
    private ModelFallbackTestServer server;
    private MockEnvironment env;

    @BeforeEach void setUp() throws Exception {
        server = new ModelFallbackTestServer();
        env = new MockEnvironment()
            .withProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key")
            .withProperty("qingfeng.providers.qwen.base-url", server.uri("/qwen").toString())
            .withProperty("qingfeng.providers.qwen.model", QWEN_MODEL)
            .withProperty("qingfeng.providers.ark.qwen-timeout-fallback-enabled", "true")
            .withProperty("ARK_API_KEY", "offline-ark-key")
            .withProperty("qingfeng.providers.ark.text-base-url", server.uri("/ark").toString())
            .withProperty("qingfeng.providers.ark.text-model", ARK_MODEL)
            .withProperty("qingfeng.lyrics.provider", "qwen")
            .withProperty("qingfeng.lyrics.qwen-timeout-ms", "5000")
            .withProperty("qingfeng.agent.intent.llm-enabled", "true")
            .withProperty("qingfeng.agent.chat.qwen-timeout-ms", "5000")
            .withProperty("qingfeng.agent.chat.model-total-timeout-ms", "12000");
    }

    @AfterEach void tearDown() { server.close(); }

    @Test void successfulGptKeepsBothBackupsIdle() {
        server.reply(GPT, 200, gpt(SONG));
        server.reply(GPT, 200, gpt(LYRICS));
        var draft = gptFirst().write(WORK, "zh-CN");
        assertEquals("OPENAI", draft.source());
        assertEquals(LYRICS, draft.lyrics());
        assertCount(GPT, 2);
        assertCount(QWEN_LYRICS, 0);
        assertCount(ARK, 0);
    }

    @Test void aGptTimeoutDoesNotJumpToEitherBackup() {
        server.reply(GPT, 504, Map.of());
        var error = assertThrows(DomainException.class, () -> gptFirst().write(WORK, "zh-CN"));
        assertEquals("LYRICS_PROVIDER_NETWORK_ERROR", error.code());
        assertCount(GPT, 1);
        assertCount(QWEN_LYRICS, 0);
        assertCount(ARK, 0);
    }

    @Test void aConfirmedGptRejectionStillUsesAHealthyQwenWithItsRefinement() {
        server.reply(GPT, 429, Map.of());
        server.reply(QWEN_LYRICS, 200, chat(SONG));
        server.reply(QWEN_LYRICS, 200, chat(LYRICS));
        var draft = gptFirst().write(WORK, "zh-CN");
        assertEquals("QWEN", draft.source());
        assertEquals(LYRICS, draft.lyrics());
        assertCount(GPT, 1);
        assertCount(QWEN_LYRICS, 2);
        assertCount(ARK, 0);
    }

    @Test void aQwenBodyTimeoutAfterGptRejectionDeliversOneCompleteArkDraft() throws Exception {
        env.setProperty("qingfeng.lyrics.qwen-timeout-ms", "1500");
        server.reply(GPT, 401, Map.of());
        try (var late = server.stall(QWEN_LYRICS, 200, chat(Map.of("title", "迟到的结果", "lyrics", "不交付")))) {
            server.reply(ARK, 200, chat(SONG));
            var draft = gptFirst().write(WORK, "zh-CN");
            assertEquals(LYRICS, draft.lyrics());
            assertEquals("DOUBAO", draft.source());
            assertEquals("Acoustic guitar", draft.plan().suggestedArrangement());
            assertEquals(0, late.headers.getCount());
            late.close();
            assertTrue(late.finished.await(3, TimeUnit.SECONDS));
        }
        assertCount(GPT, 1);
        assertCount(QWEN_LYRICS, 1);
        assertCount(ARK, 1);
        var body = arkBody(0);
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertTrue(body.path("messages").path(0).path("content").asText().contains("只提交最终歌词"));
    }

    @Test void aQwenRefinementTimeoutFallsBackToThePendingRefinement() {
        server.reply(QWEN_LYRICS, 200, chat(SONG));
        server.reply(QWEN_LYRICS, 504, Map.of());
        String polished = LYRICS + "\n把暖意留给自己";
        server.reply(ARK, 200, chat(polished));
        var draft = lyrics().write(WORK, "zh-CN");
        assertEquals(SONG.get("title"), draft.title());
        assertEquals(polished, draft.lyrics());
        assertEquals("DOUBAO", draft.source());
        assertCount(QWEN_LYRICS, 2);
        assertCount(ARK, 1);
        var material = JSON.readTree(arkBody(0).path("messages").path(1).path("content").asText());
        assertEquals(SONG.get("title"), material.path("candidate").path("title").asText());
    }

    @Test void aMissingSectionCannotTriggerAnotherModelCallAfterArkDrafting() {
        server.reply(QWEN_LYRICS, 504, Map.of());
        server.reply(ARK, 200, chat(SONG));
        var error = assertThrows(DomainException.class, () -> lyrics().writeValidated(WORK, "zh-CN", "", "",
            new LyricsWriter.Direction("", "", "AUTO"), (writer, draft) -> {
                writer.repairSections(new LyricsWriter.SectionRepair(WORK, "zh-CN", "", "", draft, Map.of(2, "Bridge")));
                return draft;
            }));
        assertEquals("LYRICS_STRUCTURE_MISMATCH", error.code());
        assertCount(QWEN_LYRICS, 1);
        assertCount(ARK, 1);
    }

    @Test void aQwenSectionRepairTimeoutFallsBackOnlyForTheMissingSections() {
        server.reply(QWEN_LYRICS, 408, Map.of());
        server.reply(ARK, 200, chat(Map.of("2", "月光还在桌上")));
        var repaired = lyrics().repairSections(new LyricsWriter.SectionRepair(WORK, "zh-CN", "", "",
            new LyricsWriter.Draft("门口的小碗", LYRICS), Map.of(2, "Bridge")));
        assertEquals(Map.of(2, "月光还在桌上"), repaired);
        assertCount(QWEN_LYRICS, 1);
        assertCount(ARK, 1);
        var material = JSON.readTree(arkBody(0).path("messages").path(1).path("content").asText());
        assertEquals("Bridge", material.path("missingSections").path("2").asText());
    }

    @Test void timelyAgentQwenResultKeepsArkIdle() {
        server.reply(QWEN_AGENT, 200, chat(Map.of("action", "chat", "reply", "千问正常结果")));
        assertEquals("千问正常结果", agent().respond(context()).reply());
        assertCount(QWEN_AGENT, 1);
        assertCount(ARK, 0);
        assertCount(GPT, 0);
    }

    @Test void agentTimeoutAcceptsOneStructuredArkPlanAcrossCallsInTheSameTurn() throws Exception {
        env.setProperty("qingfeng.agent.chat.qwen-timeout-ms", "1500");
        var model = agent();
        var context = context();
        try (var late = server.stall(QWEN_AGENT, 200, chat("迟到的千问回复"))) {
            server.reply(ARK, 200, function("generate_song", Map.of("mode", "new", "text", "给妈妈写一首歌")));
            var plan = model.respond(context);
            assertEquals("generate_song", plan.action());
            assertEquals("new", plan.args().get("mode"));
            assertEquals(0, late.headers.getCount());
            late.close();
            assertTrue(late.finished.await(3, TimeUnit.SECONDS));
        }
        server.reply(QWEN_AGENT, 504, Map.of());
        assertTrue(model.respond(context).plannerUnavailable());
        assertCount(QWEN_AGENT, 2);
        assertCount(ARK, 1);
        assertCount(GPT, 0);
        assertTrue(arkBody(0).path("tools").isArray());
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 422, 429, 499, 502, 503})
    void nonTimeoutHttpFailuresNeverUseArk(int status) {
        server.reply(QWEN_LYRICS, status, Map.of("message", "offline provider material"));
        server.reply(QWEN_AGENT, status, Map.of("message", "offline provider material"));
        var error = assertThrows(DomainException.class, () -> lyrics().write(WORK, "zh-CN"));
        assertEquals(status == 502 ? "LYRICS_PROVIDER_NETWORK_ERROR" : "LYRICS_PROVIDER_UNAVAILABLE", error.code());
        assertFalse(error.getMessage().contains("offline provider material"));
        assertTrue(agent().respond(context()).plannerUnavailable());
        assertCount(QWEN_LYRICS, 1);
        assertCount(QWEN_AGENT, 1);
        assertCount(ARK, 0);
    }

    @Test void aStalledRateLimitBodyStillDoesNotTriggerFallback() {
        env.setProperty("qingfeng.lyrics.qwen-timeout-ms", "1500");
        try (var delayed = server.stall(QWEN_LYRICS, 429, Map.of("error", "rate limit"))) {
            var error = assertThrows(DomainException.class, () -> lyrics().write(WORK, "zh-CN"));
            assertEquals("LYRICS_PROVIDER_UNAVAILABLE", error.code());
            assertEquals(0, delayed.headers.getCount());
            assertCount(ARK, 0);
        }
    }

    @Test void arkFailureEndsBothChainsWithoutRetry() {
        server.reply(QWEN_LYRICS, 504, Map.of());
        server.reply(QWEN_AGENT, 504, Map.of());
        server.reply(ARK, 503, Map.of());
        server.reply(ARK, 503, Map.of());
        assertEquals("LYRICS_PROVIDER_TIMEOUT", assertThrows(DomainException.class, () -> lyrics().write(WORK, "zh-CN")).code());
        assertTrue(agent().respond(context()).plannerUnavailable());
        assertCount(QWEN_LYRICS, 1);
        assertCount(QWEN_AGENT, 1);
        assertCount(ARK, 2);
    }

    @Test void invalidArkLyricsAndUnauthorizedAgentToolsFailExistingValidation() {
        server.reply(QWEN_LYRICS, 408, Map.of());
        server.reply(QWEN_AGENT, 408, Map.of());
        server.reply(ARK, 200, chat(Map.of("title", "缺少歌词和创作计划")));
        server.reply(ARK, 200, function("unknown_tool", Map.of()));
        assertEquals("LYRICS_RESPONSE_INVALID", assertThrows(DomainException.class, () -> lyrics().write(WORK, "zh-CN")).code());
        assertTrue(agent().respond(context()).plannerUnavailable());
        assertCount(ARK, 2);
    }

    @Test void elapsedTotalBudgetsLeaveNoTimeToStartArk() {
        env.setProperty("qingfeng.lyrics.qwen-timeout-ms", "3000");
        env.setProperty("qingfeng.agent.chat.model-total-timeout-ms", "1500");
        var writer = lyrics();
        try (var late = server.stall(QWEN_LYRICS, 200, chat(SONG));
             var scope = LyricsRequestBudget.enter(Duration.ofMillis(1500))) {
            assertEquals("LYRICS_PROVIDER_TIMEOUT", assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN")).code());
            assertEquals(0, late.headers.getCount());
        }
        try (var late = server.stall(QWEN_AGENT, 200, chat("late"))) {
            var model = agent();
            assertTrue(model.respond(context()).plannerUnavailable());
            assertEquals(0, late.headers.getCount());
        }
        assertCount(QWEN_LYRICS, 1);
        assertCount(QWEN_AGENT, 1);
        assertCount(ARK, 0);
    }

    @Test void arkUsesTheRemainingBudgetAndCannotExtendIt() {
        server.reply(QWEN_AGENT, 504, Map.of());
        env.setProperty("qingfeng.agent.chat.model-total-timeout-ms", "1500");
        var model = agent();
        try (var late = server.stall(ARK, 200, chat("late"))) {
            assertTimeout(Duration.ofSeconds(4), () -> assertTrue(model.respond(context()).plannerUnavailable()));
            assertEquals(0, late.headers.getCount());
        }
        assertCount(ARK, 1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void cancellationOfAnActiveRequestPreventsFallback(boolean isAgent) throws Exception {
        String path = isAgent ? QWEN_AGENT : QWEN_LYRICS;
        env.setProperty("qingfeng.agent.chat.qwen-timeout-ms", "6000");
        env.setProperty("qingfeng.lyrics.qwen-timeout-ms", "6000");
        var model = agent();
        var writer = lyrics();
        try (var waiting = server.stall(path, 200, chat(SONG))) {
            var scope = new AgentRequestScope(Duration.ofSeconds(8), 65_536);
            var result = CompletableFuture.supplyAsync(() -> {
                try (var ignored = scope.enter()) {
                    return assertThrows(DomainException.class, () -> {
                        if (isAgent) model.respond(context()); else writer.write(WORK, "zh-CN");
                    }).code();
                }
            });
            assertTrue(waiting.headers.await(3, TimeUnit.SECONDS));
            scope.cancel();
            assertEquals(isAgent ? "AGENT_DEADLINE_EXCEEDED" : "LYRICS_PROVIDER_TIMEOUT", result.get(2, TimeUnit.SECONDS));
        }
        assertCount(ARK, 0);
    }

    @Test void closingTheSwitchRestoresBothRoutes() {
        env.setProperty("qingfeng.providers.ark.qwen-timeout-fallback-enabled", "false");
        server.reply(QWEN_LYRICS, 408, Map.of());
        server.reply(QWEN_AGENT, 504, Map.of());
        assertEquals("LYRICS_PROVIDER_TIMEOUT", assertThrows(DomainException.class, () -> lyrics().write(WORK, "zh-CN")).code());
        assertTrue(agent().respond(context()).plannerUnavailable());
        assertCount(ARK, 0);
    }

    private LyricsWriter lyrics() { return new LyricsCompositionConfiguration().lyricsWriter(env); }
    private AgentChatModel agent() {
        var settings = env.withProperty("qingfeng.providers.qwen.base-url", server.uri("/agent").toString());
        var model = new AgentConversationChatConfiguration().agentChatModel(settings);
        env.setProperty("qingfeng.providers.qwen.base-url", server.uri("/qwen").toString());
        return model;
    }
    private LyricsWriter gptFirst() {
        return new FallbackLyricsWriter(new OpenAiLyricsWriter("offline-gpt-key", "gpt-6.1-sol",
            server.uri(GPT), HttpClient.newHttpClient()), lyrics(), true);
    }
    private AgentChatModel.Context context() {
        return new AgentChatModel.Context("聊聊创作方向", "zh-CN", List.of(), null, null,
            List.of(new Capability("generate_song", "生成音乐", "{}", "paid")), null)
            .withExecution(new AgentChatModel.Execution("owner", "conversation", "request", new AgentChatModel.ModelRequest()));
    }
    private tools.jackson.databind.JsonNode arkBody(int index) {
        var call = server.calls(ARK).get(index);
        assertEquals("Bearer offline-ark-key", call.authorization());
        assertEquals(ARK_MODEL, call.body().path("model").asText());
        assertEquals("disabled", call.body().path("thinking").path("type").asText());
        assertFalse(call.body().has("enable_thinking"));
        assertFalse(call.body().path("stream").asBoolean(true));
        return call.body();
    }
    private void assertCount(String endpoint, int expected) { assertEquals(expected, server.calls(endpoint).size(), endpoint); }
}
