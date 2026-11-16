package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes summary SQL against synthetic H2 data; does not claim real MySQL acceptance. */
class AnalyticsUserSortSqlTest {
    private JdbcTemplate jdbc;
    private AnalyticsViewController controller;
    private final Instant earlier = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    private final Instant recent = earlier.plus(1, ChronoUnit.HOURS);

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:analytics_sort_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE site_event (id VARCHAR(36) PRIMARY KEY, visitor_id VARCHAR(64), session_id VARCHAR(64), "
                + "event VARCHAR(50), page_path VARCHAR(512), payload TEXT, created_at TIMESTAMP(6), creator_id VARCHAR(36), duration_ms BIGINT, country VARCHAR(64))");
        jdbc.execute("CREATE TABLE creator_google_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
        jdbc.execute("CREATE TABLE creator_oidc_identity (creator_id VARCHAR(36), email VARCHAR(320), phone_masked VARCHAR(40), display_name VARCHAR(200))");
        jdbc.execute("CREATE TABLE creator_authing_identity (creator_id VARCHAR(36), email VARCHAR(320), display_name VARCHAR(200))");
        jdbc.execute("CREATE TABLE music_work (id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36), created_at TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE payment_order (creator_id VARCHAR(36), package_id VARCHAR(80), amount DECIMAL(18,2), currency VARCHAR(3), credits BIGINT, status VARCHAR(24), created_at TIMESTAMP(6))");
        var provider = (ObjectProvider<JdbcTemplate>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        var environment = new MockEnvironment().withProperty("qingfeng.analytics.viewer-creator-ids", "viewer");
        controller = new AnalyticsViewController(mock(IdentityApi.class), provider, environment, new AnalyticsDetailService(jdbc));
        login("viewer");
    }

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    private static void login(String id) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CreatorPrincipal(id), null, List.of()));
    }

    private void event(String creator, String type, Instant at) {
        jdbc.update("INSERT INTO site_event(id,visitor_id,session_id,event,page_path,created_at,creator_id,duration_ms) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "visitor-" + creator, "session-" + creator, type, "/create",
                Timestamp.from(at), creator, "page_leave".equals(type) ? 5000L : null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> users(String sort) {
        var response = controller.summary(30, sort);
        assertEquals(200, response.getStatusCode().value());
        var body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("allowed"));
        assertFalse(body.containsKey("error"), "The whole summary query must execute, not fall back to QUERY_FAILED");
        return (List<Map<String, Object>>) body.get("users");
    }

    private List<String> ids(String sort) {
        return users(sort).stream().map(row -> (String) row.get("creatorId")).toList();
    }

    @Test void latestIncludesRecentLowTrafficUserBeforeTopFiftyLimit() {
        for (int i = 0; i < 51; i++) {
            var creator = String.format("active-%02d", i);
            event(creator, "pageview", earlier);
            event(creator, "pageview", earlier);
        }
        event("newest", "pageview", recent);
        assertEquals(50, users("active").size());
        assertFalse(ids("active").contains("newest"));
        var latest = ids("latest");
        assertEquals(50, latest.size());
        assertEquals("newest", latest.get(0), "Newest must be selected from all users, not re-sort the active top 50");
    }

    @Test void activeUsesDisplayedPageviewsRatherThanAllEventCount() {
        event("many-leaves", "pageview", recent);
        for (int i = 0; i < 12; i++) event("many-leaves", "page_leave", recent);
        for (int i = 0; i < 3; i++) event("many-visits", "pageview", earlier);
        var active = users("active");
        assertEquals(List.of("many-visits", "many-leaves"), ids("active"));
        assertEquals(3L, active.get(0).get("pageviews"));
        assertEquals(13L, active.get(1).get("pv"), "Extra leave events must not overtake actual page visits");
    }

    @Test void equalActivityUsesRecentVisitThenStableCreatorId() {
        event("a-old", "pageview", earlier);
        event("c-recent", "pageview", recent);
        event("b-recent", "pageview", recent);
        var expected = List.of("b-recent", "c-recent", "a-old");
        assertEquals(expected, ids("active"));
        assertEquals(expected, ids("active"));
    }

    @Test void equalLastSeenUsesPageviewsThenStableCreatorId() {
        event("a-one-visit", "pageview", recent);
        event("c-two-visits", "pageview", earlier);
        event("c-two-visits", "pageview", recent);
        event("b-two-visits", "pageview", earlier);
        event("b-two-visits", "pageview", recent);
        assertEquals(List.of("b-two-visits", "c-two-visits", "a-one-visit"), ids("latest"));
    }

    @Test @SuppressWarnings("unchecked")
    void legacySummaryMethodKeepsDefaultActiveOrder() {
        event("newest", "pageview", recent);
        event("most-active", "pageview", earlier);
        event("most-active", "pageview", earlier);
        var body = controller.summary(30).getBody();
        assertNotNull(body);
        assertFalse(body.containsKey("error"));
        assertEquals(users("active"), (List<Map<String, Object>>) body.get("users"));
        assertEquals("most-active", ((List<Map<String, Object>>) body.get("users")).get(0).get("creatorId"));
    }

    @Test void invalidSortIsRejectedBeforeStatisticsQueries() {
        // Remove a statistics-only table. A valid query would fail; invalid input must still return 400.
        jdbc.execute("DROP TABLE site_event");
        for (String invalid : List.of("pv DESC", "latest;DROP TABLE creator", "LATEST", "", "latest ")) {
            var response = controller.summary(30, invalid);
            assertEquals(400, response.getStatusCode().value(), invalid);
            assertNull(response.getBody(), "Invalid parameters must not return a partial summary");
        }
        assertEquals(400, controller.summary(30, null).getStatusCode().value());
    }

    @Test void deniedUserDoesNotLearnSortValidationOrStatistics() {
        login("not-authorized");
        for (String sort : List.of("active", "latest", "untrusted SQL")) {
            var response = controller.summary(30, sort);
            assertEquals(200, response.getStatusCode().value());
            assertEquals(Map.of("allowed", false), response.getBody());
        }
    }

    @Test void newestMeansMostRecentVisitNotFirstVisit() {
        event("returning", "pageview", earlier.minus(5, ChronoUnit.DAYS));
        event("returning", "pageview", recent);
        event("first-visit", "pageview", earlier);
        assertEquals(List.of("returning", "first-visit"), ids("latest"));
    }
}
