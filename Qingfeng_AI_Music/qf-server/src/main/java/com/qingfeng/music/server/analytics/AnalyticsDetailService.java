package com.qingfeng.music.server.analytics;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                        + "AND s.creator_id IS NOT NULL AND s.duration_ms IS NOT NULL AND s.duration_ms>=0"
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
