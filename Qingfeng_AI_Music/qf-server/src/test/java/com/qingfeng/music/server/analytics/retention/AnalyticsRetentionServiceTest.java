package com.qingfeng.music.server.analytics.retention;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 隐私治理方案 §2.A 自动清理的 fail-closed / 防误删 / 锁护栏验证（真实 H2）。 */
class AnalyticsRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private JdbcTemplate jdbc;
    private InMemoryCleanupLock lock;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:retention_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE site_event (id VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "visitor_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, "
                    + "event VARCHAR(50) NOT NULL, page_path VARCHAR(512) NOT NULL, "
                    + "referrer VARCHAR(512) NOT NULL, payload TEXT, created_at TIMESTAMP(6) NOT NULL, "
                    + "creator_id VARCHAR(36), duration_ms BIGINT)");
        }
        jdbc = new JdbcTemplate(ds);
        lock = new InMemoryCleanupLock();
    }

    private void insert(String id, Instant createdAt) {
        jdbc.update("INSERT INTO site_event(id,visitor_id,session_id,event,page_path,referrer,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                id, "v-" + id, "s-" + id, "pageview", "/", "", Timestamp.from(createdAt));
    }

    private long rows() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM site_event", Long.class);
        return n == null ? 0 : n;
    }

    private AnalyticsRetentionService service(boolean enabled, int days, boolean allowShort,
                                              int batch, long maxRows, boolean dryRun, CleanupLock l) {
        return new AnalyticsRetentionService(jdbc, l, enabled, days, allowShort, batch, maxRows, dryRun);
    }

    @Test
    void disabledIsNoOpEvenWithValidDays() {
        insert("old", NOW.minus(400, ChronoUnit.DAYS));
        var r = service(false, 365, false, 1000, 50_000, false, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.DISABLED, r.status());
        assertEquals(0, r.deleted());
        assertEquals(1, rows(), "开关关闭时不得删任何数据");
    }

    @Test
    void missingOrNonPositiveRetentionDaysFailsClosed() {
        insert("old", NOW.minus(400, ChronoUnit.DAYS));
        assertEquals(AnalyticsRetentionService.Status.INVALID_CONFIG,
                service(true, 0, false, 1000, 50_000, false, lock).runOnce(NOW).status());
        assertEquals(AnalyticsRetentionService.Status.INVALID_CONFIG,
                service(true, -5, false, 1000, 50_000, false, lock).runOnce(NOW).status());
        assertEquals(1, rows(), "天数未由法务确定时一律拒绝执行");
    }

    @Test
    void tooShortRetentionRequiresExplicitOverride() {
        insert("old", NOW.minus(100, ChronoUnit.DAYS));
        assertEquals(AnalyticsRetentionService.Status.INVALID_CONFIG,
                service(true, 10, false, 1000, 50_000, false, lock).runOnce(NOW).status());
        assertEquals(1, rows());
    }

    @Test
    void estimatedRowsOverThresholdAbortsBeforeAnyDelete() {
        for (int i = 0; i < 5; i++) insert("old" + i, NOW.minus(400, ChronoUnit.DAYS));
        var r = service(true, 365, false, 1000, 3, false, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.TOO_MANY_ROWS, r.status());
        assertEquals(5, r.estimated());
        assertEquals(0, r.deleted());
        assertEquals(5, rows());
    }

    @Test
    void dryRunCountsButDeletesNothing() {
        insert("old", NOW.minus(400, ChronoUnit.DAYS));
        insert("fresh", NOW.minus(1, ChronoUnit.DAYS));
        var r = service(true, 365, false, 1000, 50_000, true, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.DRY_RUN, r.status());
        assertEquals(1, r.estimated());
        assertEquals(0, r.deleted());
        assertEquals(2, rows());
    }

    @Test
    void deletesOnlyRowsBeforeCutoffInBatches() {
        for (int i = 0; i < 7; i++) insert("old" + i, NOW.minus(400, ChronoUnit.DAYS));
        insert("fresh", NOW.minus(1, ChronoUnit.DAYS));
        var r = service(true, 365, false, 2, 50_000, false, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.SUCCESS, r.status());
        assertEquals(7, r.estimated());
        assertEquals(7, r.deleted(), "小批量循环必须删净所有过期行");
        assertEquals(1, rows(), "保留期内的行必须原样保留");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_event WHERE id='fresh'", Long.class));
    }

    @Test
    void cutoffBoundaryIsStrict() {
        insert("exactly", NOW.minus(365, ChronoUnit.DAYS));
        insert("justBefore", NOW.minus(365, ChronoUnit.DAYS).minusMillis(1));
        insert("justAfter", NOW.minus(365, ChronoUnit.DAYS).plusMillis(1));
        var r = service(true, 365, false, 1000, 50_000, false, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.SUCCESS, r.status());
        assertEquals(1, r.deleted(), "严格 < cutoff：恰好等于 cutoff 的行保留，只删早 1ms 的行");
        assertEquals(2, rows());
    }

    @Test
    void lockUnsupportedOrBusySkipsAllDeletes() {
        insert("old", NOW.minus(400, ChronoUnit.DAYS));
        lock.setSupported(false);
        assertEquals(AnalyticsRetentionService.Status.LOCK_UNAVAILABLE,
                service(true, 365, false, 1000, 50_000, false, lock).runOnce(NOW).status());
        assertEquals(1, rows(), "不支持可靠互斥锁时 fail-closed，绝不降级无锁运行");

        lock.setSupported(true);
        var held = lock.tryAcquire();
        assertTrue(held.isPresent());
        var r = service(true, 365, false, 1000, 50_000, false, lock).runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.LOCK_UNAVAILABLE, r.status());
        assertEquals(0, r.deleted());
        assertEquals(1, rows(), "锁被占用时清理任务必须整体跳过");
        held.get().close();
    }

    @Test
    void exceptionPathStillReleasesLock() {
        var crashing = mock(JdbcTemplate.class);
        when(crashing.queryForObject(eq(AnalyticsRetentionService.COUNT_SQL), eq(Long.class), any(Timestamp.class)))
                .thenThrow(new IllegalStateException("db down"));
        var svc = new AnalyticsRetentionService(crashing, lock, true, 365, false, 1000, 50_000, false);
        var r = svc.runOnce(NOW);
        assertEquals(AnalyticsRetentionService.Status.FAILED, r.status(), "异常须记录 FAILED 而非扩大删除");
        assertTrue(lock.tryAcquire().isPresent(), "即使清理体抛异常，锁也必须被释放（try-with-resources）");
    }

    @Test
    void deleteSqlAlwaysCarriesCutoffPredicate() {
        assertTrue(AnalyticsRetentionService.DELETE_SQL.contains("created_at < ?"),
                "防误删护栏：DELETE 必须含 created_at < ? 谓词");
        assertTrue(AnalyticsRetentionService.SELECT_BATCH_SQL.contains("created_at < ?"));
    }
}
