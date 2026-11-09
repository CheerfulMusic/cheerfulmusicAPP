package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
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
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40))");
            statement.execute("INSERT INTO site_event VALUES ('1','v1','s1','pageview','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000)");
            statement.execute("INSERT INTO site_event VALUES ('2','v1','s1','page_leave','/','',NULL,CURRENT_TIMESTAMP,'creator-1',5000)");
            statement.execute("INSERT INTO site_event VALUES ('3','v2','s2','pageview','/create','',NULL,CURRENT_TIMESTAMP,NULL,NULL)");
            statement.execute("INSERT INTO creator_google_identity VALUES ('creator-1','jiang@example.com')");
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

        var body = new AnalyticsViewController(identity, jdbcProvider, environment).summary(30).getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertFalse(body.containsKey("error"), "四条统计 SQL 必须全部在真实数据库上执行成功");
        assertEquals(3L, ((Map<String, Object>) body.get("totals")).get("pv"));
        assertEquals(2L, ((Map<String, Object>) body.get("totals")).get("uv"));
        assertFalse(((List<?>) body.get("daily")).isEmpty());
        assertFalse(((List<?>) body.get("pages")).isEmpty());
        assertEquals("jiang@example.com", ((Map<String, Object>) ((List<?>) body.get("users")).get(0)).get("account"));
    }
}
