package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.agent.application.ReferenceAnalysisService;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.DailyQuota;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.settings.MusicBriefService;
import com.qingfeng.music.usage.UsageBudget.Kind;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.work.api.WorkApi.Draft;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 成本封顶批量验收：覆盖用户要求的六项商用成本上限，全部按真实商用值跑批量循环到上限，
 * 验证「封顶、扣费、失败退还、幂等」四类行为，并断言线上配置等于商用契约。
 *
 * 全程使用 H2 内存库，不触碰生产主库（符合自动化验证不得向主库写测试夹具的约束）。
 * 真实商用值来自 application.yml + usage-limits.properties，本测试不覆盖它们，只读取并断言。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:cost_control_batch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=true",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.inspiration.rewrite.enabled=true"})
@ActiveProfiles("test")
@Import(CostControlBatchTest.Offline.class)
class CostControlBatchTest {

    /** 歌词生成单次积分（商用契约值，与 application.yml 的 qingfeng.credits.lyrics-cost 一致）。 */
    static final long LYRICS_COST = 5L;
    /** AI 改写单次超额积分。 */
    static final long REWRITE_COST = 1L;

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsCompositionService composer;
    @Autowired CompositionRequests requests;
    @Autowired InspirationRewriteService rewriteService;
    @Autowired CreditsLedger credits;
    @Autowired DailyQuota dailyQuota;
    @Autowired JdbcUsageBudget usageBudget;
    @Autowired RewriteCharges rewriteCharges;
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingLyricsWriter lyricsWriter;
    @Autowired RecordingRewriter rewriter;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM model_usage_reservation");
        jdbc.update("DELETE FROM daily_quota");
        jdbc.update("DELETE FROM rewrite_consumption");
        jdbc.update("DELETE FROM credit_consumption");
        lyricsWriter.calls.set(0);
        lyricsWriter.failure = null;
        rewriter.calls.set(0);
        rewriter.failure = null;
    }

    // ===== 契约断言：线上配置必须等于商用要求 =====

    @Test
    void commercialConfiguredValuesMatchContract() {
        assertEquals(5L, env.getProperty("qingfeng.credits.lyrics-cost", Long.class));
        assertEquals(1L, env.getProperty("qingfeng.credits.rewrite-cost", Long.class));
        assertEquals(5L, env.getProperty("qingfeng.credits.rewrite-free-per-day", Long.class));
        assertEquals(20, env.getProperty("qingfeng.usage.cover.daily-per-creator", Integer.class));
        assertEquals(50, env.getProperty("qingfeng.usage.timing.daily-per-creator", Integer.class));
        assertEquals(20L, env.getProperty("qingfeng.music-settings.simplify.daily-per-creator", Long.class));
        assertEquals(20L, env.getProperty("qingfeng.reference.analysis.daily-per-creator", Long.class));
    }

    // ===== 1. 歌词生成积分：5 积分/次，批量循环不超额、失败退还、幂等 =====

    @Test
    void lyricsGenerationChargesExactlyFiveCreditsPerCallInBatch() {
        String owner = identity.create().creatorId();
        credits.credit(owner, LYRICS_COST * 20, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();

        for (int i = 0; i < 20; i++) {
            var revision = composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN");
            assertNotNull(revision.id());
        }
        assertEquals(20, lyricsWriter.calls.get());
        assertEquals(0, credits.balance(owner)); // 20 * 5 = 100，正好扣完
        var rows = consumptions(owner);
        assertEquals(20, rows.size());
        for (var row : rows) {
            assertEquals("LYRICS_GENERATION", row.type());
            assertEquals(LYRICS_COST, row.amount());
            assertEquals("SUCCEEDED", row.status());
            assertEquals(0, row.refunded());
        }
        // 余额耗尽后再次生成被拦截，不再调用模型、不再扣费。
        var denied = assertThrows(DomainException.class,
            () -> composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN"));
        assertEquals("INSUFFICIENT_CREDITS", denied.code());
        assertEquals(20, lyricsWriter.calls.get());
    }

    @Test
    void lyricsGenerationFailureRefundsTheReservedCredits() {
        String owner = identity.create().creatorId();
        credits.credit(owner, LYRICS_COST * 5, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        // 失败退还：扣费后模型异常，积分应退回原额，流水标记已退。
        lyricsWriter.failure = new DomainException("LYRICS_PROVIDER_TIMEOUT", "超时");
        String failedKey = UUID.randomUUID().toString();
        assertThrows(DomainException.class, () -> composer.compose(owner, work, 0, failedKey, "zh-CN"));
        assertEquals(LYRICS_COST * 5, credits.balance(owner)); // 25，已原额退还
        var rows = consumptions(owner);
        assertEquals(1, rows.size());
        assertEquals("FAILED", rows.get(0).status());
        assertEquals(1, rows.get(0).refunded());
    }

    @Test
    void lyricsGenerationIsIdempotentPerKey() {
        String owner = identity.create().creatorId();
        credits.credit(owner, LYRICS_COST * 5, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        // 幂等：同一 requestKey 重放只扣一次。
        String key = UUID.randomUUID().toString();
        var first = composer.compose(owner, work, 0, key, "zh-CN");
        var second = composer.compose(owner, work, 0, key, "zh-CN");
        assertEquals(first.id(), second.id());
        assertEquals(1, lyricsWriter.calls.get());
        assertEquals(LYRICS_COST * 4, credits.balance(owner)); // 25 - 5 = 20，仅一次扣费
        var rows = consumptions(owner);
        assertEquals(1, rows.size());
        assertEquals("SUCCEEDED", rows.get(0).status());
        assertEquals(0, rows.get(0).refunded());
    }

    // ===== 2. AI 改写积分：每日免费 5 次 + 超额 1 积分/次 =====

    @Test
    void aiRewriteUsesFiveFreeThenChargesOneCreditPerExtra() {
        String owner = identity.create().creatorId();
        credits.credit(owner, 200, "fund-" + owner);

        // 前 5 次免费，不扣积分。
        for (int i = 0; i < 5; i++) {
            rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
        }
        assertEquals(200, credits.balance(owner));
        assertEquals(0L, rewriteCharges.freeRemaining(owner));
        assertEquals(5, rewriter.calls.get());

        // 之后每次扣 1 积分，连续 15 次共扣 15。
        for (int i = 0; i < 15; i++) {
            rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
        }
        assertEquals(200 - 15 * REWRITE_COST, credits.balance(owner));
        assertEquals(20, rewriter.calls.get());
        assertEquals(5, freeRows(owner));
        assertEquals(15, paidRows(owner));
        assertEquals(15L, totalCharged(owner));
    }

    @Test
    void aiRewriteRejectsWhenFreeUsedUpAndBalanceExhausted() {
        String owner = identity.create().creatorId();
        credits.credit(owner, 5, "fund-" + owner); // 仅够 5 次免费的「额度」，无积分可扣超额

        for (int i = 0; i < 5; i++) {
            rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
        }
        assertEquals(5, credits.balance(owner)); // 免费，未扣
        for (int i = 0; i < 5; i++) {
            rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
        }
        assertEquals(0, credits.balance(owner)); // 5 次超额，各扣 1
        // 余额耗尽，第 11 次被拦截（5 免费 + 5 已扣，余额 0）。
        var denied = assertThrows(DomainException.class,
            () -> rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString()));
        assertEquals("INSUFFICIENT_CREDITS", denied.code());
        assertEquals(5, freeRows(owner));
        assertEquals(5, paidRows(owner));
    }

    // ===== 3. 封面 AI：按图计费，每日 20 次 =====

    @Test
    void coverAiIsCappedAtTwentyPerDay() {
        String owner = identity.create().creatorId();
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        for (int i = 0; i < 20; i++) {
            usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER);
        }
        assertEquals(20, reservations(Kind.COVER, owner));
        assertEquals(0, usageBudget.allowance(owner, Kind.COVER).dailyRemaining());
        var denied = assertThrows(DomainException.class,
            () -> usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED", denied.code());
    }

    // ===== 4. 歌词时间轴：每日 50 次 =====

    @Test
    void lyricTimelineIsCappedAtFiftyPerDay() {
        String owner = identity.create().creatorId();
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        for (int i = 0; i < 50; i++) {
            usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.TIMING);
        }
        assertEquals(50, reservations(Kind.TIMING, owner));
        assertEquals(0, usageBudget.allowance(owner, Kind.TIMING).dailyRemaining());
        var denied = assertThrows(DomainException.class,
            () -> usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.TIMING));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED", denied.code());
    }

    // ===== 5. 音乐要求精简：每日 20 次（不直接计费，用每日次数封顶） =====

    @Test
    void musicBriefIsCappedAtTwentyPerDay() {
        long limit = env.getProperty("qingfeng.music-settings.simplify.daily-per-creator", Long.class, 20L);
        var calls = new AtomicInteger();
        var service = new MusicBriefService(new InspirationRewriter() {
            public boolean isConfigured() { return true; }
            public String rewrite(String text, String language) { calls.incrementAndGet(); return text; }
        }, true, 2, dailyQuota, limit);

        String owner = identity.create().creatorId();
        for (int i = 0; i < 20; i++) {
            service.suggest(owner, "温暖，90 BPM，不要吉他");
        }
        assertEquals(20, calls.get());
        assertEquals(0, dailyQuota.remaining(owner, DailyQuota.MUSIC_BRIEF, limit));
        var denied = assertThrows(DomainException.class, () -> service.suggest(owner, "温暖，90 BPM，不要吉他"));
        assertEquals("MUSIC_BRIEF_QUOTA_EXCEEDED", denied.code());
        assertEquals(20, calls.get()); // 超限不再调用模型
    }

    // ===== 6. Agent 参考分析：每日 20 次（不直接计费，用每日次数封顶） =====

    @Test
    void referenceAnalysisIsCappedAtTwentyPerDay() {
        long limit = env.getProperty("qingfeng.reference.analysis.daily-per-creator", Long.class, 20L);
        var calls = new AtomicInteger();
        var analyst = new ReferenceAnalyst() {
            public boolean isConfigured() { return true; }
            public Suggestion analyse(Request request) { calls.incrementAndGet(); return new Suggestion("pop", "明亮", 0, List.of(), List.of(), "轻快明亮"); }
        };
        var service = new ReferenceAnalysisService(analyst, true, 2, dailyQuota, limit);

        String owner = identity.create().creatorId();
        var request = new ReferenceAnalyst.Request("温暖", "ref.mp3", 30, "zh-CN", features());
        for (int i = 0; i < 20; i++) {
            assertNotNull(service.analyse(owner, request));
        }
        assertEquals(20, calls.get());
        assertEquals(0, dailyQuota.remaining(owner, DailyQuota.REFERENCE_ANALYSIS, limit));
        var denied = assertThrows(DomainException.class, () -> service.analyse(owner, request));
        assertEquals("REFERENCE_ANALYSIS_QUOTA_EXCEEDED", denied.code());
        assertEquals(20, calls.get());
    }

    // ===== 辅助方法 =====

    record Consumption(String owner, String type, String work, String requestKey, long amount, String status, int refunded) {}
    List<Consumption> consumptions(String owner) {
        return jdbc.query(
            "SELECT owner_id,consumption_type,work_id,request_key,amount,task_status,refunded FROM credit_consumption WHERE owner_id=? ORDER BY created_at",
            (rs, n) -> new Consumption(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getInt(7)),
            owner);
    }
    int freeRows(String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=? AND charged=0", Integer.class, owner);
    }
    int paidRows(String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=? AND charged=1", Integer.class, owner);
    }
    long totalCharged(String owner) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM rewrite_consumption WHERE creator_id=?", Long.class, owner);
    }
    int reservations(Kind kind, String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND owner_id=?", Integer.class, kind.name(), owner);
    }
    static ReferenceAnalyst.Features features() {
        return new ReferenceAnalyst.Features(120, 0.9, 0.5, 0.9, 0.5, 0.2, 0.2, 0.1, 0.6, 0.4, 30, 30);
    }

    // ===== 离线桩：替换真实模型写入，专注成本逻辑验收 =====

    @TestConfiguration(proxyBeanMethods = false)
    static class Offline {
        @Bean @Primary RecordingLyricsWriter recordingLyricsWriter() { return new RecordingLyricsWriter(); }
        @Bean @Primary RecordingRewriter recordingRewriter() { return new RecordingRewriter(); }
    }

    static class RecordingLyricsWriter implements LyricsWriter {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;
        public boolean isConfigured() { return true; }
        public Draft write(WorkApi.Work work, String language) { throw new AssertionError("应携带修改上下文"); }
        public Draft write(WorkApi.Work work, String language, String instructions, String previousLyrics) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return new Draft("新的歌", "[Chorus]\n愿你的每一天都有光");
        }
    }

    static class RecordingRewriter implements InspirationRewriter {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;
        public boolean isConfigured() { return true; }
        public String rewrite(String text, String language) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            return "改写后的创作描述";
        }
    }
}
