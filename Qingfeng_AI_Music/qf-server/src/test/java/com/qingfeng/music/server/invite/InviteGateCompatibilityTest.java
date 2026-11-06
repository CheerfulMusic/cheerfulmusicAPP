package com.qingfeng.music.server.invite;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class InviteGateCompatibilityTest {
    private static final String CODE = "QF-TEST-INVITATION-2026";
    private static final String KEY = "test-only-private-signing-key-with-more-than-32-characters";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
    private InviteGateSettings settings(String code, String key) { return new InviteGateSettings(true, List.of(code), key, Duration.ofDays(7), 3, false); }

    @Test void signedAdmissionSurvivesRestartButNotTamperingExpirationOrRotation() {
        var gate = new InviteGateService(settings(CODE, KEY), NOW);
        String token = gate.admit("  " + CODE.toLowerCase() + "  ");
        assertNotNull(token); assertFalse(token.contains(CODE));
        assertTrue(new InviteGateService(settings(CODE, KEY), NOW).expiresAt(token) > 0);
        assertNotEquals(token, gate.admit(CODE));
        String[] pieces = token.split("\\."); pieces[2] = Long.toString(NOW.instant().plusSeconds(600).getEpochSecond());
        assertEquals(0, gate.expiresAt(String.join(".", pieces)));
        assertEquals(0, new InviteGateService(settings(CODE, KEY), Clock.offset(NOW, Duration.ofDays(7))).expiresAt(token));
        assertEquals(0, new InviteGateService(settings("QF-REPLACEMENT-INVITATION", KEY), NOW).expiresAt(token));
        assertEquals(0, new InviteGateService(settings(CODE, KEY + "rotate"), NOW).expiresAt(token));
        for (String invalid : List.of("", "true", "v1.a.b.c.d", token + ".extra", "x".repeat(513))) assertEquals(0, gate.expiresAt(invalid));
        assertNull(gate.admit("wrong")); assertNull(gate.admit("x".repeat(129)));
    }
    @Test void attemptLimitIsBoundedAndDoesNotTrustForwardedHeaders() {
        var gate = new InviteGateService(settings(CODE, KEY), NOW);
        assertTrue(gate.allowAttempt("127.0.0.1")); assertTrue(gate.allowAttempt("127.0.0.1")); assertTrue(gate.allowAttempt("127.0.0.1"));
        assertFalse(gate.allowAttempt("127.0.0.1")); assertTrue(gate.allowAttempt("127.0.0.2"));
    }
    @Test void invalidEnabledConfigurationFailsWithoutDisclosingSecrets() {
        assertThrows(IllegalArgumentException.class, () -> settings("short", KEY));
        assertThrows(IllegalArgumentException.class, () -> settings(CODE, "short"));
        assertThrows(IllegalArgumentException.class, () -> new InviteGateSettings(true, List.of(), KEY, Duration.ofDays(7), 3, false));
    }
    @Test void controllerLimitsWrongGuessesEvenBeforeAnyAdmissionCookieExists() {
        var controller = new InviteGateController(new InviteGateService(settings(CODE, KEY), NOW));
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        for (int index = 0; index < 3; index++) assertEquals(403, controller.enter(request, new InviteGateController.Invitation("incorrect")).getStatusCode().value());
        var limited = controller.enter(request, new InviteGateController.Invitation(CODE));
        assertEquals(429, limited.getStatusCode().value()); assertEquals("60", limited.getHeaders().getFirst("Retry-After"));
    }
    @Test void tlsAdmissionCookieIsSecureHttpOnlyAndNotAnAccountCookie() {
        var controller = new InviteGateController(new InviteGateService(settings(CODE, KEY), NOW));
        var request = new MockHttpServletRequest(); request.setSecure(true); request.setRemoteAddr("127.0.0.1");
        String cookie = controller.enter(request, new InviteGateController.Invitation(CODE)).getHeaders().getFirst("Set-Cookie");
        assertNotNull(cookie); assertTrue(cookie.startsWith(InviteGateService.COOKIE + "="));
        for (String attribute : List.of("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=604800")) assertTrue(cookie.contains(attribute), attribute);
        assertFalse(cookie.contains("QF_SESSION"));
    }
}
