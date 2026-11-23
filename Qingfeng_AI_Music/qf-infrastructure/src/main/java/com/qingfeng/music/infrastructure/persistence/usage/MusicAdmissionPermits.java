package com.qingfeng.music.infrastructure.persistence.usage;

import java.util.function.IntSupplier;
import org.springframework.jdbc.core.JdbcTemplate;

/** Grants are spent in the same transaction as the task. A rollback returns its grant. */
final class MusicAdmissionPermits {
    static final int LANES = 32;
    private final JdbcTemplate db;
    MusicAdmissionPermits(JdbcTemplate db) { this.db = db; }

    void reset() {
        lockRefill();
        lockLanes();
        db.update("UPDATE music_admission_permit SET remaining=0");
    }
    private void lockRefill() {
        db.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='MUSIC' FOR UPDATE", String.class);
    }
    private void lockLanes() {
        var lanes = db.queryForList("SELECT lane FROM music_admission_permit ORDER BY lane FOR UPDATE", Integer.class);
        if (lanes.size()!=LANES || lanes.get(0)!=0 || lanes.get(LANES-1)!=LANES-1)
            throw new IllegalStateException("Incomplete music admission permit table");
    }
    private boolean spend(int limit) {
        var available = db.queryForList("""
            SELECT lane FROM music_admission_permit WHERE budget_limit=? AND remaining>0
            ORDER BY lane LIMIT 1 FOR UPDATE SKIP LOCKED
            """, Integer.class, limit);
        if (!available.isEmpty()) {
            db.update("UPDATE music_admission_permit SET remaining=remaining-1 WHERE lane=?", available.get(0));
            return true;
        }
        return false;
    }
    boolean take(int limit, IntSupplier active) {
        if (spend(limit)) return true;
        lockRefill();
        if (spend(limit)) return true;
        // Drain in-flight admissions before counting committed tasks. Only refills scan the queue.
        lockLanes();
        int grants = Math.min(512, limit-active.getAsInt());
        if (grants<=0) return false;
        int left=grants-1;
        db.update("""
            UPDATE music_admission_permit SET budget_limit=?,remaining=?+CASE WHEN lane<? THEN 1 ELSE 0 END
            """,limit,left/LANES,left%LANES);
        return true;
    }
}
