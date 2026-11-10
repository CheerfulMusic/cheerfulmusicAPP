package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.DailyQuota;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 每日次数配额落库。跨实例共享，按天重置。
 *
 * 占用额度用「先保证当日行存在，再条件自增」实现：条件自增同时给行加锁，
 * 并发下不会超发；额度已用尽时影响 0 行，调用方据此拒绝请求。
 */
public final class JdbcDailyQuota implements DailyQuota {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    public JdbcDailyQuota(JdbcTemplate jdbc, Clock clock, ZoneId zone) { this.jdbc = jdbc; this.clock = clock; this.zone = zone; }

    public boolean consume(String owner, String bucket, long limit) {
        if (limit < 0) return true;
        if (limit == 0) return false;
        Date day = today();
        try {
            jdbc.update("INSERT INTO daily_quota(creator_id,bucket,usage_day,used_count,updated_at) VALUES(?,?,?,0,CURRENT_TIMESTAMP)", owner, bucket, day);
        } catch (DuplicateKeyException duplicate) {
            // 当日行已存在，由下面的条件自增处理。
        }
        return jdbc.update("UPDATE daily_quota SET used_count=used_count+1,updated_at=CURRENT_TIMESTAMP WHERE creator_id=? AND bucket=? AND usage_day=? AND used_count<?", owner, bucket, day, limit) == 1;
    }

    public long remaining(String owner, String bucket, long limit) {
        if (limit < 0) return Long.MAX_VALUE;
        if (limit == 0) return 0L;
        var used = jdbc.query("SELECT used_count FROM daily_quota WHERE creator_id=? AND bucket=? AND usage_day=?", (rs, n) -> rs.getInt(1), owner, bucket, today());
        return Math.max(0L, limit - (used.isEmpty() ? 0 : used.get(0)));
    }

    private Date today() { return Date.valueOf(LocalDate.now(clock.withZone(zone))); }
}
