package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.DailyQuota;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.usage.UsageBudget.Kind;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.work.api.WorkApi.Draft;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * 成本封顶并发安全与退款幂等验收（在 {@link CostControlBatchTest} 顺序批量验证基础上，补充并发与异常路径）。
 *
 * 设计要点：
 * - 全程 H2 内存库，不触碰生产主库。
 * - 商用「每日成本上限」值（cover=20、rewrite 免费 5 次、lyrics 5 积分/次、rewrite 超额 1 积分/次等）保持生产配置；
 *   仅把与之正交的「并发准入上限」(concurrent-*) 与「全局每日上限」(daily-global) 在测试内放宽为 -1，
 *   使并发压力只落在「每日成本上限」这一被验收的对象上，避免正交的准入限流污染断言。
 * - 并发串行化依赖数据库行锁（model_usage_lock / credit_lock 的 SELECT ... FOR UPDATE），
 *   本项目 KieRequestLimiterTest 已证明 H2（MODE=MySQL）下 FOR UPDATE 能正确串行化并发事务。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:cost_control_conc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=true",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.inspiration.rewrite.enabled=true",
    // 仅放开正交的并发准入上限，保留每日成本上限（cover 每日 20 次、rewrite 免费 5 次等）。
    "qingfeng.usage.cover.concurrent-per-creator=-1",
    "qingfeng.usage.cover.concurrent-global=-1",
    "qingfeng.usage.cover.daily-global=-1",
    "qingfeng.usage.lyrics.concurrent-per-creator=-1",
    "qingfeng.usage.lyrics.concurrent-global=-1",
    "qingfeng.usage.lyrics.daily-global=-1",
    "qingfeng.inspiration.rewrite.concurrent-global=1000",
    "qingfeng.inspiration.rewrite.concurrent-per-creator=1000"})
@ActiveProfiles("test")
@Import(CostControlConcurrencyTest.Offline.class)
class CostControlConcurrencyTest {

    static final long LYRICS_COST = 5L;
    static final long REWRITE_COST = 1L;

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsCompositionService composer;
    @Autowired InspirationRewriteService rewriteService;
    @Autowired CreditsLedger credits;
    @Autowired DailyQuota dailyQuota;
    @Autowired JdbcUsageBudget usageBudget;
    @Autowired RewriteCharges rewriteCharges;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingLyricsWriter lyricsWriter;
    @Autowired RecordingRewriter rewriter;

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
        rewriter.calls.set(0);
        rewriter.failure = null;
    }

    /** 并发地用尽每日次数配额（原子 UPDATE ... WHERE used_count<?），断言不会超发。 */
    @Test
    void dailyQuotaDoesNotOverAdmitUnderConcurrency() throws Exception {
        String owner = identity.create().creatorId();
        String bucket = "COST_CONCURRENCY_TEST";
        int limit = 20;
        int tasks = 100;
        var results = runConcurrently(20, tasks, () -> dailyQuota.consume(owner, bucket, limit) ? "OK" : "DENIED");
        assertEquals(limit, results.stream().filter("OK"::equals).count(), "每日上限内恰好放行");
        assertEquals(tasks - limit, results.stream().filter("DENIED"::equals).count(), "超出每日上限全部拒绝");
        assertEquals(0, dailyQuota.remaining(owner, bucket, limit), "剩余额度归零");
        int used = jdbc.queryForObject(
            "SELECT used_count FROM daily_quota WHERE creator_id=? AND bucket=? AND usage_day=?",
            Integer.class, owner, bucket, Date.valueOf(LocalDate.now()));
        assertEquals(limit, used, "落库计数不超过上限");
    }

    /** 并发预约封面 AI（每日 20 次）：串行化行锁保证恰好 20 次成功，其余被每日上限拦截。 */
    @Test
    void usageBudgetCoverCapHeldUnderConcurrency() throws Exception {
        String owner = identity.create().creatorId();
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        int cap = 20;
        int tasks = 50;
        var results = runConcurrently(20, tasks, () -> {
            try {
                usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.COVER);
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        assertEquals(cap, results.stream().filter("OK"::equals).count(), "并发下每日上限内恰好放行");
        assertEquals(tasks - cap, results.stream().filter("PERSONAL_DAILY_QUOTA_EXCEEDED"::equals).count(), "超出部分被正确拒绝");
        assertEquals(cap, reservations(Kind.COVER, owner), "落库预约数不超过每日上限");
    }

    /**
     * 并发 AI 改写：免费 5 次 + 超额 1 积分/次。
     * 注意：InspirationRewriteService 按 owner 串行化（active 集合），故用多个 owner 并行施压，
     * 每个 owner 顺序跑 6 次（5 免费 + 1 计费），验证免费额度不超发、账本扣费与流水一致。
     */
    @Test
    void rewriteFreeQuotaCapHeldUnderConcurrency() throws Exception {
        int owners = 10;
        int perOwner = 6; // 5 免费 + 1 计费
        long fundPerOwner = 50;
        var ownerIds = new ArrayList<String>();
        for (int i = 0; i < owners; i++) {
            String o = identity.create().creatorId();
            credits.credit(o, fundPerOwner, "fund-" + o);
            ownerIds.add(o);
        }
        var idx = new AtomicInteger();
        var results = runConcurrently(owners, owners, () -> {
            String owner = ownerIds.get(idx.incrementAndGet() - 1);
            for (int i = 0; i < perOwner; i++) {
                rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
            }
            return "OK";
        });
        assertEquals(owners, results.stream().filter("OK"::equals).count(), "所有 owner 的改写均完成（并发准入已放开）");
        long totalFree = 0, totalPaid = 0, totalCharged = 0;
        for (String o : ownerIds) {
            assertEquals(5, freeRows(o), "每位 owner 免费额度恰好 5 次，不超发");
            assertEquals(1, paidRows(o), "每位 owner 第 6 次计费");
            assertEquals(fundPerOwner - REWRITE_COST, credits.balance(o), "账本余额与实际扣费一致");
            totalFree += freeRows(o);
            totalPaid += paidRows(o);
            totalCharged += totalCharged(o);
        }
        assertEquals((long) owners * 5, totalFree, "全局免费次数 = owner 数 × 5");
        assertEquals((long) owners * 1, totalPaid, "全局计费次数 = owner 数 × 1");
        assertEquals((long) owners * REWRITE_COST, totalCharged, "流水累计扣费等于实际计费次数");
    }

    /** 退款幂等：同一笔扣费退款两次，余额只恢复一次，仅产生一条 REFUND 流水。 */
    @Test
    void refundIsIdempotentAndDoesNotDoubleCredit() {
        String owner = identity.create().creatorId();
        credits.credit(owner, 100, "fund-" + owner);
        credits.debit(owner, 5, "R1");
        assertEquals(95, credits.balance(owner));
        credits.refund(owner, "R1");
        assertEquals(100, credits.balance(owner));
        credits.refund(owner, "R1"); // 第二次退款应被幂等忽略
        assertEquals(100, credits.balance(owner));
        int refunds = jdbc.queryForObject(
            "SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?", Integer.class, "R1");
        assertEquals(1, refunds, "仅产生一条退款流水");
    }

    /** 改写失败退款幂等：已扣积分退还后再次 fail 不再重复退款，状态保持已退。 */
    @Test
    void rewriteFailIsIdempotentAndDoesNotDoubleRefund() {
        String owner = identity.create().creatorId();
        credits.credit(owner, 20, "fund-" + owner);
        // 先耗尽 5 次免费（使用合法 UUID 请求键），使第 6 次走积分扣费。
        for (int i = 0; i < 5; i++) rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", UUID.randomUUID().toString());
        String chargedKey = UUID.randomUUID().toString();
        rewriteService.rewrite(owner, "我想感谢爸爸", "zh-CN", chargedKey); // 计费 1 积分
        long beforeFail = credits.balance(owner);
        assertEquals(19, beforeFail);
        rewriteCharges.fail(owner, chargedKey);
        assertEquals(beforeFail + 1, credits.balance(owner), "失败后积分已退还");
        rewriteCharges.fail(owner, chargedKey); // 幂等：不应再次退款
        assertEquals(beforeFail + 1, credits.balance(owner));
        int refunded = jdbc.queryForObject(
            "SELECT refunded FROM rewrite_consumption WHERE creator_id=? AND request_key=?", Integer.class, owner, chargedKey);
        assertEquals(1, refunded, "仅标记一次已退");
    }

    /** 并发歌词生成全部失败时，每笔扣费精确退还一次，账本净零、流水状态一致。 */
    @Test
    void concurrentLyricsFailureRefundsExactlyOncePerKey() throws Exception {
        String owner = identity.create().creatorId();
        long fund = 60;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        lyricsWriter.failure = new DomainException("LYRICS_PROVIDER_TIMEOUT", "模型超时");
        int tasks = 10;
        var results = runConcurrently(16, tasks, () -> {
            try {
                composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN");
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        assertTrue(results.stream().noneMatch("OK"::equals), "所有并发生成均因模型失败而中止");
        assertEquals(fund, credits.balance(owner), "并发失败下账本净零（每笔扣费均被精确退还）");
        var rows = consumptions(owner);
        assertEquals(tasks, rows.size(), "每次生成均留下一条流水");
        for (var row : rows) {
            assertEquals("FAILED", row.status());
            assertEquals(1, row.refunded(), "每笔失败仅退款一次");
            assertEquals(LYRICS_COST, row.amount());
        }
    }

    // ===== 并发执行辅助 =====

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

    // ===== 数据读取辅助 =====

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

    // ===== 离线桩 =====

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
