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
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT)");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000)");
            statement.execute("INSERT INTO site_event VALUES ('2','v1','s1','page_leave','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000)");
            statement.execute("INSERT INTO site_event VALUES ('3','v2','s2','pageview','/create','',NULL,CURRENT_TIMESTAMP,NULL,NULL)");
            statement.execute("INSERT INTO site_event VALUES ('4','v2','s2','page_leave','/create','',NULL,CURRENT_TIMESTAMP,NULL,12000)");
            statement.execute("INSERT INTO site_event VALUES ('5','v3','s3','page_leave','/create','',NULL,CURRENT_TIMESTAMP,NULL,20000)");
            statement.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com','Jiang')");
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
    }

    /** W7：页面均值必须和用户明细一样排除负时长，不能把异常值算进平均或样本。 */
    @Test @SuppressWarnings("unchecked")
    void negativeDurationsAreExcludedFromPageAverages() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_negative_dwell;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT)");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("INSERT INTO site_event VALUES ('p1','v1','s1','pageview','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',NULL)");
            statement.execute("INSERT INTO site_event VALUES ('p2','v1','s1','page_leave','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',10000)");
            statement.execute("INSERT INTO site_event VALUES ('p3','v1','s1','page_leave','/create','',NULL,CURRENT_TIMESTAMP,'creator-1',-5000)");
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
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT)");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000)");
            statement.execute("INSERT INTO site_event VALUES ('2','v2','s2','pageview','/','',NULL,CURRENT_TIMESTAMP,'',5000)");
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
}
