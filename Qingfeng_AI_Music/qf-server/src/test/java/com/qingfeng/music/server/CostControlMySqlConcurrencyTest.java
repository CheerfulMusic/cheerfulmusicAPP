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
 * 六项成本封顶的「真实 MySQL 跨进程并发」验收。
 *
 * 与 CostControlConcurrencyTest（H2 内存库）互为补充：本类在独立 MySQL 实例上运行，
 * 验证生产级行锁（SELECT ... FOR UPDATE / 原子 UPDATE ... WHERE used_count&lt;?）在真实数据库引擎下
 * 不会突破每日成本上限、不会重复扣费、退款幂等、跨日隔离。
 *
 * 仅当环境变量 QF_EXECUTION_MYSQL_TEST_PORT 匹配 57[0-9]{3} 时启用（与项目既有三个 MySQL 专属测试类同机制），
 * 未设置时本类被跳过，不影响常规 mvn verify。
 *
 * 数据库为隔离的 qf_execution_concurrency_test 库（专用 schema，非生产、非本机 3306 用户实例），
 * Flyway 从空库自行迁移 V1..V23。
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
    "qingfeng.usage.cover.concurrent-per-creator=-1",
    "qingfeng.usage.cover.concurrent-global=-1",
    "qingfeng.usage.cover.daily-global=-1",
    // 歌词并发准入保持生产值（per-creator=1、global=5），使并发失败测试贴近真实生产并发形态；
    // 注：把 per-creator 放开到 -1 会制造同一 owner 16 线程同时写账本的极端场景，触发 credit_lock 与
    // creator 外键 S 锁的 AB-BA 死锁（详见验收报告「风险」一节），生产 per-creator=1 不会出现该形态。
    "qingfeng.inspiration.rewrite.concurrent-global=1000",
    "qingfeng.inspiration.rewrite.concurrent-per-creator=1000"})
@ActiveProfiles("test")
@Import(CostControlMySqlConcurrencyTest.Offline.class)
class CostControlMySqlConcurrencyTest {

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
        // MySQL 外键约束较 H2 严格：清理期间临时关闭外键检查，避免删除顺序导致失败。
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

    /** 并发地用尽每日次数配额（原子 UPDATE ... WHERE used_count&lt;?），断言不会超发。 */
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

    /** 并发预约封面 AI（每日 20 次）：真实 MySQL 行锁保证恰好 20 次成功，其余被每日上限拦截。 */
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
     * InspirationRewriteService 按 owner 串行化（active 集合），故用多个 owner 并行施压，
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
            "SELECT COUNT(*) FROM credit_ledger WHERE entry_type=? AND reference_id=?", Integer.class, "REFUND", "R1");
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

    /**
     * 并发歌词生成全部失败时，每笔扣费精确退还一次，账本净零、流水状态一致。
     * 采用「多个不同 owner 各 1 次并发生成」的真实生产形态（lyrics.concurrent-per-creator=1、global=5），
     * 避免同一 owner 超并发写入账本触发 credit_lock 与 creator 外键 S 锁的死锁（该形态生产不会出现）。
     */
    @Test
    void concurrentLyricsFailureRefundsExactlyOncePerKey() throws Exception {
        int owners = 4; // 每个 owner 并发 1 次，合计 4 次 <= 生产 global=5
        long fund = 60;
        var ownerIds = new ArrayList<String>();
        var workIds = new ArrayList<String>();
        for (int i = 0; i < owners; i++) {
            String o = identity.create().creatorId();
            credits.credit(o, fund, "fund-" + o);
            ownerIds.add(o);
            workIds.add(works.create(o, new Draft("妈妈", "生日", "一起做饭", "愿你快乐", "民谣", "故事")).id());
        }
        lyricsWriter.failure = new DomainException("LYRICS_PROVIDER_TIMEOUT", "模型超时");
        var idx = new AtomicInteger();
        var results = runConcurrently(owners, owners, () -> {
            int i = idx.incrementAndGet() - 1;
            try {
                composer.compose(ownerIds.get(i), workIds.get(i), 0, UUID.randomUUID().toString(), "zh-CN");
                return "OK";
            } catch (DomainException e) {
                return e.code();
            }
        });
        assertTrue(results.stream().noneMatch("OK"::equals), "所有并发生成均因模型失败而中止");
        for (int i = 0; i < owners; i++) {
            String o = ownerIds.get(i);
            assertEquals(fund, credits.balance(o), "并发失败下每位 owner 账本净零（扣费均被精确退还）");
            var rows = consumptions(o);
            assertEquals(1, rows.size(), "每位 owner 一次生成留下一条流水");
            var row = rows.get(0);
            assertEquals("FAILED", row.status());
            assertEquals(1, row.refunded(), "失败仅退款一次");
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
