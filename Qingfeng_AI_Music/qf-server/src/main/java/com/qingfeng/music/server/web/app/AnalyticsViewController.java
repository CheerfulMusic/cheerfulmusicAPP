package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 站内数据看板（内部工具）。
 * 鉴权完全在服务端：只有已登录、且 Google 账号邮箱命中私有白名单
 * qingfeng.analytics.viewer-emails 的账号才能拿到任何统计值。
 * 白名单为空 / 未登录 / 非 Google 账号 / 邮箱不匹配一律 fail-closed。
 * 前端是否渲染入口不构成权限，绕过前端直接调接口同样拿不到数据。
 */
@RestController
@RequestMapping("/api/app/analytics")
@Profile({"mysql", "test"})
public class AnalyticsViewController {
    private static final String VIEWER_KEY = "qingfeng.analytics.viewer-emails";

    private final IdentityApi identity;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Environment environment;

    public AnalyticsViewController(IdentityApi identity, ObjectProvider<JdbcTemplate> jdbc, Environment environment) {
        this.identity = identity;
        this.jdbc = jdbc;
        this.environment = environment;
    }

    private Set<String> viewers() {
        var result = new HashSet<String>();
        var raw = environment.getProperty(VIEWER_KEY, "");
        for (var item : raw.split(",")) {
            var value = item.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private String currentCreatorId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CreatorPrincipal principal ? principal.id() : null;
    }

    /** 包可见便于测试；空白名单绝不放行。 */
    boolean allowed(String creatorId) {
        var allowed = viewers();
        if (allowed.isEmpty() || creatorId == null || creatorId.isBlank()) return false;
        try {
            return identity.googleIdentity(creatorId)
                    .map(IdentityApi.GoogleIdentity::email)
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .filter(allowed::contains)
                    .isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @GetMapping("/access")
    public ResponseEntity<Map<String, Object>> access() {
        var creator = currentCreatorId();
        var ok = allowed(creator);
        var body = new LinkedHashMap<String, Object>();
        body.put("allowed", ok);
        if (ok) {
            body.put("viewer", identity.googleIdentity(creator)
                    .map(IdentityApi.GoogleIdentity::email).orElse(""));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam(defaultValue = "30") int days) {
        var body = new LinkedHashMap<String, Object>();
        body.put("allowed", false);
        if (!allowed(currentCreatorId())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        var template = jdbc.getIfAvailable();
        if (template == null) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        int window = Math.max(1, Math.min(days, 90));
        var since = Timestamp.from(Instant.now().minus(window, ChronoUnit.DAYS));
        body.put("allowed", true);
        body.put("days", window);
        try {
            body.put("totals", template.query(
                    "SELECT COUNT(*) pv, COUNT(DISTINCT visitor_id) uv, COUNT(DISTINCT session_id) sessions, "
                            + "COALESCE(AVG(duration_ms),0) avg_duration_ms, COALESCE(SUM(duration_ms),0) total_duration_ms "
                            + "FROM site_event WHERE created_at >= ?",
                    (rs, n) -> map(rs.getLong("pv"), rs.getLong("uv"), rs.getLong("sessions"),
                            rs.getLong("avg_duration_ms"), rs.getLong("total_duration_ms")), since).stream().findFirst().orElseGet(LinkedHashMap::new));
            body.put("daily", template.query(
                    "SELECT CAST(created_at AS DATE) d, COUNT(*) pv, COUNT(DISTINCT visitor_id) uv, "
                            + "COALESCE(AVG(duration_ms),0) avg_duration_ms FROM site_event "
                            + "WHERE created_at >= ? GROUP BY CAST(created_at AS DATE) ORDER BY d",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("d", String.valueOf(rs.getDate("d")));
                        row.put("pv", rs.getLong("pv"));
                        row.put("uv", rs.getLong("uv"));
                        row.put("avgDurationMs", rs.getLong("avg_duration_ms"));
                        return row;
                    }, since));
            body.put("pages", template.query(
                    "SELECT page_path, COUNT(*) pv, COALESCE(AVG(duration_ms),0) avg_duration_ms "
                            + "FROM site_event WHERE event='pageview' AND created_at >= ? "
                            + "GROUP BY page_path ORDER BY pv DESC LIMIT 20",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("pagePath", rs.getString("page_path"));
                        row.put("pv", rs.getLong("pv"));
                        row.put("avgDurationMs", rs.getLong("avg_duration_ms"));
                        return row;
                    }, since));
            body.put("users", template.query(
                    "SELECT s.creator_id, COALESCE(g.email, o.email, o.phone_masked) account, COUNT(*) pv, "
                            + "COALESCE(AVG(s.duration_ms),0) avg_duration_ms, MAX(s.created_at) last_seen "
                            + "FROM site_event s LEFT JOIN creator_google_identity g ON g.creator_id=s.creator_id "
                            + "LEFT JOIN creator_oidc_identity o ON o.creator_id=s.creator_id "
                            + "WHERE s.creator_id IS NOT NULL AND s.created_at >= ? "
                            + "GROUP BY s.creator_id, COALESCE(g.email, o.email, o.phone_masked) "
                            + "ORDER BY pv DESC LIMIT 50",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("creatorId", rs.getString("creator_id"));
                        row.put("account", rs.getString("account"));
                        row.put("pv", rs.getLong("pv"));
                        row.put("avgDurationMs", rs.getLong("avg_duration_ms"));
                        row.put("lastSeen", rs.getTimestamp("last_seen") == null ? "" : rs.getTimestamp("last_seen").toInstant().toString());
                        return row;
                    }, since));
        } catch (RuntimeException e) {
            body.put("error", "QUERY_FAILED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Map<String, Object> map(long pv, long uv, long sessions, long avg, long total) {
        var row = new LinkedHashMap<String, Object>();
        row.put("pv", pv);
        row.put("uv", uv);
        row.put("sessions", sessions);
        row.put("avgDurationMs", avg);
        row.put("totalDurationMs", total);
        return row;
    }
}
