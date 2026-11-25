package com.qingfeng.music.server.config;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.server.agent.AgentChatExecutor;
import com.qingfeng.music.server.agent.AgentChatService;
import com.qingfeng.music.work.api.WorkApi;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.context.request.async.DeferredResult;
import static com.qingfeng.music.server.config.ModelFallbackTestServer.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production routing and business services, with loopback providers and a fresh in-memory database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:model_timeout_fallback;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.agent.chat.enabled=true", "qingfeng.agent.intent.llm-enabled=true",
    "qingfeng.agent.chat.qwen-timeout-ms=2000", "qingfeng.agent.chat.model-total-timeout-ms=12000",
    "qingfeng.lyrics.enabled=true", "qingfeng.lyrics.qwen-timeout-ms=1500",
    "qingfeng.providers.openai.api-key=", "qingfeng.providers.qwen.api-key=offline-qwen-key",
    "qingfeng.providers.ark.api-key=offline-ark-key", "qingfeng.providers.ark.qwen-timeout-fallback-enabled=true",
    "qingfeng.providers.ark.text-model=doubao-seed-2-1-pro-260915",
    "qingfeng.generation.enabled=true", "qingfeng.generation.scheduler-enabled=false", "qingfeng.quick.scheduler-enabled=false",
    "qingfeng.credits.enabled=true", "qingfeng.credits.lyrics-cost=5", "qingfeng.credits.music-cost=15"
})
@Sql(scripts = {"classpath:db/migration/V46__agent_chat_allowance.sql", "classpath:db/migration/V47__music_video_projects.sql",
    "classpath:db/migration/V53__agent_admission_version.sql", "classpath:db/migration/V54__song_share_statistics.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@ActiveProfiles("test")
@Import(ModelTimeoutFallbackIntegrationTest.OfflineMusic.class)
class ModelTimeoutFallbackIntegrationTest {
    private static final ModelFallbackTestServer MODELS = testServer();

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineMusic {
        @Bean @Primary MusicProvider musicProviderForFallbackTest() {
            return new MusicProvider() {
                @Override public String name() { return "offline-music"; }
                @Override public boolean isConfigured() { return true; }
                @Override public Submission submit(Request request, String key) { throw new AssertionError("Unexpected provider submission"); }
                @Override public Result query(String id) { throw new AssertionError("Unexpected provider polling"); }
            };
        }
    }

    @DynamicPropertySource static void providerUrls(DynamicPropertyRegistry properties) {
        properties.add("qingfeng.providers.qwen.base-url", () -> MODELS.uri("/qwen").toString());
        properties.add("qingfeng.providers.ark.text-base-url", () -> MODELS.uri(ARK).toString());
    }

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired LyricsCompositionService composer;
    @Autowired AgentChatExecutor executor;
    @Autowired ConversationService conversations;
    @Autowired CreditsLedger credits;
    @Autowired JdbcTemplate db;

    @BeforeEach void resetResponses() { MODELS.reset(); }
    @AfterAll static void closeServer() { MODELS.close(); }

    @Test void validatedLyricsAreSavedOnceAndSameKeyDoesNotSpendAgain() throws Exception {
        String owner = identity.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("妈妈", "生日", "门口的小碗", "", "民谣", ""));
        String key = UUID.randomUUID().toString();
        credits.credit(owner, 30, "seed-" + owner);
        // Exercise the paid path after this work's initial free grant has been consumed.
        db.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES('LYRIC_GRANT',?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP)",
            work.id(), "00000000-0000-0000-0000-000000000000", owner);
        db.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES('LYRIC_REDEEM',?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP)",
            work.id(), UUID.randomUUID().toString(), owner);
        var original = Map.of("title", "迟到的千问标题", "lyrics", "延迟结果不应写入");
        try (var qwen = MODELS.stall(QWEN_LYRICS, 200, chat(original));
             var ark = MODELS.stall(ARK, 200, chat(SONG))) {
            var result = CompletableFuture.supplyAsync(() -> compose(owner, work, key));
            assertTrue(qwen.headers.await(4, TimeUnit.SECONDS));
            assertTrue(ark.headers.await(4, TimeUnit.SECONDS));
            assertEquals("LYRICS_REQUEST_PENDING",
                assertThrows(DomainException.class, () -> compose(owner, work, key)).code());
            ark.close();
            var saved = result.get(6, TimeUnit.SECONDS);
            qwen.close();
            assertTrue(qwen.finished.await(4, TimeUnit.SECONDS));
            assertEquals("DOUBAO", saved.source());
            assertEquals(LYRICS, saved.lyrics());
            assertEquals(List.of("Verse", "Chorus"), saved.customSections());
            assertEquals(saved.id(), compose(owner, work, key).id());
            assertEquals(List.of(saved.id()), lyrics.list(owner, work.id()).stream().map(LyricsApi.Revision::id).toList());
        }
        assertEquals(1, MODELS.calls(QWEN_LYRICS).size());
        assertEquals(1, MODELS.calls(ARK).size());
        assertEquals(25, credits.balance(owner));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM credit_consumption WHERE work_id=? AND request_key=? AND task_status='SUCCEEDED'",
            Integer.class, work.id(), key));
    }

    @Test void fallbackAfterAToolResultQuotesThenQueuesAndChargesOnceOnConfirmation() throws Exception {
        String owner = identity.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("妈妈", "生日", "门口的小碗", "", "民谣", "门口的小碗"));
        var revision = lyrics.save(owner, work.id(), work.version(), "门口的小碗", LYRICS);
        var conversation = conversations.current(owner);
        conversations.applyToolResult(owner, conversation.id(), null, "{}", work.id(),
            JSON.writeValueAsString(Map.of("revisionId", revision.id())), null, 0);
        db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            owner.replace("-", "").repeat(2), owner);
        credits.credit(owner, 30, "seed-" + owner);
        String key = UUID.randomUUID().toString();
        String text = "先核对当前作品的歌词，再用它生成音乐";
        MODELS.reply(QWEN_LYRICS, 200, function("get_current_song", Map.of()));
        try (var qwen = MODELS.stall(QWEN_LYRICS, 200, chat("迟到的千问计划"))) {
            MODELS.reply(ARK, 200, function("generate_song", Map.of("mode", "regenerate", "revisionId", revision.id())));
            var first = await(executor.submit(owner, conversation.id(), text, key, "zh-CN"));
            assertEquals(0, qwen.headers.getCount());
            qwen.close();
            assertTrue(qwen.finished.await(4, TimeUnit.SECONDS));
            var quote = first.messages().stream().map(message -> JSON.readTree(message.contentJson()).path("creationQuote"))
                .filter(value -> value.hasNonNull("id")).findFirst().orElseThrow();
            assertEquals(revision.id(), quote.path("revisionId").asText());
            assertEquals(15, quote.path("credits").asInt());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?", Integer.class, work.id()));
            assertEquals(30, credits.balance(owner));
            assertNull(await(executor.submit(owner, conversation.id(), text, key, "zh-CN")).taskId());
            String approval = quote.path("id").asText();
            String command = "#approve:" + approval;
            var confirmed = await(executor.submit(owner, conversation.id(), command, approval, "zh-CN"));
            assertEquals("generate_song", confirmed.action());
            assertNotNull(confirmed.taskId());
            assertEquals("QUEUED", confirmed.songCard().status());
            var repeated = await(executor.submit(owner, conversation.id(), command, UUID.randomUUID().toString(), "zh-CN"));
            assertEquals(confirmed.taskId(), repeated.taskId());
            assertEquals(1, repeated.messages().stream().map(message -> JSON.readTree(message.contentJson()))
                .filter(content -> "tool_started".equals(content.path("stage").asText())
                    && "get_current_song".equals(content.path("action").asText())).count());
            assertTrue(repeated.messages().stream().noneMatch(message -> message.contentJson().contains("迟到的千问计划")));
        }
        assertEquals(2, MODELS.calls(QWEN_LYRICS).size());
        assertEquals(1, MODELS.calls(ARK).size());
        assertTrue(MODELS.calls(ARK).get(0).body().path("messages").toString().contains("最近真实工具结果"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?", Integer.class, work.id()));
        assertEquals(1, works.list(owner, 0).size());
        assertEquals(15, credits.balance(owner));
    }

    private LyricsApi.Revision compose(String owner, WorkApi.Work work, String key) {
        return composer.composeCustom(owner, work.id(), work.version(), key, "zh-CN", "", null,
            "CUSTOM", false, List.of("Verse", "Chorus"));
    }

    private static AgentChatService.Snapshot await(DeferredResult<AgentChatService.Snapshot> pending) throws Exception {
        var result = new CompletableFuture<>();
        pending.setResultHandler(result::complete);
        return assertInstanceOf(AgentChatService.Snapshot.class, result.get(12, TimeUnit.SECONDS));
    }

    private static ModelFallbackTestServer testServer() {
        try { return new ModelFallbackTestServer(); } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
