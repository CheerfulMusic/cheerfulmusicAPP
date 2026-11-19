package com.qingfeng.music.server.analytics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 看板下钻查询：某个页面上每个人的停留时长。
 * 口径与页面列表一致，只用 page_leave.duration_ms；
 * 没有离开事件的用户不会出现在结果里，避免把「没停留」算成 0 秒。
 *
 * 身份字段来自三张互斥的身份表（Google / OIDC / Authing），都没有绑定的就是游客会话。
 * 游客也会带 creator_id，所以这里显式给出 accountType，前端不能再把它当成登录用户。
 */
@Service @Profile({"mysql","test"})
public class AnalyticsDetailService {
    /**
     * accountType: GOOGLE / OIDC_EMAIL / OIDC_PHONE / AUTHING / GUEST。
     * name 可能为空；手机号在登录时按脱敏写入，这里只能给出掩码。
     */
    public record UserDwell(String creatorId, String accountType, String name, String email,
                            String phoneMasked, long avgDurationMs, long samples) {}

    /** 单个用户在各页面的停留；同样只认 page_leave 且有有效时长。 */
    public record PageDwell(String pagePath, long avgDurationMs, long samples) {}

    /** 未识别的身份一律归为游客，绝不猜测成某个账号。 */
    static final String IDENTITY_SQL =
            "CASE WHEN g.creator_id IS NOT NULL THEN 'GOOGLE' "
                    + "WHEN o.email IS NOT NULL AND o.email <> '' THEN 'OIDC_EMAIL' "
                    + "WHEN o.creator_id IS NOT NULL THEN 'OIDC_PHONE' "
                    + "WHEN a.creator_id IS NOT NULL THEN 'AUTHING' ELSE 'GUEST' END";

    public static final String REAL_VISITOR_PREDICATE = "visitor_id <> 'ANONYMIZED' AND session_id <> 'ANONYMIZED'";
    private static final Map<String, String> COUNTRY_CODES = countryCodes();

    private static Map<String, String> countryCodes() {
        var codes = new HashMap<String, String>();
        for (String code : Locale.getISOCountries()) {
            var region = new Locale("", code);
            codes.put(region.getDisplayCountry(Locale.SIMPLIFIED_CHINESE), code);
            codes.put(region.getDisplayCountry(Locale.ENGLISH), code);
            codes.put(code, code);
        }
        codes.putAll(Map.ofEntries(
                Map.entry("中国香港", "HK"), Map.entry("中国澳门", "MO"), Map.entry("中国台湾", "TW"),
                Map.entry("香港", "HK"), Map.entry("澳门", "MO"), Map.entry("台湾", "TW"),
                Map.entry("俄罗斯联邦", "RU"), Map.entry("捷克共和国", "CZ"),
                Map.entry("波斯尼亚和黑塞哥维那", "BA"), Map.entry("刚果民主共和国", "CD"),
                Map.entry("刚果共和国", "CG"), Map.entry("象牙海岸", "CI"), Map.entry("科索沃", "XK")));
        return Map.copyOf(codes);
    }

    public static String countryCode(String country) {
        return COUNTRY_CODES.getOrDefault(country, "");
    }

    public record CountryUser(String creatorId, String accountType, String name, String email,
                              String phoneMasked, long hits, Instant lastSeen) {}

    public List<CountryUser> countryUsers(String country, Instant since) {
        return db.query("""
                SELECT t.creator_id, %s account_type, COALESCE(g.email, o.email) email,
                       COALESCE(g.display_name, o.display_name, a.display_name) name, o.phone_masked,
                       t.hits, t.last_seen
                FROM (
                    SELECT creator_id, CASE WHEN creator_id IS NULL OR creator_id='' THEN visitor_id END guest_id,
                           COUNT(*) hits, MAX(created_at) last_seen
                    FROM site_event WHERE country=? AND created_at>=? AND %s
                    GROUP BY creator_id, CASE WHEN creator_id IS NULL OR creator_id='' THEN visitor_id END
                ) t LEFT JOIN creator_google_identity g ON g.creator_id=t.creator_id
                LEFT JOIN creator_oidc_identity o ON o.creator_id=t.creator_id
                LEFT JOIN creator_authing_identity a ON a.creator_id=t.creator_id
                ORDER BY t.hits DESC, t.last_seen DESC, t.creator_id ASC, t.guest_id ASC
                """.formatted(IDENTITY_SQL, REAL_VISITOR_PREDICATE),
                (rs, n) -> new CountryUser(rs.getString("creator_id"), rs.getString("account_type"),
                        rs.getString("name"), rs.getString("email"), rs.getString("phone_masked"),
                        rs.getLong("hits"), rs.getTimestamp("last_seen").toInstant()),
                country, Timestamp.from(since));
    }

    public List<Map<String, Object>> recentRegisteredUsers(Timestamp since, Set<String> activeIds) {
        return db.query("""
                SELECT r.creator_id, %s account_type,
                       COALESCE(g.display_name, o.display_name, a.display_name) name,
                       COALESCE(g.email, o.email, a.email) email,
                       COALESCE(b.phone_masked, o.phone_masked) phone_masked,
                       (SELECT COUNT(*) FROM music_work w WHERE w.owner_id=r.creator_id) works
                FROM (
                    SELECT creator_id, MIN(created_at) registered_at
                    FROM (
                        SELECT creator_id, created_at FROM creator_google_identity
                        UNION ALL SELECT creator_id, created_at FROM creator_oidc_identity
                        UNION ALL SELECT creator_id, created_at FROM creator_authing_identity
                    ) identities
                    GROUP BY creator_id HAVING MIN(created_at)>=?
                ) r
                LEFT JOIN creator_google_identity g ON g.creator_id=r.creator_id
                LEFT JOIN creator_oidc_identity o ON o.creator_id=r.creator_id
                LEFT JOIN creator_authing_identity a ON a.creator_id=r.creator_id
                LEFT JOIN creator_phone_binding b ON b.creator_id=r.creator_id
                ORDER BY r.registered_at DESC, r.creator_id ASC
                """.formatted(IDENTITY_SQL), rs -> {
            var rows = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                String creatorId = rs.getString("creator_id");
                if (activeIds.contains(creatorId)) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("creatorId", creatorId);
                row.put("accountType", rs.getString("account_type"));
                row.put("name", rs.getString("name"));
                row.put("email", rs.getString("email"));
                row.put("phoneMasked", rs.getString("phone_masked"));
                row.put("works", rs.getLong("works"));
                row.put("pv", 0);
                row.put("pageviews", 0);
                row.put("avgDurationMs", 0);
                row.put("samples", 0);
                row.put("firstSeen", "");
                row.put("lastSeen", "");
                row.put("noActivity", true);
                rows.add(row);
            }
            return rows;
        }, since);
    }

    public record PhoneUser(String creatorId, String accountType, String name, String phoneMasked) {}

    public List<PhoneUser> phoneUsers(String suffix) {
        return db.query("""
                SELECT p.creator_id, COALESCE(g.display_name, o.display_name) name,
                       CASE WHEN g.creator_id IS NOT NULL THEN 'GOOGLE'
                            WHEN o.email IS NOT NULL AND o.email <> '' THEN 'OIDC_EMAIL'
                            ELSE 'OIDC_PHONE' END account_type
                FROM (
                    SELECT b.creator_id FROM creator_phone_binding b WHERE RIGHT(TRIM(b.phone_masked), 4)=?
                    UNION ALL
                    SELECT i.creator_id FROM creator_oidc_identity i WHERE RIGHT(TRIM(i.phone_masked), 4)=?
                    AND NOT EXISTS (SELECT 1 FROM creator_phone_binding b WHERE b.creator_id=i.creator_id)
                ) p LEFT JOIN creator_oidc_identity o ON o.creator_id=p.creator_id
                LEFT JOIN creator_google_identity g ON g.creator_id=p.creator_id
                ORDER BY p.creator_id
                """, (rs, n) -> new PhoneUser(rs.getString("creator_id"), rs.getString("account_type"),
                        rs.getString("name"), "****" + suffix), suffix, suffix);
    }

    private static final int LIMIT = 50;

    private final JdbcTemplate db;

    public AnalyticsDetailService(JdbcTemplate db) { this.db = db; }

    public List<UserDwell> pageDwell(String pagePath, Instant since) {
        var rows = db.queryForList(
                "SELECT t.creator_id, t.account_type, t.name, t.email, t.phone_masked, "
                        + "AVG(t.duration_ms) avg_ms, COUNT(*) samples FROM ("
                        + "SELECT s.creator_id, s.duration_ms, COALESCE(g.email, o.email) email, "
                        + "COALESCE(g.display_name, o.display_name, a.display_name) name, o.phone_masked, "
                        + IDENTITY_SQL + " account_type "
                        + "FROM site_event s LEFT JOIN creator_google_identity g ON g.creator_id=s.creator_id "
                        + "LEFT JOIN creator_oidc_identity o ON o.creator_id=s.creator_id "
                        + "LEFT JOIN creator_authing_identity a ON a.creator_id=s.creator_id "
                        + "WHERE s.event='page_leave' AND s.page_path=? AND s.created_at>=? "
                        + "AND s.creator_id IS NOT NULL AND s.creator_id<>'' AND s.duration_ms IS NOT NULL AND s.duration_ms>=0"
                        + ") t GROUP BY t.creator_id, t.account_type, t.name, t.email, t.phone_masked "
                        + "ORDER BY samples DESC, avg_ms DESC LIMIT " + LIMIT,
                pagePath, Timestamp.from(since));
        var result = new ArrayList<UserDwell>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new UserDwell(text(row.get("creator_id")), text(row.get("account_type")),
                    text(row.get("name")), text(row.get("email")), text(row.get("phone_masked")),
                    Math.round(number(row.get("avg_ms"))), number(row.get("samples"))));
        }
        return result;
    }

    public List<PageDwell> userPages(String creatorId, Instant since) {
        var rows = db.queryForList(
                "SELECT page_path, AVG(duration_ms) avg_ms, COUNT(*) samples FROM site_event "
                        + "WHERE event='page_leave' AND creator_id=? AND created_at>=? "
                        + "AND duration_ms IS NOT NULL AND duration_ms>=0 "
                        + "GROUP BY page_path ORDER BY samples DESC, avg_ms DESC LIMIT " + LIMIT,
                creatorId, Timestamp.from(since));
        var result = new ArrayList<PageDwell>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(new PageDwell(text(row.get("page_path")),
                    Math.round(number(row.get("avg_ms"))), number(row.get("samples"))));
        }
        return result;
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static long number(Object value) { return value instanceof Number n ? Math.round(n.doubleValue()) : 0L; }
}
