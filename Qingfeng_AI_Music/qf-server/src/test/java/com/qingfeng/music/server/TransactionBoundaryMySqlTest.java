package com.qingfeng.music.server;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.generation.application.ExecutionRepository;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.usage.UsageBudget.Kind;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 事务边界与锁序最小修复的针对性回归（真实 MySQL 8.4.3，真实行锁，无 mock）。
 *
 * 覆盖第五阶段两处修复：
 * A. MyBatisExecutionRepository.failed 统一为 creator → generation_task → credit_lock，
 *    与 cancelMusic 的 creator → usage_lock → generation_task → credit_lock 不再反转。
 * B. JdbcRewriteCharges.reapStale 每条 stale 记录单独事务（creator → rewrite 行 → credit_lock），
 *    状态条件更新命中后才退款，二者同事务；单条失败只回滚该条并继续。
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
    "qingfeng.inspiration.rewrite.concurrent-global=1000",
    "qingfeng.inspiration.rewrite.concurrent-per-creator=1000",
    "qingfeng.usage.lyrics.concurrent-per-creator=-1",
    "qingfeng.usage.lyrics.concurrent-global=-1",
    "qingfeng.usage.lyrics.daily-global=-1",
    "qingfeng.usage.music.concurrent-per-creator=-1",
    "qingfeng.usage.music.concurrent-global=-1",
    "qingfeng.usage.music.daily-per-creator=-1",
    "qingfeng.usage.music.daily-global=-1"})
@ActiveProfiles("test")
class TransactionBoundaryMySqlTest {

    @Autowired IdentityApi identity;
    @Autowired JdbcUsageBudget usageBudget;
    @Autowired RewriteCharges rewriteCharges;
    @Autowired ExecutionRepository execution;
    @Autowired CreditsLedger credits;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.update("DELETE FROM generation_task");
            jdbc.update("DELETE FROM lyric_revision");
            jdbc.update("DELETE FROM model_usage_reservation");
            jdbc.update("DELETE FROM rewrite_consumption");
            jdbc.update("DELETE FROM credit_consumption");
            jdbc.update("DELETE FROM credit_ledger");
            jdbc.update("DELETE FROM music_work");
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    // ===== A. cancelMusic 与 worker.failed 同一 QUEUED 任务并发 =====

    @Test
    void cancelVersusWorkerFailedSameQueuedTaskNoDeadlock() throws Exception {
        String owner = identity.create().creatorId();
        credits.credit(owner, 2000, "fund-" + owner);
        int iterations = 25;
        for (int i = 0; i < iterations; i++) {
            String key = UUID.randomUUID().toString();
            String work = seedWork(owner);
            String lease = UUID.randomUUID().toString();
            String task = seedQueuedTask(work, key, lease);
            seedReservation("MUSIC", work, key, owner);
            seedDebit(owner, "music:" + work + ":" + key, 15);
            var idx = new AtomicInteger();
            var results = runConcurrently(2, 2, () -> {
                int n = idx.getAndIncrement();
                if (n == 0) {
                    boolean cancelled = usageBudget.cancelMusic(work, key, () -> jdbc.update(
                        "UPDATE generation_task SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL "
                        + "WHERE work_id=? AND id=? AND status='QUEUED' AND provider_task_id IS NULL AND submit_attempts=0", work, task) == 1);
                    return "CANCEL:" + cancelled;
                }
                execution.failed(task, lease, "PROVIDER_FAILED", Instant.now());
                return "FAILED";
            });
            // 若无死锁，两个任务都会正常返回；死锁会以 CannotAcquireLockException 从 Future.get() 抛出。
            assertEquals(2, results.size(), "迭代 " + i + "：cancel 与 failed 均完成（无死锁）");
            String status = jdbc.queryForObject("SELECT status FROM generation_task WHERE id=?", String.class, task);
            assertTrue(status.equals("CANCELLED") || status.equals("FAILED"), "任务进入终态：" + status);
            int refunds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?", Integer.class, "music:" + work + ":" + key);
            assertEquals(1, refunds, "迭代 " + i + "：同一任务恰好退款一次（cancel/failed 竞争不重复退款）");
        }
    }

    // ===== reserve 与 cancelMusic 并发 =====

    @Test
    void reserveVersusCancelMusicNoDeadlock() throws Exception {
        String owner = identity.create().creatorId();
        credits.credit(owner, 2000, "fund-" + owner);
        int iterations = 25;
        for (int i = 0; i < iterations; i++) {
            String cancelKey = UUID.randomUUID().toString();
            String cancelWork = seedWork(owner);
            String cancelTask = seedQueuedTask(cancelWork, cancelKey, UUID.randomUUID().toString());
            seedReservation("MUSIC", cancelWork, cancelKey, owner);
            seedDebit(owner, "music:" + cancelWork + ":" + cancelKey, 15);
            String reserveWork = seedWork(owner);
            String reserveKey = UUID.randomUUID().toString();
            var idx = new AtomicInteger();
            var results = runConcurrently(2, 2, () -> {
                int n = idx.getAndIncrement();
                if (n == 0) {
                    usageBudget.reserve(owner, reserveWork, reserveKey, Kind.MUSIC);
                    return "RESERVE";
                }
                usageBudget.cancelMusic(cancelWork, cancelKey, () -> jdbc.update(
                    "UPDATE generation_task SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL "
                    + "WHERE work_id=? AND id=? AND status='QUEUED' AND provider_task_id IS NULL AND submit_attempts=0", cancelWork, cancelTask) == 1);
                return "CANCEL";
            });
            assertEquals(2, results.size(), "迭代 " + i + "：reserve 与 cancel 均完成（无死锁）");
            int reserved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=? AND request_key=?", Integer.class, reserveWork, reserveKey);
            assertEquals(1, reserved, "新任务额度预留成功");
            String cancelStatus = jdbc.queryForObject("SELECT status FROM generation_task WHERE id=?", String.class, cancelTask);
            assertEquals("CANCELLED", cancelStatus, "被取消任务进入 CANCELLED");
            long sum = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?", Long.class, owner);
            assertEquals(sum, credits.balance(owner), "账本余额等于流水合计");
        }
    }

    // ===== B. reapStale 幂等 + 原子 =====

    @Test
    void reapStaleIsIdempotentAndAtomic() {
        String owner = identity.create().creatorId();
        credits.credit(owner, 500, "fund-" + owner);
        String key = UUID.randomUUID().toString();
        seedStaleRewrite(owner, key, 400); // 400 秒前的 RESERVED 记录
        String triggerOwner = identity.create().creatorId();
        credits.credit(triggerOwner, 500, "fund-" + triggerOwner);
        // 触发两次 reapStale（两次 reserve），第二次应发现记录已 FAILED，不再退款。
        rewriteCharges.reserve(triggerOwner, UUID.randomUUID().toString());
        rewriteCharges.reserve(triggerOwner, UUID.randomUUID().toString());
        String status = jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?", String.class, owner, key);
        assertEquals("FAILED", status, "stale 记录被回收为 FAILED");
        int refunded = jdbc.queryForObject("SELECT refunded FROM rewrite_consumption WHERE creator_id=? AND request_key=?", Integer.class, owner, key);
        assertEquals(1, refunded, "标记已退款");
        int refunds = jdbc.queryForObject(
            "SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?", Integer.class, "rewrite:" + owner + ":" + key);
        assertEquals(1, refunds, "重复回收不重复退款");
    }

    // ===== B. 单条 stale 失败不影响其他记录 =====

    @Test
    void reapStaleSingleFailureDoesNotBlockOthers() {
        String ownerA = identity.create().creatorId();
        String ownerB = identity.create().creatorId();
        String goodKey = UUID.randomUUID().toString();
        String badKey = UUID.randomUUID().toString();
        // 正常记录：DEBIT 属于 ownerA，可正常退款。
        seedStaleRewrite(ownerA, goodKey, 400);
        // 异常记录：rewrite_consumption 属于 ownerB，但 DEBIT 记在 ownerA 名下 → 退款时归属冲突抛异常，该条事务回滚。
        seedStaleRewriteRowOnly(ownerB, badKey, 400);
        seedDebit(ownerA, "rewrite:" + ownerB + ":" + badKey, 1);
        String triggerOwner = identity.create().creatorId();
        credits.credit(triggerOwner, 500, "fund-" + triggerOwner);
        rewriteCharges.reserve(triggerOwner, UUID.randomUUID().toString());
        // 正常记录被回收
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?", String.class, ownerA, goodKey));
        assertEquals(1, jdbc.queryForObject("SELECT refunded FROM rewrite_consumption WHERE creator_id=? AND request_key=?", Integer.class, ownerA, goodKey));
        // 异常记录整体回滚：状态仍 RESERVED、未标记已退、未产生 REFUND
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?", String.class, ownerB, badKey), "异常记录事务回滚，状态不变");
        assertEquals(0, jdbc.queryForObject("SELECT refunded FROM rewrite_consumption WHERE creator_id=? AND request_key=?", Integer.class, ownerB, badKey));
        int badRefunds = jdbc.queryForObject(
            "SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?", Integer.class, "rewrite:" + ownerB + ":" + badKey);
        assertEquals(0, badRefunds, "异常记录未退款");
    }

    // ===== 种子与并发辅助 =====

    private String seedWork(String owner) {
        String work = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at,vocal_gender) "
            + "VALUES(?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'female')",
            work, owner, "r", "birthday", "story", "msg", "folk", "t");
        return work;
    }

    private String seedQueuedTask(String work, String key, String lease) {
        String revision = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO lyric_revision(id,work_id,work_version,title,lyrics,source,confirmed,created_at) VALUES(?,?,0,?,?,?,1,CURRENT_TIMESTAMP(6))",
            revision, work, "t", "lyrics", "AI");
        String task = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO generation_task(id,work_id,revision_id,work_version,request_key,title,lyrics,style,provider,status,created_at,submit_attempts,archive_attempts,query_failures,vocal_gender,lease_token) "
            + "VALUES(?,?,?,0,?,?,?,?,?,'QUEUED',CURRENT_TIMESTAMP(6),0,0,0,'female',?)",
            task, work, revision, key, "t", "lyrics", "folk", "KIE", lease);
        return task;
    }

    private void seedReservation(String kind, String work, String key, String owner) {
        jdbc.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES(?,?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP(6))", kind, work, key, owner);
    }

    private void seedDebit(String owner, String reference, long amount) {
        jdbc.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES('DEBIT',?,?,?,CURRENT_TIMESTAMP(6))", reference, owner, -amount);
    }

    private void seedStaleRewrite(String owner, String key, int secondsAgo) {
        seedStaleRewriteRowOnly(owner, key, secondsAgo);
        seedDebit(owner, "rewrite:" + owner + ":" + key, 1);
    }

    private void seedStaleRewriteRowOnly(String owner, String key, int secondsAgo) {
        jdbc.update("INSERT INTO rewrite_consumption(id,creator_id,request_key,amount,charged,status,refunded,created_at,updated_at) "
            + "VALUES(?,?,?,1,1,'RESERVED',0,TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), owner, key, secondsAgo);
    }

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
}
