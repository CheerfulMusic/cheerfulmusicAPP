package com.qingfeng.music.infrastructure.persistence.cover;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Cover jobs use private outbox states so the general event dispatcher cannot send them. */
public final class JdbcAutomaticCoverJobs {
    private static final String TOPIC = "music.cover";
    private static final int MAX_ARCHIVE_ATTEMPTS = 4;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public record Job(String id, String task, String work, String owner, String lyrics, String style,
                      String state, String url, int attempts, String token) {}

    public JdbcAutomaticCoverJobs(JdbcTemplate db, TransactionTemplate transactions) {
        this.db = db;
        this.tx = new TransactionTemplate(transactions.getTransactionManager());
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // Both submission and successful archive may enqueue. The unique event key keeps one job.
    public void enqueue(String task) {
        db.update("""
                INSERT INTO task_event_outbox(id,topic,event_key,payload,state,next_attempt_at,created_at)
                SELECT ?,?,t.id,'','COVER_QUEUED',
                    CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                FROM generation_task t JOIN music_work w ON w.id=t.work_id
                LEFT JOIN work_cover c ON c.work_id=w.id
                WHERE t.id=? AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SUBMISSION_UNKNOWN','SAVING','SUCCEEDED')
                  AND w.deleted_at IS NULL AND c.cover_id IS NULL AND COALESCE(c.version,0)=0 AND TRIM(t.lyrics)<>''
                  AND NOT EXISTS (SELECT 1 FROM section_edit s WHERE s.task_id=t.id)
                ON DUPLICATE KEY UPDATE id=task_event_outbox.id
                """, UUID.randomUUID().toString(), TOPIC, task);
    }

    static boolean pending(JdbcTemplate db, String work) {
        return !db.queryForList("""
                SELECT 1 FROM task_event_outbox e JOIN generation_task t ON t.id=e.event_key
                WHERE t.work_id=? AND e.topic=? AND e.state IN ('COVER_WAIT','COVER_QUEUED','COVER_SUBMIT','COVER_SAVING')
                  AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SUBMISSION_UNKNOWN','SAVING','SUCCEEDED')
                LIMIT 1
                """, Integer.class, work, TOPIC).isEmpty();
    }

    public Optional<Job> claim(int dailyLimit, Instant now) {
        return tx.execute(status -> {
            // Serialize the daily provider-call budget across local workers and instances.
            db.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='COVER' FOR UPDATE", String.class);
            // Resume legacy waiting jobs as soon as the music task is active.
            db.update("""
                    UPDATE task_event_outbox SET state='COVER_QUEUED'
                    WHERE topic=? AND (state='COVER_WAIT'
                        OR (state='COVER_SKIPPED' AND last_error='MUSIC_NOT_COMPLETED')) AND event_key IN (
                        SELECT id FROM generation_task
                        WHERE status IN ('QUEUED','SUBMITTING','RUNNING','SUBMISSION_UNKNOWN','SAVING','SUCCEEDED')
                    )
                    """, TOPIC);
            db.update("""
                    UPDATE task_event_outbox SET state='COVER_SKIPPED',last_error='MUSIC_NOT_COMPLETED'
                    WHERE topic=? AND state IN ('COVER_WAIT','COVER_QUEUED') AND event_key IN (
                        SELECT t.id FROM generation_task t JOIN music_work w ON w.id=t.work_id
                        WHERE t.status IN ('FAILED','CANCELLED') OR w.deleted_at IS NOT NULL
                    )
                    """, TOPIC);
            db.update("""
                    UPDATE task_event_outbox SET state='COVER_UNKNOWN',last_error='SUBMISSION_UNKNOWN',
                        lease_token=NULL,lease_until=NULL
                    WHERE topic=? AND state='COVER_SUBMIT' AND lease_until<=CURRENT_TIMESTAMP
                    """, TOPIC);
            var jobs = db.query("""
                    SELECT e.id,t.id,t.work_id,w.owner_id,t.lyrics,t.style,e.state,e.payload,e.attempts
                    FROM task_event_outbox e JOIN generation_task t ON t.id=e.event_key
                    JOIN music_work w ON w.id=t.work_id
                    WHERE e.topic=? AND e.state IN ('COVER_QUEUED','COVER_SAVING')
                      AND e.next_attempt_at<=CURRENT_TIMESTAMP
                      AND (e.lease_until IS NULL OR e.lease_until<=CURRENT_TIMESTAMP)
                    ORDER BY e.next_attempt_at,e.id LIMIT 1
                    """, (row, index) -> new Job(row.getString(1), row.getString(2), row.getString(3),
                    row.getString(4), row.getString(5), row.getString(6), row.getString(7),
                    row.getString(8), row.getInt(9), UUID.randomUUID().toString()),
                    TOPIC);
            if (jobs.isEmpty()) return Optional.empty();
            var job = jobs.get(0);
            if (!eligible(job)) {
                skip(job, "COVER_NO_LONGER_NEEDED");
                return Optional.empty();
            }
            boolean submission = "COVER_QUEUED".equals(job.state());
            if (submission && db.queryForObject("""
                    SELECT COUNT(*) FROM task_event_outbox WHERE topic=? AND published_at>=FROM_UNIXTIME(?)
                    """, Long.class, TOPIC, now.truncatedTo(ChronoUnit.DAYS).getEpochSecond()) >= dailyLimit) {
                skip(job, "DAILY_LIMIT");
                return Optional.empty();
            }
            int claimed = db.update("""
                    UPDATE task_event_outbox SET state=?,lease_token=?,
                        lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP),
                        published_at=COALESCE(published_at,CURRENT_TIMESTAMP)
                    WHERE id=? AND state=? AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)
                    """, submission ? "COVER_SUBMIT" : job.state(), job.token(), job.id(), job.state());
            return claimed == 1 ? Optional.of(job) : Optional.empty();
        });
    }

    private void skip(Job job, String reason) {
        db.update("""
                UPDATE task_event_outbox SET state='COVER_SKIPPED',last_error=?,payload='',
                    lease_token=NULL,lease_until=NULL WHERE id=? AND state=?
                """, reason, job.id(), job.state());
    }

    private boolean eligible(Job job) {
        return !db.queryForList("""
                SELECT 1 FROM music_work w LEFT JOIN work_cover c ON c.work_id=w.id
                JOIN generation_task t ON t.work_id=w.id
                WHERE w.id=? AND w.owner_id=? AND w.deleted_at IS NULL
                  AND c.cover_id IS NULL AND COALESCE(c.version,0)=0
                  AND t.id=? AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SUBMISSION_UNKNOWN','SAVING','SUCCEEDED')
                  AND NOT EXISTS (
                      SELECT 1 FROM generation_task newer WHERE newer.work_id=t.work_id
                        AND newer.status IN ('QUEUED','SUBMITTING','RUNNING','SUBMISSION_UNKNOWN','SAVING','SUCCEEDED')
                        AND (newer.created_at>t.created_at OR (newer.created_at=t.created_at AND newer.id>t.id))
                        AND NOT EXISTS (SELECT 1 FROM section_edit s WHERE s.task_id=newer.id)
                  )
                """, Integer.class, job.work(), job.owner(), job.task()).isEmpty();
    }

    public void accepted(Job job, String url) {
        update(job, "COVER_SUBMIT", "COVER_SAVING", null, url, 0, 0);
    }

    public void rejected(Job job, String reason) {
        update(job, "COVER_SUBMIT", "COVER_FAILED", reason, "", 0, 0);
    }

    public void unknown(Job job) {
        update(job, "COVER_SUBMIT", "COVER_UNKNOWN", "SUBMISSION_UNKNOWN", "", 0, 0);
    }

    public void archiveFailed(Job job, boolean permanent) {
        int attempts = job.attempts() + 1;
        boolean terminal = permanent || attempts >= MAX_ARCHIVE_ATTEMPTS;
        update(job, "COVER_SAVING", terminal ? "COVER_FAILED" : "COVER_SAVING",
                permanent ? "INVALID_IMAGE" : "ARCHIVE_FAILED", terminal ? "" : job.url(),
                attempts, 15 * attempts);
    }

    private void update(Job job, String expected, String state, String error, String payload, int attempts, int delay) {
        db.update("""
                UPDATE task_event_outbox SET state=?,last_error=?,payload=?,attempts=?,
                    next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),
                    lease_token=NULL,lease_until=NULL WHERE id=? AND state=? AND lease_token=?
                """, state, error, payload, attempts, delay, job.id(), expected, job.token());
    }

    public void finish(Job job, String cover) {
        tx.executeWithoutResult(status -> {
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE", job.owner());
            db.queryForList("SELECT id FROM music_work WHERE id=? FOR UPDATE", job.work());
            if (db.queryForList("""
                    SELECT id FROM task_event_outbox
                    WHERE id=? AND state='COVER_SAVING' AND lease_token=? FOR UPDATE
                    """, job.id(), job.token()).isEmpty()) return;
            if (eligible(job)) {
                db.update("""
                        INSERT INTO work_cover(work_id,version) VALUES(?,0)
                        ON DUPLICATE KEY UPDATE work_id=work_id
                        """, job.work());
                int selected = db.update("""
                        UPDATE work_cover SET cover_id=?,version=version+1
                        WHERE work_id=? AND version=0 AND cover_id IS NULL
                        """, cover, job.work());
                if (selected == 1) db.update("""
                        UPDATE artist_song_release SET cover_id=?
                        WHERE creator_id=? AND work_id=? AND show_on_profile=TRUE AND cover_id IS NULL
                          AND asset_id IN (SELECT id FROM media_asset WHERE work_id=? AND generation_task_id=?)
                        """, cover, job.owner(), job.work(), job.work(), job.task());
            }
            update(job, "COVER_SAVING", "COVER_DONE", null, "", job.attempts(), 0);
        });
    }
}
