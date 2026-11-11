package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Google 与 OIDC/Logto 双身份来源的看板白名单统一口径测试。
 * 核心规则：任一「已验证邮箱」命中白名单即授权（并集口径），避免用户同时绑了两种
 * 身份、白名单只写了其中一个邮箱时被误拒；空白邮箱视为无此来源，外部身份服务异常 fail-closed。
 */
class AnalyticsIdentityWhitelistTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private JdbcTemplate h2() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:analytics_whitelist_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), subject_hash VARCHAR(64), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
        }
        return new JdbcTemplate(ds);
    }

    private AnalyticsViewController controller(String viewers, String googleEmail, JdbcTemplate jdbc) {
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(googleEmail == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new IdentityApi.GoogleIdentity("subject", googleEmail, "Name")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn(viewers);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new AnalyticsViewController(identity, provider, environment, new AnalyticsDetailService(jdbc));
    }

    @Test void googleEmailInWhitelistIsAllowed() throws Exception {
        assertTrue(controller("google@x.com", "google@x.com", h2()).allowed("c1"));
    }

    @Test void oidcEmailInWhitelistIsAllowed() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        assertTrue(controller("oidc@x.com", null, jdbc).allowed("c1"));
    }

    @Test void oidcEmailNullIsDeniedEvenWhenWhitelisted() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1', NULL)");
        assertFalse(controller("oidc@x.com", null, jdbc).allowed("c1"));
    }

    @Test void unverifiedBlankGoogleEmailIsDenied() throws Exception {
        var jdbc = h2(); // 无 OIDC 行
        assertFalse(controller("google@x.com", "   ", jdbc).allowed("c1"));
    }

    @Test void bothEmailsPresentWhitelistMatchesOidcOnly() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_google_identity (creator_id, subject_hash, email, display_name) VALUES ('c1','h','google@x.com','G')");
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        // 白名单只写了 OIDC 邮箱：并集口径仍授权，纠正常见的误拒。
        assertTrue(controller("oidc@x.com", "google@x.com", jdbc).allowed("c1"));
    }

    @Test void bothEmailsPresentWhitelistMatchesGoogleOnly() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_google_identity (creator_id, subject_hash, email, display_name) VALUES ('c1','h','google@x.com','G')");
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        assertTrue(controller("google@x.com", "google@x.com", jdbc).allowed("c1"));
    }

    @Test void bothEmailsPresentWhitelistMatchesNeitherIsDenied() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_google_identity (creator_id, subject_hash, email, display_name) VALUES ('c1','h','google@x.com','G')");
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        assertFalse(controller("other@x.com", "google@x.com", jdbc).allowed("c1"));
    }

    @Test void identityServiceExceptionFailsClosed() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenThrow(new RuntimeException("idp down"));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("oidc@x.com");
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        assertFalse(new AnalyticsViewController(identity, provider, environment, new AnalyticsDetailService(jdbc)).allowed("c1"));
    }

    @Test void emptyWhitelistDeniesEveryone() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        assertFalse(controller("", "google@x.com", jdbc).allowed("c1"));
    }

    @Test void nonWhitelistedUserIsDenied() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES ('c1','oidc@x.com')");
        assertFalse(controller("someoneelse@x.com", "google@x.com", jdbc).allowed("c1"));
    }
}
