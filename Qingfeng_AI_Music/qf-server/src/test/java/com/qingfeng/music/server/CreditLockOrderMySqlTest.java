package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.usage.UsageBudget.Kind;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.work.api.WorkApi.Draft;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
 * 积分账本 InnoDB 死锁专项回归（真实 MySQL 8.4.3）。
 *
 * 目的：复现第二阶段发现的 credit_lock → creator 与 creator → credit_lock 的 AB-BA 锁序反转，
 * 并在修复（统一为 creator → usage_lock → credit_lock）后证明死锁消失。
 *
 * 关键：本类**刻意放开**歌词并发准入（per-creator=-1、global=-1），以便对「同一 owner」施压——
 * 这正是最初触发死锁的形态；修复前此形态会抛 CannotAcquireLockException（Deadlock），
 * 修复后应全部完成且账本一致。这里放开准入是「复现原始死锁」所必需，不是放宽断言。
 *
 * 仅当 QF_EXECUTION_MYSQL_TEST_PORT 匹配 57[0-9]{3} 时启用，使用隔离库 qf_execution_concurrency_test。
 */
@EnabledIfEnvironmentVariable(named = "QF_EXECUTION_MYSQL_TEST_PORT", matches = "57[0-9]{3}")
@SpringBootTest(classes = com.qingfeng.music.server.MusicApplication.class, properties = {
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
    "spring.datasource.username=root",
    "spring.datasource.password=",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
    "qingfeng.credits.enabled=true",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.inspiration.rewrite.enabled=true",
    // 刻意放开准入，复现「同一 owner 并发」的死锁形态（修复前会死锁，修复后通过）。
    "qingfeng.usage.lyrics.concurrent-per-creator=-1",
    "qingfeng.usage.lyrics.concurrent-global=-1",
    "qingfeng.usage.lyrics.daily-global=-1",
    "qingfeng.usage.cover.concurrent-per-creator=-1",
    "qingfeng.usage.cover.concurrent-global=-1",
    "qingfeng.usage.cover.daily-global=-1",
    "qingfeng.inspiration.rewrite.concurrent-global=1000",
    "qingfeng.inspiration.rewrite.concurrent-per-creator=1000"})
@ActiveProfiles("test")
@Import(CreditLockOrderMySqlTest.Offline.class)
class CreditLockOrderMySqlTest {

    static final long LYRICS_COST = 5L;

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsCompositionService composer;
    @Autowired InspirationRewriteService rewriteService;
    @Autowired CreditsLedger credits;
    @Autowired JdbcUsageBudget usageBudget;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingLyricsWriter lyricsWriter;
    @Autowired RecordingRewriter rewriter;

    @BeforeEach
    void reset() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("DELETE FROM model_usage_reservation");
            jdbc.update("DELETE FROM daily_quota");
            jdbc.update("DELETE FROM rewrite_consumption");
            jdbc.update("DELETE FROM credit_consumption");
        jdbc.update("DELETE FROM credit_account");
        jdbc.update("DELETE FROM credit_ledger");
            jdbc.update("DELETE FROM lyrics_composition_request");
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        lyricsWriter.calls.set(0);
        lyricsWriter.failure = null;
        rewriter.calls.set(0);
        rewriter.failure = null;
    }

    /**
     * 原始死锁场景（同一 owner、16 线程并发生成且全部失败）。
     * 修复前：reserve(creator→credit_lock) 与 refund(credit_lock→creator) 锁序反转 → Deadlock。
     * 修复后：全部完成、每笔精确退款一次、账本净零。
     */
    @Test
    void originalSameOwnerConcurrentFailureNoDeadlock() throws Exception {
        String owner = identity.create().creatorId();
        long fund = 200; // 16 次 × 5 积分 = 80，留足余额，确保 16 次都真正走「扣费→失败→退款」
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        lyricsWriter.failure = new DomainException("LYRICS_PROVIDER_TIMEOUT", "模型超时");
        int tasks = 16;
        var results = runConcurrently(16, tasks, () -> {
            try {
                composer.compose(owner, work, 0, UUID.randomUUID().toString(), "zh-CN");
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        // 若发生死锁，CannotAcquireLockException(DataAccessException) 会从 Future.get() 抛出，测试即失败。
        assertTrue(results.stream().noneMatch("OK"::equals), "所有并发生成均因模型失败而中止");
        assertEquals(fund, credits.balance(owner), "并发失败下账本净零（每笔扣费均被精确退还）");
        long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?", Long.class, owner);
        assertEquals(sum, credits.balance(owner), "账本余额等于流水合计");
        var rows = jdbc.queryForList("SELECT task_status AS status, refunded, amount FROM credit_consumption WHERE owner_id=?", owner);
        assertEquals(tasks, rows.size(), "每次生成均留下一条消费流水");
        for (var row : rows) {
            assertEquals("FAILED", row.get("status"));
            assertEquals(1, ((Number) row.get("refunded")).intValue(), "每笔失败仅退款一次");
            assertEquals(LYRICS_COST, ((Number) row.get("amount")).longValue());
        }
    }

    /**
     * 同一 owner 上 reserve（creator→usage_lock→credit_lock）与 refund（修复前 credit_lock→creator）
     * 并发执行，修复后不应死锁，且账本与流水一致。
     */
    @Test
    void reserveVersusRefundSameOwnerNoDeadlock() throws Exception {
        String owner = identity.create().creatorId();
        credits.credit(owner, 1000, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        int seeds = 10;
        for (int i = 0; i < seeds; i++) credits.debit(owner, 1, "seed-" + i);
        int reserves = 10;
        var idx = new AtomicInteger();
        var results = runConcurrently(16, seeds + reserves, () -> {
            int n = idx.incrementAndGet() - 1;
            try {
                if (n < seeds) { credits.refund(owner, "seed-" + n); return "REFUND"; }
                usageBudget.reserve(owner, work, UUID.randomUUID().toString(), Kind.LYRICS);
                return "RESERVE";
            } catch (DomainException e) {
                return e.code();
            }
        });
        assertEquals(seeds + reserves, results.size(), "全部任务完成（无死锁）");
        long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?", Long.class, owner);
        assertEquals(sum, credits.balance(owner), "账本余额等于流水合计");
    }

    /** 不同 owner 并发扣费/退款，互不串账，各自账本正确。 */
    @Test
    void differentOwnerConcurrentChargeNoDeadlock() throws Exception {
        int owners = 12;
        long fund = 100;
        var ownerIds = new ArrayList<String>();
        for (int i = 0; i < owners; i++) {
            String o = identity.create().creatorId();
            credits.credit(o, fund, "fund-" + o);
            ownerIds.add(o);
        }
        var idx = new AtomicInteger();
        var results = runConcurrently(12, owners, () -> {
            String o = ownerIds.get(idx.incrementAndGet() - 1);
            for (int i = 0; i < 5; i++) {
                String ref = "d-" + UUID.randomUUID();
                credits.debit(o, 2, ref);
                credits.refund(o, ref);
            }
            return "OK";
        });
        assertEquals(owners, results.stream().filter("OK"::equals).count(), "全部 owner 完成（无死锁）");
        for (String o : ownerIds) {
            assertEquals(fund, credits.balance(o), "每个 owner 扣费+退款净零");
            long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?", Long.class, o);
            assertEquals(sum, credits.balance(o), "每个 owner 账本余额等于流水合计");
        }
    }

    /** 同一幂等 Key 并发提交，只扣一次费、只生成一条流水（锁序调整不破坏幂等）。 */
    @Test
    void sameIdempotencyKeyConcurrentChargesOnce() throws Exception {
        String owner = identity.create().creatorId();
        long fund = 100;
        credits.credit(owner, fund, "fund-" + owner);
        String work = works.create(owner, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id();
        String key = UUID.randomUUID().toString();
        int tasks = 10;
        var results = runConcurrently(10, tasks, () -> {
            try {
                usageBudget.reserve(owner, work, key, Kind.LYRICS);
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        assertEquals(tasks, results.size(), "全部任务完成（无死锁）");
        int reservations = jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRICS' AND owner_id=?", Integer.class, owner);
        assertEquals(1, reservations, "同一幂等 Key 仅一条额度预留");
        int debits = jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='DEBIT'", Integer.class, owner);
        assertEquals(1, debits, "同一幂等 Key 仅扣费一次");
        assertEquals(fund - LYRICS_COST, credits.balance(owner), "余额只扣一次");
    }

    /** 同一 owner 上大量 debit 与 refund 交错并发，账本一致、无死锁、无重复退款。 */
    @Test
    void debitRefundInterleavedSameOwnerNoDeadlock() throws Exception {
        String owner = identity.create().creatorId();
        long fund = 1000;
        credits.credit(owner, fund, "fund-" + owner);
        int perThread = 20;
        int threads = 8;
        var results = runConcurrently(threads, threads, () -> {
            for (int i = 0; i < perThread; i++) {
                String ref = "x-" + UUID.randomUUID();
                credits.debit(owner, 1, ref);
                credits.refund(owner, ref);
            }
            return "OK";
        });
        assertEquals(threads, results.stream().filter("OK"::equals).count(), "全部线程完成（无死锁）");
        assertEquals(fund, credits.balance(owner), "交错扣费+退款净零");
        long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?", Long.class, owner);
        assertEquals(sum, credits.balance(owner), "账本余额等于流水合计");
        int debits = jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='DEBIT'", Integer.class, owner);
        int refunds = jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'", Integer.class, owner);
        assertEquals(debits, refunds, "每笔扣费恰好对应一笔退款（无重复退款）");
    }

    // ===== 并发辅助 =====
    private <T> List<T> runConcurrently(int threads, int tasks, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<Future<T>>(tasks);
            var start = new CountDownLatch(1);
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            }
            start.countDown();
            var out = new ArrayList<T>(tasks);
            for (var f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
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
