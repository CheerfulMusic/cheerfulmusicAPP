package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.LyricFreeAllowance;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the existing usage reservation table so the free grant follows the same work and owner lifecycle. */
public final class JdbcLyricFreeAllowance implements LyricFreeAllowance {
    private static final String GRANT = "LYRIC_GRANT";
    private static final String REDEEM = "LYRIC_REDEEM";
    private static final String INITIAL = "00000000-0000-0000-0000-000000000000";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public JdbcLyricFreeAllowance(JdbcTemplate jdbc, Clock clock, ZoneId zone) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
    }

    @Override public boolean reserve(String owner, String work, String requestKey) {
        requireOwner(owner, work, false);
        requireKey(requestKey);
        if (count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=? AND owner_id=?",
                REDEEM, work, requestKey, owner) > 0) return true;
        addInitialGrantIfEligible(owner, work);
        if (!hasAvailableGrant(work)) return false;
        insert(REDEEM, owner, work, requestKey);
        return true;
    }

    @Override public void release(String work, String requestKey) {
        requireKey(requestKey);
        jdbc.update("DELETE FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?", REDEEM, work, requestKey);
    }

    @Override public void grantMusic(String owner, String work, String taskId) {
        requireOwner(owner, work, true);
        requireKey(taskId);
        if (count("SELECT COUNT(*) FROM generation_task WHERE id=? AND work_id=? AND status='SUCCEEDED'", taskId, work) != 1)
            throw new DomainException("MUSIC_GRANT_UNAVAILABLE", "音乐任务尚未成功，不能刷新免费歌词次数。");
        if (count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?", GRANT, work, taskId) == 0)
            insert(GRANT, owner, work, taskId);
    }

    @Override public boolean freeAvailable(String owner, String work) {
        requireOwner(owner, work, false);
        return hasAvailableGrant(work) || initialGrantEligible(work);
    }

    private void addInitialGrantIfEligible(String owner, String work) {
        if (initialGrantEligible(work)) insert(GRANT, owner, work, INITIAL);
    }

    private boolean initialGrantEligible(String work) {
        return count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?", GRANT, work, INITIAL) == 0
            && count("SELECT COUNT(*) FROM lyrics_composition_request WHERE work_id=? AND status IN ('PENDING','UNKNOWN','SUCCEEDED')", work) == 0;
    }

    private boolean hasAvailableGrant(String work) {
        long grants = count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=?", GRANT, work);
        long redeemed = count("SELECT COUNT(*) FROM model_usage_reservation WHERE kind=? AND work_id=?", REDEEM, work);
        return grants > redeemed;
    }

    private void insert(String kind, String owner, String work, String key) {
        LocalDate day = LocalDate.now(clock.withZone(zone));
        jdbc.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES(?,?,?,?,?,?)",
            kind, work, key, owner, java.sql.Date.valueOf(day), Timestamp.from(clock.instant()));
    }

    private void requireOwner(String owner, String work, boolean allowDeleted) {
        String sql = "SELECT COUNT(*) FROM music_work WHERE id=? AND owner_id=?" + (allowDeleted ? "" : " AND deleted_at IS NULL");
        if (owner == null || work == null || count(sql, work, owner) != 1)
            throw new DomainException("CREATOR_SESSION_CHANGED", "作品归属已改变，请刷新后重试。");
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("Invalid request key");
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
