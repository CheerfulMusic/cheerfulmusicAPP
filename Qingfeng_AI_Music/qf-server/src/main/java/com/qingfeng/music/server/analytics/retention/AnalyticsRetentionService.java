package com.qingfeng.music.server.analytics.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 站内埋点数据的保存期限清理（隐私治理方案 §2.A）。
 *
 * <p>安全护栏（全部 fail-closed，见方案 §2.A「安全护栏 1-10」）：
 * <ol>
 *   <li>独立开关 {@code qingfeng.analytics.retention.enabled}，默认 false，关闭时整体 no-op；</li>
 *   <li>保留天数 {@code qingfeng.analytics.retention.retention-days} 由法务确定、配置注入，
 *       <b>代码不写死生产默认值</b>；缺失或 &le; 0 直接拒绝执行；小于 30 天需显式 override；</li>
 *   <li>单批上限 {@code batch-size}（默认 1000，硬上限 5000）；</li>
 *   <li>执行前 {@code SELECT COUNT(*)} 预估影响行数，超过 {@code max-rows}（默认 50000）中止并告警；</li>
 *   <li>每批独立事务：逐行 DELETE，单批失败不回滚已提交批次，不扩大删除范围；</li>
 *   <li>通过 {@link CleanupLock} 接口互斥；不支持可靠锁 / 获取失败 → 不删任何数据；</li>
 *   <li>cutoff = UTC_NOW − retention-days，严格 {@code created_at < cutoff} 语义；</li>
 *   <li>异常记录 FAILED 并告警，不重试扩大范围；</li>
 *   <li>dry-run 仅统计不删除；</li>
 *   <li>DELETE 必含 {@code created_at < ?} 谓词（常量 {@link #DELETE_SQL} 保证）。</li>
 * </ol>
 *
 * <p>审计：本轮不新增数据库表，以结构化日志落地（runId / cutoff / estimated / deleted / status）。
 * 持久化审计表与生产备份、副本演练属授权环境动作，不在本类内执行。
 */
public class AnalyticsRetentionService {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsRetentionService.class);

    /** 防误删护栏：DELETE 必含 created_at &lt; ? 谓词，且按主键精确删除。 */
    static final String DELETE_SQL = "DELETE FROM site_event WHERE id = ? AND created_at < ?";
    static final String SELECT_BATCH_SQL =
            "SELECT id FROM site_event WHERE created_at < ? ORDER BY created_at LIMIT ?";
    static final String COUNT_SQL = "SELECT COUNT(*) FROM site_event WHERE created_at < ?";

    private static final int HARD_BATCH_LIMIT = 5000;
    private static final int MIN_RETENTION_DAYS = 30;

    public enum Status {
        DISABLED, INVALID_CONFIG, TOO_MANY_ROWS, LOCK_UNAVAILABLE, DRY_RUN, SUCCESS, FAILED
    }

    public record Result(Status status, String runId, Instant cutoff, long estimated, long deleted, String reason) {
        public boolean deletedAnything() { return deleted > 0; }
    }

    private final JdbcTemplate jdbc;
    private final CleanupLock lock;
    private final boolean enabled;
    private final int retentionDays;
    private final boolean allowShortRetention;
    private final int batchSize;
    private final long maxRows;
    private final boolean dryRun;

    /**
     * 配置全部由调用方（配置类）从 Environment 注入，便于测试直接构造可控实例。
     * retentionDays &le; 0 表示「未配置」，服务会 fail-closed 拒绝执行。
     */
    public AnalyticsRetentionService(JdbcTemplate jdbc, CleanupLock lock,
                                     boolean enabled, int retentionDays, boolean allowShortRetention,
                                     int batchSize, long maxRows, boolean dryRun) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.allowShortRetention = allowShortRetention;
        this.batchSize = Math.min(Math.max(batchSize, 1), HARD_BATCH_LIMIT);
        this.maxRows = maxRows;
        this.dryRun = dryRun;
    }

    /** 一次清理运行。任何失败一律返回非 SUCCESS 状态，绝不静默降级为无锁/全表删除。 */
    public Result runOnce(Instant now) {
        String runId = UUID.randomUUID().toString();
        if (!enabled) {
            return result(Status.DISABLED, runId, null, 0, 0, "retention.enabled=false");
        }
        if (retentionDays <= 0) {
            LOG.warn("analytics.retention abort: retention-days missing or <=0 (法务未确定，禁止擅自填默认值) runId={}", runId);
            return result(Status.INVALID_CONFIG, runId, null, 0, 0, "retention-days missing or <=0");
        }
        if (retentionDays < MIN_RETENTION_DAYS && !allowShortRetention) {
            LOG.warn("analytics.retention abort: retention-days={} < {} without override runId={}",
                    retentionDays, MIN_RETENTION_DAYS, runId);
            return result(Status.INVALID_CONFIG, runId, null, 0, 0, "retention-days too short without override");
        }
        if (maxRows <= 0) {
            return result(Status.INVALID_CONFIG, runId, null, 0, 0, "max-rows must be > 0");
        }
        // 防误删护栏自检：常量被改坏时立即中止，绝不执行无 cutoff 谓词的删除。
        if (!DELETE_SQL.contains("created_at < ?")) {
            return result(Status.INVALID_CONFIG, runId, null, 0, 0, "DELETE_SQL missing cutoff predicate");
        }

        Instant cutoff = now.minus(retentionDays, ChronoUnit.DAYS);
        Timestamp cutoffTs = Timestamp.from(cutoff);

        // fail-closed：环境不支持可靠互斥锁 → 整体拒绝，绝不降级为无锁运行。
        if (!lock.supported()) {
            LOG.warn("analytics.retention abort: cleanup lock unsupported runId={}", runId);
            return result(Status.LOCK_UNAVAILABLE, runId, cutoff, 0, 0, "lock unsupported");
        }
        var handle = lock.tryAcquire();
        if (handle.isEmpty()) {
            return result(Status.LOCK_UNAVAILABLE, runId, cutoff, 0, 0, "lock busy or unavailable");
        }

        long deleted = 0;
        try (var ignored = handle.get()) {
            Long estimated = jdbc.queryForObject(COUNT_SQL, Long.class, cutoffTs);
            long count = estimated == null ? 0L : estimated;
            if (count > maxRows) {
                LOG.warn("analytics.retention abort: estimated={} exceeds maxRows={} runId={} cutoff={}",
                        count, maxRows, runId, cutoff);
                return result(Status.TOO_MANY_ROWS, runId, cutoff, count, 0, "estimated rows exceed max-rows");
            }
            if (dryRun) {
                LOG.info("analytics.retention dry-run runId={} cutoff={} estimated={}", runId, cutoff, count);
                return result(Status.DRY_RUN, runId, cutoff, count, 0, "dry-run");
            }
            while (true) {
                List<String> ids = jdbc.queryForList(SELECT_BATCH_SQL, String.class, cutoffTs, batchSize);
                if (ids.isEmpty()) break;
                for (String id : ids) {
                    // 逐行独立事务；WHERE 同时带主键与 cutoff，双重防误删。
                    deleted += jdbc.update(DELETE_SQL, id, cutoffTs);
                }
                if (ids.size() < batchSize) break;
            }
            LOG.info("analytics.retention success runId={} cutoff={} estimated={} deleted={} batchSize={}",
                    runId, cutoff, count, deleted, batchSize);
            return result(Status.SUCCESS, runId, cutoff, count, deleted, "ok");
        } catch (RuntimeException e) {
            // 异常时锁仍由 try-with-resources 释放；记录 FAILED，绝不扩大删除范围。
            LOG.error("analytics.retention failed runId={} cutoff={} deletedBeforeFailure={} type={}",
                    runId, cutoff, deleted, e.getClass().getSimpleName(), e);
            return result(Status.FAILED, runId, cutoff, 0, deleted, e.getClass().getSimpleName());
        }
    }

    private static Result result(Status status, String runId, Instant cutoff,
                                 long estimated, long deleted, String reason) {
        return new Result(status, runId, cutoff, estimated, deleted, reason);
    }
}
