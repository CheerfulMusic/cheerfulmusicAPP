package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.geo.GeoIpResolver;
import jakarta.servlet.http.Cookie;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

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

    private static IdentityApi identity(String creatorId) {
        var identity = mock(IdentityApi.class);
        when(identity.resolve(any())).thenReturn(Optional.ofNullable(creatorId));
        return identity;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GeoIpResolver> noGeoIp() {
        var provider = (ObjectProvider<GeoIpResolver>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<IdentityApi> identities(IdentityApi value) {
        var provider = (ObjectProvider<IdentityApi>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static Environment environment(boolean enabled) {
        var environment = mock(Environment.class);
        when(environment.getProperty(eq("qingfeng.analytics.enabled"), eq(Boolean.class), eq(false))).thenReturn(enabled);
        return environment;
    }

    @Test void unknownEventIsRejected() {
        assertNull(SiteEventController.sanitize(request("drop_all_tables")));
        assertNull(SiteEventController.sanitize(null));
        assertNull(SiteEventController.sanitize(new SiteEventController.EventRequest(null, "/", "", "", "", null)));
        assertNotNull(SiteEventController.sanitize(request("pageview")));
        assertNotNull(SiteEventController.sanitize(request("page_leave")));
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

    @Test void durationIsClampedAndRejectsGarbage() {
        assertEquals(1500L, SiteEventController.durationMs(Map.of("durationMs", 1500)));
        assertEquals(1500L, SiteEventController.durationMs(Map.of("durationMs", "1500")));
        assertNull(SiteEventController.durationMs(Map.of("durationMs", -1)));
        assertNull(SiteEventController.durationMs(Map.of("durationMs", 99_999_999L)));
        assertNull(SiteEventController.durationMs(Map.of("durationMs", "not-a-number")));
        assertNull(SiteEventController.durationMs(null));
    }

    @Test void creatorIdOnlyComesFromSessionCookie() {
        var withCookie = new MockHttpServletRequest();
        withCookie.setCookies(new Cookie("QF_SESSION", "tok"));
        assertEquals("creator-9", SiteEventController.creatorId(identity("creator-9"), withCookie));
        assertEquals("", SiteEventController.creatorId(identity("creator-9"), new MockHttpServletRequest()));
        assertEquals("", SiteEventController.creatorId(null, withCookie));
    }

    @Test void disabledConfigDoesNotWrite() {
        var template = mock(JdbcTemplate.class);
        var controller = new SiteEventController(provider(template), identities(identity("")), environment(false), noGeoIp());
        assertEquals(204, controller.collect(request("pageview"), new MockHttpServletRequest()).getStatusCode().value());
        verifyNoInteractions(template);
    }

    @Test void absentConfigDefaultsToDisabled() {
        var template = mock(JdbcTemplate.class);
        var controller = new SiteEventController(provider(template), identities(identity("")), new org.springframework.core.env.StandardEnvironment(), noGeoIp());
        assertEquals(204, controller.collect(request("pageview"), new MockHttpServletRequest()).getStatusCode().value());
        verifyNoInteractions(template);
    }

    @Test void missingDatasourceIsSilent() {
        @SuppressWarnings("unchecked") var absent = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        var controller = new SiteEventController(absent, identities(identity("")), environment(true), noGeoIp());
        var servlet = new MockHttpServletRequest();
        assertDoesNotThrow(() -> controller.collect(request("pageview"), servlet));
        assertEquals(204, controller.collect(request("pageview"), servlet).getStatusCode().value());
    }

    @Test void databaseFailureNeverEscapes() {
        var template = mock(JdbcTemplate.class);
        when(template.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db down"));
        var controller = new SiteEventController(provider(template), identities(identity("")), environment(true), noGeoIp());
        var servlet = new MockHttpServletRequest();
        assertDoesNotThrow(() -> controller.collect(request("pageview"), servlet));
        assertEquals(204, controller.collect(request("pageview"), servlet).getStatusCode().value());
        verify(template, atLeastOnce()).update(eq(SiteEventController.INSERT_SQL), any(Object[].class));
    }

    @Test void validEventCallsInsertOnce() {
        var template = mock(JdbcTemplate.class);
        var controller = new SiteEventController(provider(template), identities(identity("")), environment(true), noGeoIp());
        assertEquals(204, controller.collect(request("pageview"), new MockHttpServletRequest()).getStatusCode().value());
        verify(template, times(1)).update(eq(SiteEventController.INSERT_SQL), any(Object[].class));
    }

    @Test void rowCarriesOnlySanitizedValues() {
        var clean = SiteEventController.sanitize(new SiteEventController.EventRequest(
                "page_leave", "/gift/" + "a".repeat(43) + "?code=SECRET",
                "https://ref.example/x?q=1", "visitor-1", "session-1", Map.of("route", "gift", "durationMs", 1500)));
        var row = SiteEventController.row(clean, SiteEventController.payloadJson(clean.payload()), "creator-9", 1500L, null);
        assertEquals("visitor-1", row[1]);
        assertEquals("session-1", row[2]);
        assertEquals("page_leave", row[3]);
        assertEquals("/gift/:token", row[4]);
        assertEquals("https://ref.example/x", row[5]);
        assertNotNull(row[7]);
        assertEquals("creator-9", row[8]);
        assertEquals(1500L, row[9]);
        assertNull(row[10], "国家列默认不存原始 IP，由服务端派生；单测无 resolver 时为 null");
    }

    @Test void payloadDropsUnknownKeys() {
        assertNull(SiteEventController.payloadJson(Map.of("story", "SECRET", "email", "a@b.c")));
        assertEquals("{\"route\":\"gift\"}", SiteEventController.payloadJson(Map.of("route", "gift", "token", "SECRET")));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GeoIpResolver> geoProvider(GeoIpResolver resolver) {
        var provider = (ObjectProvider<GeoIpResolver>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return provider;
    }

    /** 服务端从请求头解析真实 IP 并派生国家，写入 country 列（第 11 列）；不向前端/库暴露原始 IP。 */
    @Test void countryIsResolvedFromRequestIpAndStored() {
        var template = mock(JdbcTemplate.class);
        var resolver = mock(GeoIpResolver.class);
        when(resolver.resolveCountry("203.0.113.9")).thenReturn("中国");
        var controller = new SiteEventController(provider(template), identities(identity("")), environment(true), geoProvider(resolver));
        var servlet = new MockHttpServletRequest();
        servlet.addHeader("X-Forwarded-For", "203.0.113.9");
        controller.collect(request("pageview"), servlet);
        var captor = ArgumentCaptor.forClass(Object[].class);
        verify(template).update(eq(SiteEventController.INSERT_SQL), captor.capture());
        assertEquals("中国", captor.getValue()[10], "country 列应写入派生国家");
    }
}
