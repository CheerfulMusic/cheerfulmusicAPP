package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 页面下钻：真实 H2 上跑 SQL，同时覆盖权限、口径与时间窗口。 */
class AnalyticsPageUsersSqlTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private AnalyticsViewController controller;

    @BeforeEach @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_page_users;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                    + "event VARCHAR(50), page_path VARCHAR(512), referrer VARCHAR(512), payload TEXT, "
                    + "created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT)");
            statement.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
            statement.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
        }
        jdbc.update("INSERT INTO creator_google_identity VALUES ('creator-1','a@example.com','Alice')");
        jdbc.update("INSERT INTO creator_oidc_identity VALUES ('creator-2','b@example.com','138****0000','Bob')");
        jdbc.update("INSERT INTO creator_oidc_identity VALUES ('creator-4',NULL,'****1111',NULL)");

        var now = Timestamp.from(Instant.now());
        var stale = Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS));
        leave("e1", "/create", "creator-1", 3000, now);
        leave("e2", "/create", "creator-1", 3000, now);
        leave("e3", "/create", "creator-2", 6000, now);
        leave("e4", "/create", "creator-3", 1000, now);
        leave("e9", "/create", "creator-4", 4500, now);
        leave("e5", "/create", "creator-1", null, now);
        leave("e6", "/create", "creator-1", 999000, stale);
        leave("e7", "/works", "creator-1", 99000, now);
        jdbc.update("INSERT INTO site_event VALUES ('e8','v','s','pageview','/create','',NULL,?, 'creator-1', 50000)", now);

        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.empty());
        when(identity.googleIdentity(eq("creator-1"))).thenReturn(Optional.of(
                new IdentityApi.GoogleIdentity("hash", "a@example.com", "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("a@example.com");
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        controller = new AnalyticsViewController(identity, provider, environment, new AnalyticsDetailService(jdbc));
        login("creator-1");
    }

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    private void leave(String id, String page, String creator, Integer duration, Timestamp at) {
        jdbc.update("INSERT INTO site_event VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, "v-" + id, "s-" + id, "page_leave", page, "", null, at, creator,
                duration == null ? null : duration.longValue());
    }

    private static void login(String creatorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal(creatorId), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));
    }

    @Test @SuppressWarnings("unchecked")
    void groupsDwellPerUserOnTheRequestedPageOnly() {
        var body = controller.pageUsers("/create", 30).getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertEquals("/create", body.get("pagePath"));
        assertEquals(30, body.get("days"));

        var users = (List<AnalyticsDetailService.UserDwell>) body.get("users");
        assertEquals(4, users.size(), "只统计该页面、时间窗口内、有有效停留时长的用户");
        assertEquals("creator-1", users.get(0).creatorId(), "按有效停留次数排序");
        assertEquals("GOOGLE", users.get(0).accountType());
        assertEquals("Alice", users.get(0).name());
        assertEquals("a@example.com", users.get(0).email());
        assertEquals(3000L, users.get(0).avgDurationMs());
        assertEquals(2L, users.get(0).samples(), "pageview 行的时长与超窗记录都不计入");
        assertEquals("creator-2", users.get(1).creatorId());
        assertEquals("OIDC_EMAIL", users.get(1).accountType());
        assertEquals("b@example.com", users.get(1).email());
        assertEquals("138****0000", users.get(1).phoneMasked());
        assertEquals(6000L, users.get(1).avgDurationMs());
        assertEquals("creator-4", users.get(2).creatorId());
        assertEquals("OIDC_PHONE", users.get(2).accountType(), "只绑定了手机号的登录用户不能显示成游客");
        assertEquals("****1111", users.get(2).phoneMasked());
        assertNull(users.get(2).email());
        assertEquals("creator-3", users.get(3).creatorId());
        assertEquals("GUEST", users.get(3).accountType(), "没有绑定任何身份的就是游客会话");
        assertNull(users.get(3).email());
    }

    @Test @SuppressWarnings("unchecked")
    void otherPagesAreNotMixedIn() {
        var users = (List<AnalyticsDetailService.UserDwell>) controller.pageUsers("/works", 30).getBody().get("users");
        assertEquals(1, users.size());
        assertEquals(99000L, users.get(0).avgDurationMs());
        assertEquals(1L, users.get(0).samples());
    }

    @Test
    void windowIsClampedLikeTheSummary() {
        assertEquals(90, controller.pageUsers("/create", 999).getBody().get("days"));
        assertEquals(1, controller.pageUsers("/create", 0).getBody().get("days"));
    }

    @Test
    void invalidPagePathIsRejected() {
        var missingSlash = controller.pageUsers("create", 30);
        assertEquals(400, missingSlash.getStatusCode().value());
        var blank = controller.pageUsers("  ", 30);
        assertEquals(400, blank.getStatusCode().value());
    }

    @Test @SuppressWarnings("unchecked")
    void userPagesGroupsDwellForOneCreator() {
        var body = controller.userPages("creator-1", 30).getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertEquals("creator-1", body.get("creatorId"));
        var rows = (List<AnalyticsDetailService.PageDwell>) body.get("pages");
        assertEquals(2, rows.size(), "只取这个用户自己的页面");
        assertEquals("/create", rows.get(0).pagePath(), "按有效停留次数排序");
        assertEquals(3000L, rows.get(0).avgDurationMs());
        assertEquals(2L, rows.get(0).samples());
        assertEquals("/works", rows.get(1).pagePath());
        assertEquals(1L, rows.get(1).samples());
    }

    @Test
    void invalidCreatorIdIsRejected() {
        assertEquals(400, controller.userPages("../etc/passwd", 30).getStatusCode().value());
        assertEquals(400, controller.userPages("  ", 30).getStatusCode().value());
    }

    @Test
    void nonWhitelistedViewerGetsNoRows() {
        login("creator-2");
        var body = controller.pageUsers("/create", 30).getBody();
        assertNotNull(body);
        assertEquals(false, body.get("allowed"));
        assertFalse(body.containsKey("users"), "未授权时不能返回任何用户明细");
        assertFalse(controller.userPages("creator-1", 30).getBody().containsKey("pages"), "未授权时同样拿不到用户页面明细");
    }
}
