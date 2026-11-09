package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.CreatorSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 站内用户行为埋点接收端，移植自 tk-tracking v1.1/v1.2 的本地落库方案。
 * 公开、匿名、无 CSRF（sendBeacon 无法带自定义头），靠事件白名单、字段脱敏与 ApiRateLimitFilter 约束。
 * 只存匿名标识与脱敏路径；登录用户 ID 由服务端从 QF_SESSION 解析，绝不采信前端；
 * 邮箱 / 手机号不进埋点表，看板通过 creator_id 关联身份表获取。
 * 埋点失败静默丢弃，绝不影响业务请求。
 */
@RestController
@RequestMapping("/api/public")
public class SiteEventController {
    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "pageview", "page_leave", "gift_view", "creation_start", "lyrics_generated", "song_generated",
            "checkout_start", "purchase", "audio_play", "gift_download");
    private static final Set<String> ALLOWED_PAYLOAD_KEYS = Set.of(
            "route", "durationMs", "audioOrigin", "provenance", "provider", "result", "plan", "status");
    private static final Pattern GIFT_TOKEN = Pattern.compile("/gift/[A-Za-z0-9_-]{20,}");
    private static final Pattern WORK_ID = Pattern.compile("/works/[A-Za-z0-9_-]{8,}");
    private static final int MAX_PAYLOAD = 2048;
    private static final long MAX_DURATION_MS = 86_400_000L;
    static final String INSERT_SQL = "INSERT INTO site_event(id,visitor_id,session_id,event,page_path,referrer,payload,created_at,creator_id,duration_ms) VALUES(?,?,?,?,?,?,?,?,?,?)";

    public record EventRequest(String event, String pagePath, String referrer,
                               String visitorId, String sessionId, Map<String, ?> payload) {}

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<IdentityApi> identities;
    private final Environment environment;
    private static final ObjectMapper JSON = new ObjectMapper();

    public SiteEventController(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<IdentityApi> identities,
                               Environment environment) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.environment = environment;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> collect(@RequestBody(required = false) EventRequest request, HttpServletRequest servletRequest) {
        var clean = sanitize(request);
        // 默认关闭：同意门禁与隐私政策落地前，绝不因「下一次上线」而自动开始采集。
        // 需在服务端私有配置显式设置 qingfeng.analytics.enabled=true 才会落库。
        if (clean == null || !environment.getProperty("qingfeng.analytics.enabled", Boolean.class, false)) {
            return ResponseEntity.noContent().build();
        }
        var template = jdbc.getIfAvailable();
        if (template == null) return ResponseEntity.noContent().build();
        try {
            template.update(INSERT_SQL, row(clean, payloadJson(clean.payload()),
                    creatorId(servletRequest), durationMs(clean.payload())));
        } catch (RuntimeException ignored) {
            // 埋点失败不影响业务，也不向前端抛错。
        }
        return ResponseEntity.noContent().build();
    }

    /** 落库参数只来自脱敏后的请求与服务端会话；单独成方法便于测试断言，避免 mock varargs 的假阳性。 */
    static Object[] row(EventRequest clean, String payloadJson, String creatorId, Long durationMs) {
        return new Object[]{UUID.randomUUID().toString(), clean.visitorId(), clean.sessionId(), clean.event(),
                clean.pagePath(), clean.referrer(), payloadJson, Timestamp.from(Instant.now()), creatorId, durationMs};
    }

    /** 登录用户归属只认服务端会话令牌，前端传任何身份字段都无效。 */
    private String creatorId(HttpServletRequest request) {
        return creatorId(identities.getIfAvailable(), request);
    }

    /** 包可见便于测试：身份只认服务端会话，前端无法伪造。 */
    static String creatorId(IdentityApi identity, HttpServletRequest request) {
        if (identity == null) return "";
        try {
            return Optional.ofNullable(CreatorSessionFilter.token(request)).flatMap(identity::resolve).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 页面停留时长由前端 page_leave 上报；只接受 0..24h 的数值，异常一律丢弃。 */
    static Long durationMs(Map<String, ?> payload) {
        if (payload == null) return null;
        var raw = payload.get("durationMs");
        if (raw == null) return null;
        try {
            long value = raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
            return value >= 0 && value <= MAX_DURATION_MS ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 返回 null 表示拒绝；不信任前端，路径与来源在此二次脱敏。 */
    static EventRequest sanitize(EventRequest request) {
        if (request == null || request.event() == null || !ALLOWED_EVENTS.contains(request.event())) return null;
        return new EventRequest(request.event(), cleanPath(request.pagePath()), cleanReferrer(request.referrer()),
                cleanId(request.visitorId()), cleanId(request.sessionId()), request.payload());
    }

    private static String cleanPath(String raw) {
        if (raw == null) return "";
        var path = raw.length() > 512 ? raw.substring(0, 512) : raw;
        int cut = path.length();
        int query = path.indexOf('?'), hash = path.indexOf('#');
        if (query >= 0) cut = Math.min(cut, query);
        if (hash >= 0) cut = Math.min(cut, hash);
        path = path.substring(0, cut);
        if (!path.startsWith("/")) return "";
        path = GIFT_TOKEN.matcher(path).replaceAll("/gift/:token");
        return WORK_ID.matcher(path).replaceAll("/works/:id");
    }

    private static String cleanReferrer(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            var uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return "";
            if (uri.getHost() == null) return "";
            var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            var text = uri.getScheme() + "://" + uri.getHost() + port + (uri.getPath() == null ? "" : uri.getPath());
            return text.length() > 512 ? text.substring(0, 512) : text;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String cleanId(String raw) {
        if (raw == null) return "";
        var value = raw.length() > 64 ? raw.substring(0, 64) : raw;
        return value.matches("[A-Za-z0-9_-]{1,64}") ? value : "";
    }

    static String payloadJson(Map<String, ?> payload) {
        if (payload == null || payload.isEmpty()) return null;
        var clean = new LinkedHashMap<String, String>();
        for (var entry : payload.entrySet()) {
            if (!ALLOWED_PAYLOAD_KEYS.contains(entry.getKey()) || entry.getValue() == null) continue;
            var value = String.valueOf(entry.getValue());
            clean.put(entry.getKey(), value.substring(0, Math.min(value.length(), 128)));
            if (clean.size() >= 12) break;
        }
        if (clean.isEmpty()) return null;
        try {
            var text = JSON.writeValueAsString(clean);
            return text.length() > MAX_PAYLOAD ? null : text;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
