package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcDailyQuota;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.usage.UsageBudget.Kind;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.work.api.WorkApi.Draft;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 成本封顶边界验收：幂等并发、不同请求隔离、失败重放、超时/取消退款、每日额度跨日重置。
 *
 * 全程 H2 内存库，不触碰生产主库。仅放开与「每日成本上限」正交的并发/全局准入上限，
 * 保留生产积分单价（lyrics=5、music=15、rewrite=1）与每日上限（cover=20）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:cost_control_edges;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=true",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.inspiration.rewrite.enabled=true",
    "qingfeng.usage.lyrics.concurrent-per-creator=-1",
    "qingfeng.usage.lyrics.concurrent-global=-1",
    "qingfeng.usage.lyrics.daily-global=-1",
    "qingfeng.usage.music.concurrent-per-creator=-1",
    "qingfeng.usage.music.concurrent-global=-1",
    "qingfeng.usage.music.daily-global=-1",
    "qingfeng.usage.cover.concurrent-per-creator=-1",
    "qingfeng.usage.cover.concurrent-global=-1",
    "qingfeng.usage.cover.daily-global=-1"})
@ActiveProfiles("test")
@Import(CostControlEdgeCasesTest.Offline.class)
class CostControlEdgeCasesTest {

    static final long LYRICS_COST = 5L;
    static final long MUSIC_COST = 15L;

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsCompositionService composer;
    @Autowired CreditsLedger credits;
    @Autowired JdbcUsageBudget usageBudget;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired RecordingLyricsWriter lyricsWriter;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM model_usage_reservation");
        jdbc.update("DELETE FROM daily_quota");
        jdbc.update("DELETE FROM rewrite_consumption");
        jdbc.update("DELETE FROM credit_consumption");
        jdbc.update("DELETE FROM credit_ledger");
        jdbc.update("DELETE FROM lyrics_composition_request");
        lyricsWriter.calls.set(0);
        lyricsWriter.failure = null;
    }

    /** 并发重放同一个幂等 Key：模型只调用一次、只扣一次积分、只产生一条流水。 */
    @Test
    void concurrentSameIdempotencyKeyChargesOnlyOnce() throws Exception {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, draft()).id();
        String key = UUID.randomUUID().toString();
        int tasks = 10;
        var results = runConcurrently(10, tasks, () -> {
            try {
                composer.compose(owner, work, 0, key, "zh-CN");
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        long ok = results.stream().filter("OK"::equals).count();
        long pending = results.stream().filter("LYRICS_REQUEST_PENDING"::equals).count();
        assertEquals(tasks, ok + pending, "每个并发请求要么成功/重放，要么被明确拒绝，无其它异常");
        assertTrue(ok >= 1, "至少一次请求成功");
        assertEquals(1, lyricsWriter.calls.get(), "模型只被调用一次");
        assertEquals(1, consumptions(owner).size(), "只产生一条扣费流水");
        assertEquals(fund - LYRICS_COST, credits.balance(owner), "同一幂等 Key 只扣 5 积分");
    }

    /** 不同请求 Key 不会错误共用幂等结果：各自产生独立版本、各自精确扣费。 */
    @Test
    void distinctKeysProduceIndependentResultsAndCharges() {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, draft()).id();
        var first = composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN");
        var second = composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN");
        assertNotEquals(first.id(), second.id(), "不同请求产生不同歌词版本");
        assertEquals(2, lyricsWriter.calls.get(), "模型被调用两次");
        assertEquals(fund - 2 * LYRICS_COST, credits.balance(owner));
        var rows = consumptions(owner);
        assertEquals(2, rows.size());
        for (var row : rows) {
            assertEquals("SUCCEEDED", row.status());
            assertEquals(0, row.refunded());
            assertEquals(LYRICS_COST, row.amount());
        }
    }

    /** 任务失败后以同 Key 重放：被拒绝且不重复扣费、不重复退款。 */
    @Test
    void failedRequestReplayIsRejectedWithoutExtraCharge() {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, draft()).id();
        String key = UUID.randomUUID().toString();
        lyricsWriter.failure = new DomainException("LYRICS_INTERNAL_ERROR", "模型异常");
        assertThrows(DomainException.class, () -> composer.compose(owner, work, 0, key, "zh-CN"));
        assertEquals(fund, credits.balance(owner), "失败已全额退款");
        lyricsWriter.failure = null;
        var denied = assertThrows(DomainException.class, () -> composer.compose(owner, work, 0, key, "zh-CN"));
        assertEquals("LYRICS_REQUEST_FAILED", denied.code(), "失败请求重放被明确拒绝");
        assertEquals(fund, credits.balance(owner), "重放不扣费也不重复退款");
        assertEquals(1, lyricsWriter.calls.get(), "重放不再调用模型");
        var rows = consumptions(owner);
        assertEquals(1, rows.size(), "仍只有一条流水");
        assertEquals("FAILED", rows.get(0).status());
        assertEquals(1, rows.get(0).refunded());
    }

    /** 超时/结果未知（LYRICS_PROVIDER_TIMEOUT）走 UNKNOWN 终态，同样全额退款且流水一致。 */
    @Test
    void timeoutOutcomeIsRefundedAndMarkedUnknown() {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, draft()).id();
        String key = UUID.randomUUID().toString();
        lyricsWriter.failure = new DomainException("LYRICS_PROVIDER_TIMEOUT", "上游超时");
        assertThrows(DomainException.class, () -> composer.compose(owner, work, 0, key, "zh-CN"));
        assertEquals(fund, credits.balance(owner), "超时未交付结果，全额退款");
        String status = jdbc.queryForObject(
            "SELECT status FROM lyrics_composition_request WHERE work_id=? AND request_key=?", String.class, work, key);
        assertEquals("UNKNOWN", status, "超时保留不确定终态，避免误判为成功");
        var rows = consumptions(owner);
        assertEquals(1, rows.size());
        assertEquals("FAILED", rows.get(0).status());
        assertEquals(1, rows.get(0).refunded());
        assertEquals(LYRICS_COST, rows.get(0).amount());
    }

    /** 取消歌曲生成：退款并释放额度；重复取消、取消被否决均不破坏账本。 */
    @Test
    void cancelMusicRefundsAndReleasesReservationIdempotently() {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, draft()).id();
        String key = UUID.randomUUID().toString();
        usageBudget.reserve(owner, work, key, Kind.MUSIC);
        assertEquals(fund - MUSIC_COST, credits.balance(owner), "MUSIC 预扣 15 积分");
        assertTrue(usageBudget.cancelMusic(work, key, () -> true), "取消成功");
        assertEquals(fund, credits.balance(owner), "取消后全额退款");
        assertEquals(0, reservations(Kind.MUSIC, owner), "额度预留已释放");
        assertTrue(usageBudget.cancelMusic(work, key, () -> true), "重复取消不报错");
        assertEquals(fund, credits.balance(owner), "重复取消不重复退款");
        // 取消被否决：不退款、不释放预留。
        String key2 = UUID.randomUUID().toString();
        usageBudget.reserve(owner, work, key2, Kind.MUSIC);
        assertFalse(usageBudget.cancelMusic(work, key2, () -> false), "取消被否决");
        assertEquals(fund - MUSIC_COST, credits.balance(owner), "被否决的取消不动账本");
        assertEquals(1, reservations(Kind.MUSIC, owner), "预留仍然保留");
    }

    /** 每日额度按天重置：跨日后重新获得上限，且不把昨天的用量错误带到今天。 */
    @Test
    void dailyQuotaResetsAcrossDaysWithoutCrossDayLeak() {
        String owner = identity.create().creatorId();
        String work = works.create(owner, draft()).id();
        var ref = new AtomicReference<>(Instant.parse("2026-11-11T04:00:00Z")); // 上海时间 11 日 12:00
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var clock = new MutableClock(ref, zone);
        var quota = new JdbcDailyQuota(jdbc, clock, zone);
        var budget = new JdbcUsageBudget(jdbc, new TransactionTemplate(manager),
            Map.of(Kind.COVER, new JdbcUsageBudget.Limits(20, -1, -1, -1)), clock, zone);
        String bucket = "COST_DAY_RESET_TEST";

        // 第 1 天：用满 20 次。
        for (int i = 0; i < 20; i++) {
            assertTrue(quota.consume(owner, bucket, 20));
            budget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER);
        }
        assertFalse(quota.consume(owner, bucket, 20), "第 1 天第 21 次被拒绝");
        var denied = assertThrows(DomainException.class,
            () -> budget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED", denied.code());

        // 进入第 2 天。
        ref.set(ref.get().plus(1, ChronoUnit.DAYS));
        for (int i = 0; i < 20; i++) {
            assertTrue(quota.consume(owner, bucket, 20), "第 2 天重新获得每日额度");
            budget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER);
        }
        assertFalse(quota.consume(owner, bucket, 20), "第 2 天同样在第 21 次封顶");
        assertEquals(40, reservations(Kind.COVER, owner), "两天合计 40 条预留，各自独立计数");
        int day1 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM model_usage_reservation WHERE kind='COVER' AND owner_id=? AND usage_day=?",
            Integer.class, owner, Date.valueOf(LocalDate.of(2026, 11, 11)));
        int day2 = jdbc.queryForObject(
            "SELECT COUNT(*) FROM model_usage_reservation WHERE kind='COVER' AND owner_id=? AND usage_day=?",
            Integer.class, owner, Date.valueOf(LocalDate.of(2026, 11, 12)));
        assertEquals(20, day1, "第 1 天用量独立归档");
        assertEquals(20, day2, "第 2 天用量独立归档，未串日");
        int quotaRows = jdbc.queryForObject(
            "SELECT COUNT(*) FROM daily_quota WHERE creator_id=? AND bucket=?", Integer.class, owner, bucket);
        assertEquals(2, quotaRows, "每日次数计数按天分两行");
    }

    // ===== 辅助 =====

    private static Draft draft() {
        return new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事");
    }

    private <T> List<T> runConcurrently(int threads, int tasks, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<T>>(tasks);
            var start = new CountDownLatch(1);
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            var out = new ArrayList<T>(tasks);
            for (var f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    record Consumption(String type, long amount, String status, int refunded) {}
    List<Consumption> consumptions(String owner) {
        return jdbc.query(
            "SELECT consumption_type,amount,task_status,refunded FROM credit_consumption WHERE owner_id=? ORDER BY created_at",
            (rs, n) -> new Consumption(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getInt(4)), owner);
    }
    int reservations(Kind kind, String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND owner_id=?",
            Integer.class, kind.name(), owner);
    }

    /** 可推进的时钟：withZone 返回共享同一时刻引用的实例，用于模拟跨日。 */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> ref;
        private final ZoneId zone;
        MutableClock(AtomicReference<Instant> ref, ZoneId zone) { this.ref = ref; this.zone = zone; }
        public ZoneId getZone() { return zone; }
        public Clock withZone(ZoneId z) { return new MutableClock(ref, z); }
        public Instant instant() { return ref.get(); }
    }

    // ===== 离线桩 =====

    @TestConfiguration(proxyBeanMethods = false)
    static class Offline {
        @Bean @Primary RecordingLyricsWriter recordingLyricsWriter() { return new RecordingLyricsWriter(); }
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
}
