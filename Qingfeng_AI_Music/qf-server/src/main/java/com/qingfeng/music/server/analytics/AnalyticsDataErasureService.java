package com.qingfeng.music.server.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 撤回同意后的历史数据处理（隐私治理方案 §2.B）。
 *
 * <p>安全边界：
 * <ul>
 *   <li><b>标识来源唯一</b>：creator_id 只能由登录态会话解析后由调用方传入，
 *       本类不提供任何接受客户端 creator_id 的入口；空值一律拒绝。</li>
 *   <li><b>范围严格</b>：所有读写都带 {@code WHERE creator_id = ?}，
 *       删除再叠加 {@code AND creator_id = ?} 二次防误删，禁止全表路径。</li>
 *   <li><b>默认关</b>：{@code qingfeng.analytics.erasure.enabled=false} 时整体 no-op。</li>
 *   <li><b>方式由法务定</b>：{@code erasure.method} 未配置 / 未知值一律 fail-closed；
 *       {@code aggregate}（聚合后保留）因本轮无汇总表，明确返回 UNSUPPORTED，不删任何数据。</li>
 *   <li><b>匿名化遵守 NOT NULL</b>：visitor_id / session_id 为 NOT NULL，
 *       用常量哨兵值 {@code 'ANONYMIZED'} 而非 NULL；仅可空列 creator_id / payload 置空。</li>
 *   <li>幂等：二次执行影响行数 = 0；分批独立事务；结构化日志审计。</li>
 * </ul>
 *
 * <p>审计：本轮不新增数据库表，以结构化日志落地。持久化 erasure_audit 表属授权环境动作。
 */
public class AnalyticsDataErasureService {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsDataErasureService.class);

    public static final String ANONYMIZED = "ANONYMIZED";

    static final String SELECT_IDS_SQL =
            "SELECT id FROM site_event WHERE creator_id = ? ORDER BY created_at LIMIT ?";
    static final String DELETE_SQL = "DELETE FROM site_event WHERE id = ? AND creator_id = ?";
    static final String ANONYMIZE_SQL = "UPDATE site_event SET visitor_id = ?, session_id = ?, "
            + "creator_id = NULL, payload = NULL WHERE id = ? AND creator_id = ?";
    static final String COUNT_SQL = "SELECT COUNT(*) FROM site_event WHERE creator_id = ?";

    private static final int HARD_BATCH_LIMIT = 5000;

    public enum Method { DELETE, ANONYMIZE, AGGREGATE }

    public enum Status {
        DISABLED, INVALID_REQUEST, INVALID_CONFIG, UNSUPPORTED, SUCCESS, FAILED
    }

    public record Result(Status status, String requestId, String method, long matched, long affected, String reason) {}

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String configuredMethod;
    private final int batchSize;

    public AnalyticsDataErasureService(JdbcTemplate jdbc, boolean enabled,
                                       String configuredMethod, int batchSize) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.configuredMethod = configuredMethod == null ? "" : configuredMethod.trim().toUpperCase(java.util.Locale.ROOT);
        this.batchSize = Math.min(Math.max(batchSize, 1), HARD_BATCH_LIMIT);
    }

    /**
     * @param creatorId 仅允许来自服务端会话（{@code SecurityContextHolder} → {@code CreatorPrincipal}），
     *                  绝不由请求参数传入。
     */
    public Result erase(String creatorId) {
        String requestId = UUID.randomUUID().toString();
        if (!enabled) {
            return result(Status.DISABLED, requestId, 0, 0, "erasure.enabled=false");
        }
        if (creatorId == null || creatorId.isBlank()) {
            return result(Status.INVALID_REQUEST, requestId, 0, 0, "creatorId missing (login required)");
        }
        Method method;
        try {
            method = Method.valueOf(configuredMethod);
        } catch (IllegalArgumentException e) {
            LOG.warn("analytics.erasure abort: method missing or unknown requestId={}", requestId);
            return result(Status.INVALID_CONFIG, requestId, 0, 0,
                    "erasure.method missing or unknown (法务 L3 未定，禁止擅自选型)");
        }
        if (method == Method.AGGREGATE) {
            // 聚合后保留需要日/小时汇总表与聚合管线，本轮未建表 → 明确不可用，绝不静默降级为删除。
            return result(Status.UNSUPPORTED, requestId, 0, 0,
                    "aggregate requires rollup table (not implemented in this round)");
        }
        // 防误删自检：两条写语句都必须带 creator_id 谓词。
        if (!DELETE_SQL.contains("creator_id = ?") || !ANONYMIZE_SQL.contains("creator_id = ?")) {
            return result(Status.INVALID_CONFIG, requestId, 0, 0, "SQL missing creator_id predicate");
        }

        Long matched = jdbc.queryForObject(COUNT_SQL, Long.class, creatorId);
        long total = matched == null ? 0L : matched;
        long affected = 0;
        try {
            while (true) {
                List<String> ids = jdbc.queryForList(SELECT_IDS_SQL, String.class, creatorId, batchSize);
                if (ids.isEmpty()) break;
                for (String id : ids) {
                    affected += method == Method.DELETE
                            ? jdbc.update(DELETE_SQL, id, creatorId)
                            : jdbc.update(ANONYMIZE_SQL, ANONYMIZED, ANONYMIZED, id, creatorId);
                }
                if (ids.size() < batchSize) break;
            }
            LOG.info("analytics.erasure success requestId={} method={} matched={} affected={}",
                    requestId, method, total, affected);
            return result(Status.SUCCESS, requestId, total, affected, "ok");
        } catch (RuntimeException e) {
            LOG.error("analytics.erasure failed requestId={} method={} affectedBeforeFailure={} type={}",
                    requestId, method, affected, e.getClass().getSimpleName(), e);
            return result(Status.FAILED, requestId, total, affected, e.getClass().getSimpleName());
        }
    }

    private Result result(Status status, String requestId, long matched, long affected, String reason) {
        return new Result(status, requestId, configuredMethod.isEmpty() ? "(unset)" : configuredMethod,
                matched, affected, reason);
    }
}
