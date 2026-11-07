package com.qingfeng.music.infrastructure.provider;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 同一KIE账号的音乐、封面和时间轴共享速率预算。复用现有锁表，不记录API密钥。
 * 数据库时间与短事务协调多个进程；等待发生在事务外，不提前预订未来时段。
 */
public final class KieRequestLimiter {
    enum Kind { CREATE, READ }
    public static final class Unavailable extends RuntimeException {
        public Unavailable() { super("KIE请求调度暂不可用，尚未发出本次请求"); }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String scope;
    private final long createSpacingMillis, readSpacingMillis, waitNanos;

    public KieRequestLimiter(JdbcTemplate jdbc, TransactionTemplate template, String scope,
                             int createsPerTenSeconds, int readsPerSecond, Duration maxWait) {
        this.jdbc = Objects.requireNonNull(jdbc);
        if (scope == null || !scope.matches("[A-Za-z0-9:_-]{1,40}"))
            throw new IllegalArgumentException("KIE限流账号标识无效");
        if (createsPerTenSeconds < 1 || createsPerTenSeconds > 20)
            throw new IllegalArgumentException("KIE新建请求须设置为每10秒1到20次");
        if (readsPerSecond < 1 || readsPerSecond > 10)
            throw new IllegalArgumentException("KIE查询须设置为每秒1到10次");
        if (maxWait.isZero() || maxWait.isNegative() || maxWait.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("KIE请求排队超时须大于0且不超过60秒");
        this.scope = scope;
        // 比整除间隔多1ms，避免恰好落在供应商窗口边界产生额外请求。
        this.createSpacingMillis = 10_000L / createsPerTenSeconds + 1;
        this.readSpacingMillis = 1_000L / readsPerSecond + 1;
        this.waitNanos = maxWait.toNanos();
        this.transactions = new TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(5);
    }

    public void beforeCreate() { await(Kind.CREATE); }
    public void beforeRead() { await(Kind.READ); }

    private void await(Kind kind) {
        long deadline = System.nanoTime() + waitNanos;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new Unavailable();
                Duration delay = tryAcquire(kind);
                if (delay.isZero()) return;
                TimeUnit.NANOSECONDS.sleep(Math.min(delay.toNanos(), remaining));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            // 不透出数据库异常、配置或供应商信息；调用方可明确区分“未发送”。
            throw new Unavailable();
        }
        throw new Unavailable();
    }

    Duration tryAcquire(Kind kind) {
        String row = "kie-rate:" + scope + ":" + kind.name();
        return transactions.execute(tx -> {
            jdbc.update("INSERT INTO generation_worker_lock(provider) VALUES(?) ON DUPLICATE KEY UPDATE provider=provider", row);
            var slot = jdbc.queryForObject("SELECT lease_until,CURRENT_TIMESTAMP(6) FROM generation_worker_lock WHERE provider=? FOR UPDATE",
                    (rs, n) -> new Slot(rs.getTimestamp(1), rs.getTimestamp(2).toInstant()), row);
            if (slot == null) throw new Unavailable();
            if (slot.next() != null && slot.next().toInstant().isAfter(slot.now()))
                return Duration.between(slot.now(), slot.next().toInstant());
            long spacing = kind == Kind.CREATE ? createSpacingMillis : readSpacingMillis;
            jdbc.update("UPDATE generation_worker_lock SET lease_until=? WHERE provider=?", Timestamp.from(slot.now().plusMillis(spacing)), row);
            return Duration.ZERO;
        });
    }

    private record Slot(Timestamp next, Instant now) {}
}
