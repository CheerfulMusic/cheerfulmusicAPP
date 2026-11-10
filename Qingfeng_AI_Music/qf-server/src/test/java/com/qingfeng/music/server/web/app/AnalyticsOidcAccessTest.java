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

/** OIDC/Logto 邮箱账号也要能进看板，且仍然 fail-closed。 */
class AnalyticsOidcAccessTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test @SuppressWarnings("unchecked")
    void oidcEmailInWhitelistIsAllowedAndOthersAreNot() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:analytics_oidc_access;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40))");
            statement.execute("INSERT INTO creator_oidc_identity VALUES ('creator-oidc','snow@cheerfulmusic.co.uk',NULL)");
        }
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(Optional.empty());
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq("")))
                .thenReturn("jiangxueer.snow@gmail.com,yt0514255@gmail.com,snow@cheerfulmusic.co.uk");
        var jdbc = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(new JdbcTemplate(dataSource));
        var controller = new AnalyticsViewController(identity, jdbc, environment, new AnalyticsDetailService(new JdbcTemplate(dataSource)));

        assertTrue(controller.allowed("creator-oidc"));
        assertFalse(controller.allowed("creator-other"));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal("creator-oidc"), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));
        var body = controller.access().getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertEquals("snow@cheerfulmusic.co.uk", body.get("viewer"));

        var strictEnvironment = mock(Environment.class);
        when(strictEnvironment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq("")))
                .thenReturn("yt0514255@gmail.com");
        assertFalse(new AnalyticsViewController(identity, jdbc, strictEnvironment, new AnalyticsDetailService(new JdbcTemplate(dataSource))).allowed("creator-oidc"));

        var emptyEnvironment = mock(Environment.class);
        when(emptyEnvironment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("");
        assertFalse(new AnalyticsViewController(identity, jdbc, emptyEnvironment, new AnalyticsDetailService(new JdbcTemplate(dataSource))).allowed("creator-oidc"));
    }
}
