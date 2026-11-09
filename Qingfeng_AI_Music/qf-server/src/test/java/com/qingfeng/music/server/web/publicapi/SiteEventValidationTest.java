package com.qingfeng.music.server.web.publicapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SiteEventValidationTest {
    private static SiteEventController.EventRequest request(String event) {
        return new SiteEventController.EventRequest(event, "/create", "", "visitor-1", "session-1", Map.of("route", "studio"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JdbcTemplate> provider(JdbcTemplate template) {
        var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private static Environment environment(boolean enabled) {
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.enabled"), eq(Boolean.class), eq(true))).thenReturn(enabled);
        return environment;
    }

    @Test void unknownEventIsRejected() {
        assertNull(SiteEventController.sanitize(request("drop_all_tables")));
        assertNull(SiteEventController.sanitize(null));
        assertNull(SiteEventController.sanitize(new SiteEventController.EventRequest(null, "/", "", "", "", null)));
        assertNotNull(SiteEventController.sanitize(request("pageview")));
    }

    @Test void pathIsStrippedOfTokensQueriesAndIds() {
        var clean = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/gift/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ?code=SECRET#frag",
                "", "v", "s", null));
        assertEquals("/gift/:token", clean.pagePath());
        var work = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/works/12345678-1234-1234-1234-123456789012?tab=lyrics", "", "v", "s", null));
        assertEquals("/works/:id", work.pagePath());
        assertEquals("", SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "https://evil.example/x", "", "v", "s", null)).pagePath());
    }

    @Test void referrerKeepsOriginOnly() {
        var clean = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/", "https://www.google.com/search?q=secret&code=x", "v", "s", null));
        assertEquals("https://www.google.com/search", clean.referrer());
        assertEquals("", SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/", "javascript:alert(1)", "v", "s", null)).referrer());
    }

    @Test void invalidAnonymousIdsAreBlank() {
        var clean = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/", "", "'; DROP TABLE creator; --", "ok_id-1", null));
        assertEquals("", clean.visitorId());
        assertEquals("ok_id-1", clean.sessionId());
    }

    @Test void disabledConfigDoesNotWrite() {
        var template = mock(JdbcTemplate.class);
        var controller = new SiteEventController(provider(template), environment(false));
        assertEquals(204, controller.collect(request("pageview")).getStatusCode().value());
        verifyNoInteractions(template);
    }

    @Test void missingDatasourceIsSilent() {
        @SuppressWarnings("unchecked") var absent = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        var controller = new SiteEventController(absent, environment(true));
        assertDoesNotThrow(() -> controller.collect(request("pageview")));
        assertEquals(204, controller.collect(request("pageview")).getStatusCode().value());
    }

    @Test void databaseFailureNeverEscapes() {
        var template = mock(JdbcTemplate.class);
        when(template.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db down"));
        var controller = new SiteEventController(provider(template), environment(true));
        assertDoesNotThrow(() -> controller.collect(request("pageview")));
        assertEquals(204, controller.collect(request("pageview")).getStatusCode().value());
        // 确认异常分支真的被执行，而不是 mock 没匹配上导致的假阳性通过。
        verify(template, atLeastOnce()).update(eq(SiteEventController.INSERT_SQL), any(Object[].class));
    }

    @Test void validEventCallsInsertOnce() {
        var template = mock(JdbcTemplate.class);
        var controller = new SiteEventController(provider(template), environment(true));
        assertEquals(204, controller.collect(request("pageview")).getStatusCode().value());
        verify(template, times(1)).update(eq(SiteEventController.INSERT_SQL), any(Object[].class));
    }

    @Test void rowCarriesOnlySanitizedValues() {
        var clean = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "pageview", "/gift/" + "a".repeat(43) + "?code=SECRET",
                "https://ref.example/x?q=1", "visitor-1", "session-1", Map.of("route", "gift")));
        var row = SiteEventController.row(clean, SiteEventController.payloadJson(clean.payload()));
        assertEquals("visitor-1", row[1]);
        assertEquals("session-1", row[2]);
        assertEquals("pageview", row[3]);
        assertEquals("/gift/:token", row[4]);
        assertEquals("https://ref.example/x", row[5]);
        assertEquals("{\"route\":\"gift\"}", row[6]);
        assertNotNull(row[7]);
    }

    @Test void payloadDropsUnknownKeys() {
        assertNull(SiteEventController.payloadJson(Map.of("story", "SECRET", "email", "a@b.c")));
        assertEquals("{\"route\":\"gift\"}", SiteEventController.payloadJson(Map.of("route", "gift", "token", "SECRET")));
    }
}
