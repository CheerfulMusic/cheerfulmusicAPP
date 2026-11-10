package com.qingfeng.music.server.web.app;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.analytics.AnalyticsDetailService;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    /**
     * 匿名化哨兵值行（visitor_id/session_id = 'ANONYMIZED'）必须排除在 UV / sessions / 页面 PV 之外，
     * 否则 COUNT(DISTINCT) 会把全部匿名化行误算成 1 个额外真实访客。正常数据无此哨兵值，行集合不变，口径零偏差。
     * 各聚合统一引用此常量，避免散落硬编码导致后续遗漏（见隐私治理方案 §2.B.6 / §15.1）。
     */
    private static final String REAL_VISITOR_PREDICATE = "visitor_id <> 'ANONYMIZED' AND session_id <> 'ANONYMIZED'";

    private final IdentityApi identity;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final Environment environment;
    private final AnalyticsDetailService detail;

    public AnalyticsViewController(IdentityApi identity, ObjectProvider<JdbcTemplate> jdbc, Environment environment,
                                   AnalyticsDetailService detail) {
        this.identity = identity;
        this.jdbc = jdbc;
        this.environment = environment;
        this.detail = detail;
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

    /**
     * 账号来源：优先 Google 身份邮箱，缺失时回退到 Logto/OIDC 身份表的邮箱。
     * 邮箱一律由服务端从已登录 creatorId 查得，前端传值不构成权限。
     */
    private Optional<String> account(String creatorId) {
        var google = identity.googleIdentity(creatorId)
                .map(IdentityApi.GoogleIdentity::email)
                .filter(value -> value != null && !value.isBlank());
        if (google.isPresent()) return google;
        var template = jdbc.getIfAvailable();
        if (template == null) return Optional.empty();
        try {
            return Optional.ofNullable(template.query(
                    "SELECT email FROM creator_oidc_identity WHERE creator_id=?",
                    rs -> rs.next() ? rs.getString(1) : null, creatorId));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 包可见便于测试；空白名单、无身份、查库异常一律 fail-closed。 */
    boolean allowed(String creatorId) {
        var allowed = viewers();
        if (allowed.isEmpty() || creatorId == null || creatorId.isBlank()) return false;
        try {
            return account(creatorId)
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
        if (ok) body.put("viewer", account(creator).orElse(""));
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
                    "SELECT COUNT(*) pv, COUNT(CASE WHEN event='pageview' THEN 1 END) pageviews, "
                            + "COUNT(DISTINCT visitor_id) uv, COUNT(DISTINCT session_id) sessions, "
                            + "COALESCE(AVG(CASE WHEN event='page_leave' THEN duration_ms END),0) avg_duration_ms, "
                            + "COALESCE(SUM(CASE WHEN event='page_leave' THEN duration_ms END),0) total_duration_ms "
                            + "FROM site_event WHERE created_at >= ? AND " + REAL_VISITOR_PREDICATE,
                    (rs, n) -> map(rs.getLong("pv"), rs.getLong("pageviews"), rs.getLong("uv"), rs.getLong("sessions"),
                            rs.getLong("avg_duration_ms"), rs.getLong("total_duration_ms")), since).stream().findFirst().orElseGet(LinkedHashMap::new));
            body.put("daily", template.query(
                    "SELECT CAST(created_at AS DATE) d, COUNT(*) pv, COUNT(CASE WHEN event='pageview' THEN 1 END) pageviews, "
                            + "COUNT(DISTINCT visitor_id) uv, "
                            + "COALESCE(AVG(CASE WHEN event='page_leave' THEN duration_ms END),0) avg_duration_ms FROM site_event "
                            + "WHERE created_at >= ? AND " + REAL_VISITOR_PREDICATE + " GROUP BY CAST(created_at AS DATE) ORDER BY d",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("d", String.valueOf(rs.getDate("d")));
                        row.put("pv", rs.getLong("pv"));
                        row.put("pageviews", rs.getLong("pageviews"));
                        row.put("uv", rs.getLong("uv"));
                        row.put("avgDurationMs", rs.getLong("avg_duration_ms"));
                        return row;
                    }, since));
            // 停留时长只存在于 page_leave 事件上；pageview 行没有时长，
            // 直接对 pageview 取平均会永远是 0，所以按页面分别聚合后再拼回来。
            body.put("pages", template.query(
                    "SELECT p.page_path, p.pv, COALESCE(l.avg_ms,0) avg_duration_ms, COALESCE(l.samples,0) samples FROM ("
                            + "SELECT page_path, COUNT(*) pv FROM site_event WHERE event='pageview' AND created_at >= ? AND " + REAL_VISITOR_PREDICATE + " "
                            + "GROUP BY page_path ORDER BY pv DESC LIMIT 20) p LEFT JOIN ("
                            + "SELECT page_path, AVG(duration_ms) avg_ms, COUNT(*) samples FROM site_event "
                            + "WHERE event='page_leave' AND duration_ms IS NOT NULL AND duration_ms >= 0 AND created_at >= ? AND " + REAL_VISITOR_PREDICATE + " "
                            + "GROUP BY page_path) l ON l.page_path = p.page_path ORDER BY p.pv DESC",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("pagePath", rs.getString("page_path"));
                        row.put("pv", rs.getLong("pv"));
                        row.put("avgDurationMs", Math.round(rs.getDouble("avg_duration_ms")));
                        row.put("samples", rs.getLong("samples"));
                        return row;
                    }, since, since));
            // 身份来自三张互斥的身份表；都没有绑定的就是游客会话（游客也带 creator_id，不能被当成登录用户）。
            body.put("users", template.query(
                    "SELECT t.creator_id, t.account_type, t.name, t.email, t.phone_masked, COUNT(*) pv, "
                            + "COUNT(CASE WHEN t.event='pageview' THEN 1 END) pageviews, "
                            + "COALESCE(AVG(CASE WHEN t.event='page_leave' THEN t.duration_ms END),0) avg_duration_ms, "
                            + "SUM(CASE WHEN t.event='page_leave' AND t.duration_ms IS NOT NULL THEN 1 ELSE 0 END) samples, "
                            + "MIN(t.created_at) first_seen, MAX(t.created_at) last_seen FROM ("
                            + "SELECT s.creator_id, s.event, s.duration_ms, s.created_at, "
                            + "COALESCE(g.email, o.email) email, "
                            + "COALESCE(g.display_name, o.display_name, a.display_name) name, o.phone_masked, "
                            + "CASE WHEN g.creator_id IS NOT NULL THEN 'GOOGLE' "
                            + "WHEN o.email IS NOT NULL AND o.email <> '' THEN 'OIDC_EMAIL' "
                            + "WHEN o.creator_id IS NOT NULL THEN 'OIDC_PHONE' "
                            + "WHEN a.creator_id IS NOT NULL THEN 'AUTHING' ELSE 'GUEST' END account_type "
                            + "FROM site_event s LEFT JOIN creator_google_identity g ON g.creator_id=s.creator_id "
                            + "LEFT JOIN creator_oidc_identity o ON o.creator_id=s.creator_id "
                            + "LEFT JOIN creator_authing_identity a ON a.creator_id=s.creator_id "
                            + "WHERE s.creator_id IS NOT NULL AND s.creator_id <> '' AND s.created_at >= ?"
                            + ") t GROUP BY t.creator_id, t.account_type, t.name, t.email, t.phone_masked "
                            + "ORDER BY pv DESC LIMIT 50",
                    (rs, n) -> {
                        var row = new LinkedHashMap<String, Object>();
                        row.put("creatorId", rs.getString("creator_id"));
                        row.put("accountType", rs.getString("account_type"));
                        row.put("name", rs.getString("name"));
                        row.put("email", rs.getString("email"));
                        row.put("phoneMasked", rs.getString("phone_masked"));
                        row.put("pv", rs.getLong("pv"));
                        row.put("pageviews", rs.getLong("pageviews"));
                        row.put("avgDurationMs", rs.getLong("avg_duration_ms"));
                        row.put("samples", rs.getLong("samples"));
                        row.put("firstSeen", rs.getTimestamp("first_seen") == null ? "" : rs.getTimestamp("first_seen").toInstant().toString());
                        row.put("lastSeen", rs.getTimestamp("last_seen") == null ? "" : rs.getTimestamp("last_seen").toInstant().toString());
                        return row;
                    }, since));
        } catch (RuntimeException e) {
            // 查询异常时清掉已写入的半份指标：error 与数据互斥，前端才能正确显示「数据不可用」。
            body.keySet().removeIf(key -> !Set.of("allowed", "days").contains(key));
            body.put("error", "QUERY_FAILED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * 页面下钻：某个页面上每个登录用户的停留明细。
     * 权限判定与 summary 完全一致；非白名单账号即使直接调接口也只拿到 allowed=false。
     */
    @GetMapping("/page-users")
    public ResponseEntity<Map<String, Object>> pageUsers(@RequestParam String pagePath,
                                                         @RequestParam(defaultValue = "30") int days) {
        var body = new LinkedHashMap<String, Object>();
        body.put("allowed", false);
        if (!allowed(currentCreatorId())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        var clean = pagePath == null ? "" : pagePath.trim();
        if (clean.isEmpty() || clean.length() > 512 || !clean.startsWith("/")) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        int window = Math.max(1, Math.min(days, 90));
        body.put("allowed", true);
        body.put("days", window);
        body.put("pagePath", clean);
        try {
            body.put("users", detail.pageDwell(clean, Instant.now().minus(window, ChronoUnit.DAYS)));
        } catch (RuntimeException e) {
            body.put("users", List.of());
            body.put("error", "QUERY_FAILED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** 用户下钻：某个用户在各页面的停留；权限与其它看板接口一致。 */
    @GetMapping("/user-pages")
    public ResponseEntity<Map<String, Object>> userPages(@RequestParam String creatorId,
                                                         @RequestParam(defaultValue = "30") int days) {
        var body = new LinkedHashMap<String, Object>();
        body.put("allowed", false);
        if (!allowed(currentCreatorId())) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
        }
        var clean = creatorId == null ? "" : creatorId.trim();
        if (!clean.matches("[A-Za-z0-9-]{8,36}")) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        int window = Math.max(1, Math.min(days, 90));
        body.put("allowed", true);
        body.put("days", window);
        body.put("creatorId", clean);
        try {
            body.put("pages", detail.userPages(clean, Instant.now().minus(window, ChronoUnit.DAYS)));
        } catch (RuntimeException e) {
            body.put("pages", List.of());
            body.put("error", "QUERY_FAILED");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static Map<String, Object> map(long pv, long pageviews, long uv, long sessions, long avg, long total) {
        var row = new LinkedHashMap<String, Object>();
        row.put("pv", pv);
        row.put("pageviews", pageviews);
        row.put("uv", uv);
        row.put("sessions", sessions);
        row.put("avgDurationMs", avg);
        row.put("totalDurationMs", total);
        return row;
    }
}
