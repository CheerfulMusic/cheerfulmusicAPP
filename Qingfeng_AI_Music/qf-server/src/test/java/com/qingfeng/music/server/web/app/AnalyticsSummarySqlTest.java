package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在真实 H2 上执行看板的四条统计 SQL，避免只用 mock JdbcTemplate 造成假阳性。 */
class AnalyticsSummarySqlTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test @SuppressWarnings("unchecked")
    void summaryQueriesExecuteAgainstRealDatabase() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_summary_sql;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), recipient VARCHAR(80), occasion VARCHAR(32), story TEXT, message VARCHAR(500), style VARCHAR(80), title VARCHAR(100), version BIGINT, created_at TIMESTAMP(6), updated_at TIMESTAMP(6))");
            statement.execute("CREATE TABLE payment_order (id VARCHAR(36) PRIMARY KEY, creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, provider_link_id VARCHAR(128), status VARCHAR(24), created_at TIMESTAMP(6))");
            statement.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000,'中国')");
            statement.execute("INSERT INTO site_event VALUES ('2','v1','s1','page_leave','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000,'中国')");
            statement.execute("INSERT INTO site_event VALUES ('3','v2','s2','pageview','/create','',NULL,CURRENT_TIMESTAMP,NULL,NULL,'美国')");
            statement.execute("INSERT INTO site_event VALUES ('4','v2','s2','page_leave','/create','',NULL,CURRENT_TIMESTAMP,NULL,12000,'美国')");
            statement.execute("INSERT INTO site_event VALUES ('5','v3','s3','page_leave','/create','',NULL,CURRENT_TIMESTAMP,NULL,20000,'日本')");
            statement.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
            statement.execute("INSERT INTO music_work VALUES ('w1','creator-1','r','o','s','m','style','Title',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO payment_order VALUES ('o1','creator-1','starter-CNY',99.00,'CNY',1000,NULL,'PAID',CURRENT_TIMESTAMP)");
        }

        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString()))
                .thenReturn(Optional.of(new IdentityApi.GoogleIdentity("hash", "jiang@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        var jdbcProvider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-1"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));

        var body = new AnalyticsViewController(identity, jdbcProvider, environment, new AnalyticsDetailService(new JdbcTemplate(dataSource))).summary(30).getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertFalse(body.containsKey("error"), "四条统计 SQL 必须全部在真实数据库上执行成功");
        assertEquals(5L, ((Map<String, Object>) body.get("totals")).get("pv"));
        assertEquals(2L, ((Map<String, Object>) body.get("totals")).get("pageviews"), "真实 PV 只数 pageview，不把离开事件算进去");
        assertEquals(3L, ((Map<String, Object>) body.get("totals")).get("uv"));
        assertFalse(((List<?>) body.get("daily")).isEmpty());
        assertFalse(((List<?>) body.get("pages")).isEmpty());
        var userRow = (Map<String, Object>) ((List<?>) body.get("users")).get(0);
        assertEquals("jiang@example.com", userRow.get("email"), "登录账号一眼可读，不再只给 creatorId");
        assertEquals("Jiang", userRow.get("name"));
        assertEquals("GOOGLE", userRow.get("accountType"));
        assertEquals(1L, userRow.get("pageviews"), "用户访问次数同样只数 pageview");

        // 停留时长按页面从 page_leave 聚合：pageview 行上的时长不能参与平均，也不能把页面算成 0。
        var pages = (List<?>) body.get("pages");
        var landing = (Map<String, Object>) pages.stream().filter(row -> "/".equals(((Map<String, Object>) row).get("pagePath"))).findFirst().orElseThrow();
        var create = (Map<String, Object>) pages.stream().filter(row -> "/create".equals(((Map<String, Object>) row).get("pagePath"))).findFirst().orElseThrow();
        assertEquals(5000L, landing.get("avgDurationMs"));
        assertEquals(1L, landing.get("samples"));
        assertEquals(16000L, create.get("avgDurationMs"));
        assertEquals(2L, create.get("samples"));
        assertEquals(12333L, ((Map<String, Object>) body.get("totals")).get("avgDurationMs"));
        assertEquals(5000L, ((Map<String, Object>) ((List<?>) body.get("users")).get(0)).get("avgDurationMs"));

        // 国家分布：聚合已解析出国家且非匿名化的行；同一访客多次访问按 visitor_id 去重。
        var countries = (List<?>) body.get("countries");
        assertNotNull(countries, "countries 聚合段必须存在");
        assertEquals(3, countries.size(), "应聚合出 中国/美国/日本 三个国家");
        var china = (Map<String, Object>) countries.stream()
                .filter(c -> "中国".equals(((Map<String, Object>) c).get("country"))).findFirst().orElseThrow();
        assertEquals(2L, china.get("hits"), "中国 2 次访问");
        assertEquals(1L, china.get("visitors"), "中国 同一访客（v1）去重为 1");
    }

    /** W7：页面均值必须和用户明细一样排除负时长，不能把异常值算进平均或样本。 */
    @Test @SuppressWarnings("unchecked")
    void negativeDurationsAreExcludedFromPageAverages() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_negative_dwell;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), recipient VARCHAR(80), occasion VARCHAR(32), story TEXT, message VARCHAR(500), style VARCHAR(80), title VARCHAR(100), version BIGINT, created_at TIMESTAMP(6), updated_at TIMESTAMP(6))");
            statement.execute("CREATE TABLE payment_order (id VARCHAR(36) PRIMARY KEY, creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, provider_link_id VARCHAR(128), status VARCHAR(24), created_at TIMESTAMP(6))");
            statement.execute("INSERT INTO site_event VALUES ('p1','v1','s1','pageview','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',NULL,NULL)");
            statement.execute("INSERT INTO site_event VALUES ('p2','v1','s1','page_leave','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',10000,NULL)");
            statement.execute("INSERT INTO site_event VALUES ('p3','v1','s1','page_leave','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',-5000,NULL)");
            statement.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
        }
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.of(
                new IdentityApi.GoogleIdentity("hash", "jiang@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        var jdbcProvider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-1"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));

        var body = new AnalyticsViewController(identity, jdbcProvider, environment,
                new AnalyticsDetailService(new JdbcTemplate(dataSource))).summary(30).getBody();
        var pages = (List<?>) body.get("pages");
        var row = (Map<String, Object>) pages.get(0);
        assertEquals("/create", row.get("pagePath"));
        assertEquals(10000L, row.get("avgDurationMs"), "负时长不能拉低页面均值");
        assertEquals(1L, row.get("samples"), "负时长不能计入有效样本");
    }

    /** 空字符串 creator_id 是埋点未解析出归属的脏行，不能出现在用户列表里拖垮前端响应契约。 */
    @Test @SuppressWarnings("unchecked")
    void emptyCreatorIdIsExcludedFromUsers() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_empty_creator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), recipient VARCHAR(80), occasion VARCHAR(32), story TEXT, message VARCHAR(500), style VARCHAR(80), title VARCHAR(100), version BIGINT, created_at TIMESTAMP(6), updated_at TIMESTAMP(6))");
            statement.execute("CREATE TABLE payment_order (id VARCHAR(36) PRIMARY KEY, creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, provider_link_id VARCHAR(128), status VARCHAR(24), created_at TIMESTAMP(6))");
            statement.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000,NULL)");
            statement.execute("INSERT INTO site_event VALUES ('2','v2','s2','pageview','/','',NULL,CURRENT_TIMESTAMP,'',5000,NULL)");
            statement.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
        }
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.of(
                new IdentityApi.GoogleIdentity("hash", "jiang@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        var jdbcProvider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-1"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));

        var body = new AnalyticsViewController(identity, jdbcProvider, environment,
                new AnalyticsDetailService(new JdbcTemplate(dataSource))).summary(30).getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("error"));
        var users = (List<?>) body.get("users");
        assertEquals(1, users.size(), "空 creator_id 不能被聚合成一个用户");
        assertEquals("creator-1", ((Map<String, Object>) users.get(0)).get("creatorId"));
    }

    /**
     * 隐私治理方案 §2.B.6 / §15.1：匿名化哨兵值行（visitor_id/session_id='ANONYMIZED'）不得污染 UV/sessions/PV。
     * 含 2 个真实访客 + 3 个匿名化行；UV 必须为 2（哨兵值多行只贡献 0），绝不能误算为 3。
     */
    @Test @SuppressWarnings("unchecked")
    void anonymizedSentinelRowsExcludedFromUvAndPages() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_anon_exclude;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
            s.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), recipient VARCHAR(80), occasion VARCHAR(32), story TEXT, message VARCHAR(500), style VARCHAR(80), title VARCHAR(100), version BIGINT, created_at TIMESTAMP(6), updated_at TIMESTAMP(6))");
            s.execute("CREATE TABLE payment_order (id VARCHAR(36) PRIMARY KEY, creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, provider_link_id VARCHAR(128), status VARCHAR(24), created_at TIMESTAMP(6))");
            // 两个真实访客
            s.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000,NULL)");
            s.execute("INSERT INTO site_event VALUES ('2','v1','s1','page_leave','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000,NULL)");
            s.execute("INSERT INTO site_event VALUES ('3','v2','s2','pageview','/create','',NULL,CURRENT_TIMESTAMP,'creator-2',8000,NULL)");
            s.execute("INSERT INTO site_event VALUES ('4','v2','s2','page_leave','/create','',NULL,CURRENT_TIMESTAMP,'creator-2',8000,NULL)");
            // 三个匿名化哨兵值行（visitor_id/session_id = 'ANONYMIZED'）
            s.execute("INSERT INTO site_event VALUES ('5','ANONYMIZED','ANONYMIZED','pageview','/','',NULL,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
            s.execute("INSERT INTO site_event VALUES ('6','ANONYMIZED','ANONYMIZED','page_leave','/','',NULL,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
            s.execute("INSERT INTO site_event VALUES ('7','ANONYMIZED','ANONYMIZED','pageview','/create','',NULL,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
            s.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
        }
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.of(
                new IdentityApi.GoogleIdentity("hash", "jiang@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        var jdbcProvider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-1"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));

        var body = new AnalyticsViewController(identity, jdbcProvider, environment,
                new AnalyticsDetailService(new JdbcTemplate(dataSource))).summary(30).getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("error"));
        var totals = (Map<String, Object>) body.get("totals");
        assertEquals(2L, totals.get("uv"), "匿名化哨兵值多行只贡献 0 个真实访客，UV 必须为真实访客数 2，绝不误算为 3");
        assertEquals(2L, totals.get("sessions"), "sessions 同理必须排除哨兵值");
        // pv = COUNT(*) 是全部事件行：真实 4 行（2 pageview + 2 page_leave），3 条匿名行须被排除（否则为 7）。
        assertEquals(4L, totals.get("pv"), "COUNT(*) 必须排除匿名化行（真实 4 行）");
        assertEquals(2L, totals.get("pageviews"), "仅 event='pageview' 的真实行为 2 条，匿名 pageview 须被排除");
        var pages = (List<?>) body.get("pages");
        var landing = (Map<String, Object>) pages.stream().filter(r -> "/".equals(((Map<String, Object>) r).get("pagePath"))).findFirst().orElseThrow();
        var create = (Map<String, Object>) pages.stream().filter(r -> "/create".equals(((Map<String, Object>) r).get("pagePath"))).findFirst().orElseThrow();
        assertEquals(1L, landing.get("pv"), "/ 页 PV 仅含真实访客 v1");
        assertEquals(1L, create.get("pv"), "/create 页 PV 仅含真实访客 v2");
    }

    /** 仅含匿名化行时 UV 必须为 0，绝不能误算为 1——即哨兵值不能冒充一个真实访客。 */
    @Test @SuppressWarnings("unchecked")
    void sentinelOnlyRowsYieldZeroUv() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_sentinel_only;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
            s.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), recipient VARCHAR(80), occasion VARCHAR(32), story TEXT, message VARCHAR(500), style VARCHAR(80), title VARCHAR(100), version BIGINT, created_at TIMESTAMP(6), updated_at TIMESTAMP(6))");
            s.execute("CREATE TABLE payment_order (id VARCHAR(36) PRIMARY KEY, creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, provider_link_id VARCHAR(128), status VARCHAR(24), created_at TIMESTAMP(6))");
            s.execute("INSERT INTO site_event VALUES ('1','ANONYMIZED','ANONYMIZED','pageview','/','',NULL,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
            s.execute("INSERT INTO site_event VALUES ('2','ANONYMIZED','ANONYMIZED','pageview','/','',NULL,CURRENT_TIMESTAMP,NULL,NULL,NULL)");
            s.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
        }
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.of(
                new IdentityApi.GoogleIdentity("hash", "jiang@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        var jdbcProvider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbcProvider.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-1"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));

        var body = new AnalyticsViewController(identity, jdbcProvider, environment,
                new AnalyticsDetailService(new JdbcTemplate(dataSource))).summary(30).getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("error"));
        var totals = (Map<String, Object>) body.get("totals");
        assertEquals(0L, totals.get("uv"), "仅匿名化行时 UV 必须为 0，绝不能误算为 1");
        assertEquals(0L, totals.get("pv"), "仅匿名化行时 PV 必须为 0");
    }
}
