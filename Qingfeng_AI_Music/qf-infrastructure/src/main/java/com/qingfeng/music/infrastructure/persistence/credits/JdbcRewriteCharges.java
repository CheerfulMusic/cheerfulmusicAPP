package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditReferences;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.credits.DailyQuota;
import com.qingfeng.music.credits.RewriteCharges;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 改写的计费落库。
 * 预扣、免费额度占用与积分扣减在同一事务内完成；失败时退款与状态流转同事务，
 * 不会出现「退款失败却显示已退」的中间态。唯一键 (creator_id, request_key) 保证幂等。
 *
 * 并发上限统计在途（RESERVED）记录并落在数据库上，因此跨实例有效；
 * 进程崩溃留下的孤儿记录会被回收，不会永久占用并发额度。
 */
public final class JdbcRewriteCharges implements RewriteCharges {
    /** 超过这个时长仍在途的请求视为进程崩溃遗留，按失败处理并退还积分。 */
    static final int STALE_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final CreditsLedger credits;
    private final CreditsPricing pricing;
    private final DailyQuota quota;
    private final int globalConcurrency;
    private final int perCreatorConcurrency;
    public JdbcRewriteCharges(JdbcTemplate jdbc, TransactionTemplate transactions, CreditsLedger credits, CreditsPricing pricing, DailyQuota quota, int globalConcurrency, int perCreatorConcurrency) {
        this.jdbc = jdbc; this.transactions = transactions; this.credits = credits; this.pricing = pricing; this.quota = quota;
        this.globalConcurrency = globalConcurrency; this.perCreatorConcurrency = perCreatorConcurrency;
    }

    public Reservation reserve(String owner, String requestKey) {
        // 孤儿记录清理放在主事务之外：每条 stale 记录各自用独立事务退款，
        // 避免在一个 creator 锁内跨 owner 退款造成锁序反转（creatorA→creatorB vs creatorB→creatorA）。
        reapStale();
        return transactions.execute(tx -> {
            lockCreator(owner); // 统一锁序：creator → credit_lock（admit/debit 内部再取 credit_lock）
            var prior = jdbc.query("SELECT status,amount,charged,result_text FROM rewrite_consumption WHERE creator_id=? AND request_key=?", (rs, n) -> new Prior(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getString(4)), owner, requestKey);
            if (!prior.isEmpty()) {
                var row = prior.get(0);
                return new Reservation(false, row.charged() == 0, row.amount(), row.status(), row.result());
            }
            admit(owner);
            long freePerDay = pricing.freePerDay(CreditsPricing.Kind.REWRITE);
            // freePerDay：-1 永久免费；0 无免费额度（全部按积分收费）；正数表示前 N 次免费。
            boolean free = !pricing.enabled() || freePerDay < 0 || quota.consume(owner, DailyQuota.REWRITE, freePerDay);
            long cost = free ? 0L : pricing.cost(CreditsPricing.Kind.REWRITE);
            if (!free) credits.debit(owner, cost, CreditReferences.of("rewrite", owner, requestKey));
            jdbc.update("INSERT INTO rewrite_consumption(id,creator_id,request_key,amount,charged,status,refunded,created_at,updated_at) VALUES(?,?,?,?,?,'RESERVED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), owner, requestKey, cost, free ? 0 : 1);
            return new Reservation(true, free, cost, "RESERVED", null);
        });
    }

    public void succeed(String owner, String requestKey, String result) {
        transactions.executeWithoutResult(tx ->
            jdbc.update("UPDATE rewrite_consumption SET status='SUCCEEDED',result_text=?,updated_at=CURRENT_TIMESTAMP WHERE creator_id=? AND request_key=?", result, owner, requestKey));
    }

    public void fail(String owner, String requestKey) {
        transactions.executeWithoutResult(tx -> {
            lockCreator(owner); // 统一锁序：creator → rewrite 行 → credit_lock
            var rows = jdbc.query("SELECT amount,charged,refunded FROM rewrite_consumption WHERE creator_id=? AND request_key=? FOR UPDATE", (rs, n) -> new Charge(rs.getLong(1), rs.getInt(2), rs.getInt(3)), owner, requestKey);
            if (rows.isEmpty()) return;
            var row = rows.get(0);
            if (row.refunded() == 1) return;
            if (row.charged() == 1) credits.refund(owner, CreditReferences.of("rewrite", owner, requestKey));
            jdbc.update("UPDATE rewrite_consumption SET status='FAILED',refunded=1,updated_at=CURRENT_TIMESTAMP WHERE creator_id=? AND request_key=?", owner, requestKey);
        });
    }

    public long freeRemaining(String owner) {
        long perDay = pricing.freePerDay(CreditsPricing.Kind.REWRITE);
        // -1 表示永久免费，与 DailyQuota 的约定一致，界面据此不显示剩余次数。
        if (perDay < 0) return -1L;
        return quota.remaining(owner, DailyQuota.REWRITE, perDay);
    }

    /**
     * 跨实例并发准入：在事务内取全局行锁串行化判定，再统计在途请求数。
     * 统计落在数据库上，因此多副本部署时上限不会随实例数放大。
     */
    private void admit(String owner) {
        jdbc.queryForObject("SELECT id FROM credit_lock WHERE id=1 FOR UPDATE", Integer.class);
        Integer inflight = jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE status='RESERVED'", Integer.class);
        if (inflight != null && inflight >= globalConcurrency) throw busy();
        Integer mine = jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE status='RESERVED' AND creator_id=?", Integer.class, owner);
        if (mine != null && mine >= perCreatorConcurrency) throw busy();
    }

    /** 回收进程崩溃遗留的在途记录：按失败处理并退还已扣积分，不让它永久占用并发额度。 */
    private void reapStale() {
        var stale = jdbc.query("SELECT creator_id,request_key FROM rewrite_consumption WHERE status='RESERVED' AND created_at<TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP)", (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, STALE_SECONDS);
        for (var row : stale) {
            try {
                // 每条 stale 记录单独一个事务，统一锁序 creator → rewrite 行 → credit_lock；
                // 先按 RESERVED 条件更新状态（并发下仅一个事务能命中），命中后才退款，二者同事务，失败一起回滚。
                transactions.executeWithoutResult(tx -> {
                    lockCreator(row[0]);
                    int updated = jdbc.update("UPDATE rewrite_consumption SET status='FAILED',refunded=1,updated_at=CURRENT_TIMESTAMP WHERE creator_id=? AND request_key=? AND status='RESERVED'", row[0], row[1]);
                    if (updated == 1) credits.refund(row[0], CreditReferences.of("rewrite", row[0], row[1]));
                });
            } catch (RuntimeException single) {
                // 异常捕获位于单条事务边界之外：只回滚该条并继续处理其余，绝不影响调用方的新请求。
            }
        }
    }

    private DomainException busy() { return new DomainException("REWRITE_GENERATION_BUSY", "AI改写正在处理中，请稍后再试。"); }
    /** 以 creator 行的排他锁作为积分事务的第一把锁，建立与账本/额度一致的全局锁序。 */
    private void lockCreator(String owner) { if (owner != null && !owner.isBlank()) jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE", owner); }
    private record Prior(String status, long amount, int charged, String result) {}
    private record Charge(long amount, int charged, int refunded) {}
}
