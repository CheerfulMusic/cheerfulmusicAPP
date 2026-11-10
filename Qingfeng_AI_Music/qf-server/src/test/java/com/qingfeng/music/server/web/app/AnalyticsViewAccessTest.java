package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsViewAccessTest {
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @SuppressWarnings("unchecked")
    private static AnalyticsViewController controller(String viewers, String googleEmail) {
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenReturn(googleEmail == null
                ? Optional.empty()
                : Optional.of(new IdentityApi.GoogleIdentity("subject-hash", googleEmail, "Jiang")));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn(viewers);
        var jdbc = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(jdbc.getIfAvailable()).thenReturn(mock(JdbcTemplate.class));
        return new AnalyticsViewController(identity, jdbc, environment, new AnalyticsDetailService(mock(JdbcTemplate.class)));
    }

    private static void login(String creatorId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal(creatorId), null, List.of(new SimpleGrantedAuthority("ROLE_CREATOR"))));
    }

    @Test void emptyWhitelistDeniesEveryone() {
        login("creator-1");
        assertFalse(controller("", "jiang@example.com").allowed("creator-1"));
    }

    @Test void onlyMatchingGoogleEmailIsAllowed() {
        var controller = controller("jiang@example.com", "jiang@example.com");
        assertTrue(controller.allowed("creator-1"));
        assertFalse(controller.allowed(null));
        assertFalse(controller.allowed("  "));
    }

    @Test void emailMatchIsCaseInsensitive() {
        assertTrue(controller("JIANG@Example.com", " jiang@example.com ").allowed("creator-1"));
    }

    @Test void otherEmailIsDenied() {
        assertFalse(controller("jiang@example.com", "someone@example.com").allowed("creator-1"));
    }

    @Test void accountWithoutGoogleIdentityIsDenied() {
        assertFalse(controller("jiang@example.com", null).allowed("creator-1"));
    }

    @Test void identityLookupFailureFailsClosed() {
        var identity = mock(IdentityApi.class);
        when(identity.googleIdentity(anyString())).thenThrow(new RuntimeException("db down"));
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.viewer-emails"), eq(""))).thenReturn("jiang@example.com");
        @SuppressWarnings("unchecked") var jdbc = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        assertFalse(new AnalyticsViewController(identity, jdbc, environment, new AnalyticsDetailService(mock(JdbcTemplate.class))).allowed("creator-1"));
    }

    @Test void deniedSummaryContainsNoStatistics() {
        login("creator-1");
        var body = controller("jiang@example.com", "someone@example.com").summary(30).getBody();
        assertNotNull(body);
        assertEquals(false, body.get("allowed"));
        assertFalse(body.containsKey("totals"));
        assertFalse(body.containsKey("daily"));
        assertFalse(body.containsKey("users"));
    }

    @Test void deniedAccessEndpointDoesNotLeakViewer() {
        login("creator-1");
        var body = controller("jiang@example.com", "someone@example.com").access().getBody();
        assertNotNull(body);
        assertEquals(false, body.get("allowed"));
        assertFalse(body.containsKey("viewer"));
    }

    @Test void unauthenticatedRequestIsDenied() {
        var body = controller("jiang@example.com", "jiang@example.com").access().getBody();
        assertNotNull(body);
        assertEquals(false, body.get("allowed"));
    }
}
