package com.qingfeng.music.server.invite;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteGateServiceTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String CODE = "BETA-2026";
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private static InviteGateSettings settings() {
        return new InviteGateSettings(true, List.of(CODE), KEY, Duration.ofDays(7), 10, false);
    }

    private static MockHttpServletRequest requestWith(String value) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(InviteGateService.COOKIE, value));
        return request;
    }

    @Test
    void enabledGateRequiresCodesKeyAndSaneLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new InviteGateSettings(true, List.of(), KEY, Duration.ofDays(7), 10, false));
        assertThrows(IllegalArgumentException.class,
                () -> new InviteGateSettings(true, List.of(CODE), "short", Duration.ofDays(7), 10, false));
        assertThrows(IllegalArgumentException.class,
                () -> new InviteGateSettings(true, List.of(CODE), KEY, Duration.ofDays(31), 10, false));
        assertThrows(IllegalArgumentException.class,
                () -> new InviteGateSettings(true, List.of(CODE), KEY, Duration.ofDays(7), 0, false));
        assertEquals(1, settings().codes().size());
    }

    @Test
    void signsAndVerifiesOnlyConfiguredCodes() {
        var service = new InviteGateService(settings(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertNull(service.admit("wrong-code"));
        String cookie = service.admit("  " + CODE + "  ");
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("v1."));
        assertEquals(NOW.plus(Duration.ofDays(7)).getEpochSecond(), service.expiresAt(requestWith(cookie)));
    }

    @Test
    void rejectsTamperedAndExpiredCookies() {
        var issued = new InviteGateService(settings(), Clock.fixed(NOW, ZoneOffset.UTC));
        String cookie = issued.admit(CODE);
        assertNotNull(cookie);
        assertEquals(0, issued.expiresAt(requestWith(cookie.substring(0, cookie.length() - 2) + "xx")));
        assertEquals(0, issued.expiresAt(requestWith("v1.123.not-a-signature")));

        var later = new InviteGateService(settings(), Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC));
        assertEquals(0, later.expiresAt(requestWith(cookie)));
    }

    @Test
    void disabledGateNeverIssuesOrAcceptsAccess() {
        var service = new InviteGateService(
                new InviteGateSettings(false, List.of(), "", Duration.ofDays(7), 10, false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertNull(service.admit(CODE));
        assertEquals(0, service.expiresAt(requestWith("v1.anything")));
    }

    @Test
    void limitsAttemptsPerRemoteAddress() {
        var service = new InviteGateService(settings(), Clock.fixed(NOW, ZoneOffset.UTC));
        for (int attempt = 0; attempt < 10; attempt++) assertTrue(service.allowAttempt("10.0.0.1"));
        assertFalse(service.allowAttempt("10.0.0.1"));
        assertTrue(service.allowAttempt("10.0.0.2"));
    }
}
