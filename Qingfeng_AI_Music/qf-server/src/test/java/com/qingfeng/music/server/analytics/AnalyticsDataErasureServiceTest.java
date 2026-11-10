package com.qingfeng.music.server.analytics;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** 隐私治理方案 §2.B 历史数据擦除的授权边界 / 防误删 / NOT NULL 约束验证（真实 H2）。 */
class AnalyticsDataErasureServiceTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:erasure_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE site_event (id VARCHAR(36) NOT NULL PRIMARY KEY, "
                    + "visitor_id VARCHAR(64) NOT NULL, session_id VARCHAR(64) NOT NULL, "
                    + "event VARCHAR(50) NOT NULL, page_path VARCHAR(512) NOT NULL, "
                    + "referrer VARCHAR(512) NOT NULL, payload TEXT, created_at TIMESTAMP(6) NOT NULL, "
                    + "creator_id VARCHAR(36), duration_ms BIGINT)");
        }
        jdbc = new JdbcTemplate(ds);
    }

    private void insert(String id, String creator, String visitor) {
        jdbc.update("INSERT INTO site_event(id,visitor_id,session_id,event,page_path,referrer,payload,"
                        + "created_at,creator_id,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, visitor, "s-" + id, "pageview", "/create", "", "{\"route\":\"/create\"}",
                Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")), creator, 5000L);
    }

    private long rows() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM site_event", Long.class);
        return n == null ? 0 : n;
    }

    private AnalyticsDataErasureService service(boolean enabled, String method) {
        return new AnalyticsDataErasureService(jdbc, enabled, method, 2);
    }

    @Test
    void disabledIsNoOp() {
        insert("1", "user-a", "v1");
        var r = service(false, "delete").erase("user-a");
        assertEquals(AnalyticsDataErasureService.Status.DISABLED, r.status());
        assertEquals(0, r.affected());
        assertEquals(1, rows());
    }

    @Test
    void blankCreatorIdRejected() {
        insert("1", "user-a", "v1");
        assertEquals(AnalyticsDataErasureService.Status.INVALID_REQUEST, service(true, "delete").erase(null).status());
        assertEquals(AnalyticsDataErasureService.Status.INVALID_REQUEST, service(true, "delete").erase("  ").status());
        assertEquals(1, rows(), "无登录态不得擦除任何数据");
    }

    @Test
    void missingOrUnknownMethodFailsClosed() {
        insert("1", "user-a", "v1");
        assertEquals(AnalyticsDataErasureService.Status.INVALID_CONFIG, service(true, "").erase("user-a").status());
        assertEquals(AnalyticsDataErasureService.Status.INVALID_CONFIG, service(true, "guess").erase("user-a").status());
        assertEquals(1, rows(), "法务 L3 未定选型时不得擅自删除");
    }

    @Test
    void aggregateWithoutRollupTableIsExplicitlyUnsupported() {
        insert("1", "user-a", "v1");
        var r = service(true, "aggregate").erase("user-a");
        assertEquals(AnalyticsDataErasureService.Status.UNSUPPORTED, r.status());
        assertEquals(0, r.affected());
        assertEquals(1, rows(), "无汇总表时明确拒绝，绝不静默降级为删除");
    }

    @Test
    void deleteOnlyOwnRows_OtherUsersUntouched() {
        insert("a1", "user-a", "v1");
        insert("a2", "user-a", "v1");
        insert("a3", "user-a", "v2");
        insert("b1", "user-b", "v3");
        insert("anon", null, "v4");
        var r = service(true, "delete").erase("user-a");
        assertEquals(AnalyticsDataErasureService.Status.SUCCESS, r.status());
        assertEquals(3, r.matched());
        assertEquals(3, r.affected(), "分批循环须删净自身全部行");
        assertEquals(2, rows(), "其他用户与游客行必须原样保留（防误删核心断言）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM site_event WHERE creator_id='user-b'", Long.class));
    }

    @Test
    void anonymizeObeysNotNullAndSeversLinkability() {
        insert("a1", "user-a", "v1");
        insert("a2", "user-a", "v1");
        insert("b1", "user-b", "v3");
        var r = service(true, "anonymize").erase("user-a");
        assertEquals(AnalyticsDataErasureService.Status.SUCCESS, r.status());
        assertEquals(2, r.affected());
        assertEquals(3, rows(), "匿名化保留行，用于页面/事件聚合趋势");

        var row = jdbc.queryForMap("SELECT * FROM site_event WHERE id='a1'");
        assertEquals(AnalyticsDataErasureService.ANONYMIZED, row.get("visitor_id"), "visitor_id 为 NOT NULL，必须用哨兵值");
        assertEquals(AnalyticsDataErasureService.ANONYMIZED, row.get("session_id"), "session_id 为 NOT NULL，必须用哨兵值");
        assertNull(row.get("creator_id"), "可空列直接置空");
        assertNull(row.get("payload"), "可空列直接置空");
        assertEquals("/create", row.get("page_path"), "聚合友好字段保留");
        assertEquals(5000L, ((Number) row.get("duration_ms")).longValue());
        assertEquals("v3", jdbc.queryForObject("SELECT visitor_id FROM site_event WHERE id='b1'", String.class),
                "其他用户行不受影响");
    }

    @Test
    void eraseIsIdempotent() {
        insert("a1", "user-a", "v1");
        insert("a2", "user-a", "v1");
        var first = service(true, "delete").erase("user-a");
        var second = service(true, "delete").erase("user-a");
        assertEquals(2, first.affected());
        assertEquals(0, second.affected(), "二次执行影响行数必须为 0");
        assertEquals(0, second.matched());

        insert("c1", "user-c", "v9");
        assertEquals(1, service(true, "anonymize").erase("user-c").affected());
        assertEquals(0, service(true, "anonymize").erase("user-c").affected(),
                "匿名化后 creator_id 置空，重复请求天然幂等");
    }

    @Test
    void allWriteSqlCarryCreatorPredicate() {
        assertTrue(AnalyticsDataErasureService.DELETE_SQL.contains("creator_id = ?"));
        assertTrue(AnalyticsDataErasureService.ANONYMIZE_SQL.contains("creator_id = ?"));
        assertTrue(AnalyticsDataErasureService.SELECT_IDS_SQL.contains("creator_id = ?"));
    }
}
