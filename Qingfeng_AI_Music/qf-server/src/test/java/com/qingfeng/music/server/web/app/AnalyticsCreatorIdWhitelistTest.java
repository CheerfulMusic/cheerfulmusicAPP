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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 纯手机号登录账号（没有任何已验证邮箱）的看板授权回归。
 * 新增 qingfeng.analytics.viewer-creator-ids 显式白名单；邮箱白名单原有语义不变。
 */
class AnalyticsCreatorIdWhitelistTest {
    private static final String PHONE_USER = "7e68cf91-c234-45fb-b76c-c39662e61817";

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private JdbcTemplate h2() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:analytics_ids_" + java.util.UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), subject_hash VARCHAR(64), email VARCHAR(320), display_name VARCHAR(200))");
            s.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
        }
        return new JdbcTemplate(ds);
    }

    @SuppressWarnings("unchecked")
    private AnalyticsViewController controller(String emails, String ids, JdbcTemplate jdbc, boolean identityDown) {
        var identity = mock(IdentityApi.class);
        if (identityDown) {
            when(identity.googleIdentity(anyString())).thenThrow(new RuntimeException("idp down"));
        } else {
            when(identity.googleIdentity(anyString())).thenReturn(Optional.empty());
        }
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn(emails);
        // null 模拟「私有配置未提供新键」，必须保持 fail-closed 且不影响邮箱白名单
        when(environment.getProperty(eq("qingfeng.analytics.viewer-creator-ids"), eq(""))).thenReturn(ids);
        var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new AnalyticsViewController(identity, provider, environment, new AnalyticsDetailService(jdbc));
    }

    private static void login(String creatorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CreatorPrincipal(creatorId), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));
    }

    @Test void creatorIdWhitelistAllowsPhoneOnlyAccountWithoutAnyEmail() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email, phone_masked) VALUES (?, NULL, ?)",
            PHONE_USER, "****4418");
        // 身份服务异常也要放行：显式 ID 授权不依赖邮箱查询
        assertTrue(controller("", PHONE_USER, jdbc, true).allowed(PHONE_USER));
    }

    @Test void creatorIdMatchIsCaseInsensitiveAndTrimmed() throws Exception {
        var jdbc = h2();
        assertTrue(controller("", "  " + PHONE_USER.toUpperCase() + "  ", jdbc, true).allowed(PHONE_USER));
    }

    @Test void nonWhitelistedCreatorWithoutEmailStaysDenied() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email, phone_masked) VALUES (?, NULL, ?)",
            PHONE_USER, "****4418");
        assertFalse(controller("someone@example.com", "", jdbc, false).allowed(PHONE_USER));
        assertFalse(controller("", "another-creator-id", jdbc, false).allowed(PHONE_USER));
    }

    @Test void bothWhitelistsEmptyDeniesEveryone() throws Exception {
        assertFalse(controller("", "", h2(), false).allowed(PHONE_USER));
    }

    @Test void missingNewConfigKeyDoesNotBreakExistingEmailWhitelist() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email) VALUES (?, ?)",
            PHONE_USER, "verified@example.com");
        assertTrue(controller("verified@example.com", null, jdbc, false).allowed(PHONE_USER));
        assertFalse(controller("other@example.com", null, jdbc, false).allowed(PHONE_USER));
    }

    @Test void accessShowsMaskedPhoneAsViewerWhenAccountHasNoEmail() throws Exception {
        var jdbc = h2();
        jdbc.update("INSERT INTO creator_oidc_identity (creator_id, email, phone_masked) VALUES (?, NULL, ?)",
            PHONE_USER, "****4418");
        login(PHONE_USER);
        var body = controller("", PHONE_USER, jdbc, false).access().getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertEquals("****4418", body.get("viewer"));
    }
}
