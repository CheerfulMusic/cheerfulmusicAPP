package com.qingfeng.music.infrastructure.persistence.ai;

import com.qingfeng.music.common.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The job and both permits live on the primary. No provider calls run inside these transactions. */
public final class JdbcAiWorkQueue {
    public enum Lane { LYRICS, COVER, AGENT }
    public enum Stage { START, POLL, ARCHIVE }
    public enum State {
        QUEUED, RUNNING, WAITING, SUSPENDED, SUCCEEDED, FAILED, UNKNOWN;
        public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == UNKNOWN; }
    }
    public record Limits(int running, int backlog, Duration maxWait) {
        public Limits {
            if (running < 1 || running > 1000 || backlog < 0 || backlog > 1000
                    || maxWait.isNegative() || maxWait.isZero()) {
                throw new IllegalArgumentException("Invalid AI queue limits");
            }
        }
    }
    public record Job(String id, Lane lane, String scope, String requestKey, String owner, String fingerprint,
                      String payload, State state, Stage stage, String errorCode, String token, Instant queueUntil) {}
    public record Counts(long queued, long running, long unknown) {}
    public record Recovery(State state, Stage next, String error) {
        public static Recovery unknown() { return new Recovery(State.UNKNOWN, null, "AI_OUTCOME_UNKNOWN"); }
        public static Recovery success() { return new Recovery(State.SUCCEEDED, null, null); }
        public static Recovery resume(Stage stage) { return new Recovery(State.WAITING, stage, null); }
    }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Duration lease;
    private static final String COLUMNS = "id,lane,scope_id,request_key,owner_id,fingerprint,payload,state,stage,error_code,lease_token,queue_until";

    public JdbcAiWorkQueue(JdbcTemplate db, TransactionTemplate transactions, Duration lease) {
        this.db = db;
        this.tx = new TransactionTemplate(transactions.getTransactionManager());
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
        this.lease = lease;
    }

    public void requireSchema() {
        int executionSlots = Objects.requireNonNull(db.queryForObject(
            "SELECT COUNT(*) FROM ai_capacity_slot WHERE purpose='EXECUTE'", Integer.class));
        int admissionSlots = Objects.requireNonNull(db.queryForObject(
            "SELECT COUNT(*) FROM ai_capacity_slot WHERE purpose='ACTIVE'", Integer.class));
        db.queryForList("SELECT id,dependency_id FROM ai_capacity_job WHERE 1=0");
        db.queryForList("SELECT account_scope FROM ai_capacity_model_usage WHERE 1=0");
        if (executionSlots != 3000 || admissionSlots != 6000)
            throw new IllegalStateException("AI capacity requires the complete V57 migration");
    }

    public Optional<Job> find(Lane lane, String owner, String scope, String requestKey) {
        return db.query("SELECT " + COLUMNS + " FROM ai_capacity_job WHERE id=? AND owner_id=?",
            JdbcAiWorkQueue::job, identity(lane, scope, requestKey), owner).stream().findFirst();
    }

    public List<Job> statuses(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        String fields = COLUMNS.replace("payload", "NULL AS payload");
        return db.query("SELECT " + fields + " FROM ai_capacity_job WHERE id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", JdbcAiWorkQueue::job, ids.toArray());
    }

    public Job submit(Lane lane, String owner, String scope, String requestKey, String payload, Limits limits,
                      Consumer<Job> onAccept) {
        return submit(lane, owner, scope, requestKey, payload, payload, limits, onAccept);
    }

    public Job submit(Lane lane, String owner, String scope, String requestKey, String payload, String fingerprintMaterial,
                      Limits limits, Consumer<Job> onAccept) {
        Objects.requireNonNull(payload);
        if (payload.length() > 100_000) throw new DomainException("INVALID_AI_REQUEST", "创作请求内容过长。");
        String fingerprint = hash(fingerprintMaterial);
        String id = identity(lane, scope, requestKey);
        return tx.execute(status -> {
            lockOwner(owner);
            var prior = find(lane, owner, scope, requestKey);
            if (prior.isPresent()) {
                if (!fingerprint.equals(prior.get().fingerprint()))
                    throw new DomainException("REQUEST_KEY_CONFLICT", "同一请求标识不能用于不同内容。");
                return prior.get();
            }
            if (!db.queryForList("SELECT id FROM ai_capacity_job WHERE lane=? AND active_owner=?", String.class, lane.name(), owner).isEmpty())
                throw new DomainException("PERSONAL_GENERATION_BUSY", "你的上一项请求仍在排队或执行。");
            if (!take(lane, "ACTIVE", limits.running() + limits.backlog(), id))
                throw new DomainException("AI_GENERATION_BUSY", "当前请求较多，请稍后再试。");

            Instant now = now();
            Instant queueUntil = now.plus(limits.maxWait());
            db.update("""
                INSERT INTO ai_capacity_job(id,lane,scope_id,request_key,owner_id,active_owner,fingerprint,payload,
                    state,stage,created_at,updated_at,queue_until,next_attempt_at)
                VALUES(?,?,?,?,?,?,?,?,'QUEUED','START',?,?,?,?)
                """, id, lane.name(), scope, requestKey, owner, owner, fingerprint, payload,
                at(now), at(now), at(queueUntil), at(now));
            var job = new Job(id, lane, scope, requestKey, owner, fingerprint, payload, State.QUEUED, Stage.START, null, null, queueUntil);
            onAccept.accept(job);
            return job;
        });
    }

    public Optional<Job> claim(Lane lane, Stage stage, Limits limits) {
        return tx.execute(status -> {
            Instant now = now();
            // Separate indexed scans keep MySQL from filesorting and locking unrelated candidates.
            var candidates = ready(lane, stage, State.WAITING);
            if (candidates.isEmpty()) candidates = ready(lane, stage, State.QUEUED);
            if (candidates.isEmpty()) return Optional.empty();
            var candidate = candidates.get(0);
            if (candidate.state() == State.QUEUED && !take(lane, "EXECUTE", limits.running(), candidate.id()))
                return Optional.empty();
            String token = UUID.randomUUID().toString();
            db.update("""
                UPDATE ai_capacity_job SET state='RUNNING',lease_token=?,lease_until=?,updated_at=? WHERE id=?
                """, token, at(now.plus(lease)), at(now), candidate.id());
            return Optional.of(new Job(candidate.id(), lane, candidate.scope(), candidate.requestKey(), candidate.owner(),
                candidate.fingerprint(), candidate.payload(), State.RUNNING, stage, null, token, candidate.queueUntil()));
        });
    }

    private List<Job> ready(Lane lane, Stage stage, State state) {
        String beforeExpiry = state == State.QUEUED ? " AND queue_until>CURRENT_TIMESTAMP(6)" : "";
        return db.query("SELECT " + COLUMNS + " " + """
            FROM ai_capacity_job WHERE lane=? AND stage=? AND state=? AND next_attempt_at<=CURRENT_TIMESTAMP(6)
            """ + beforeExpiry + " ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
            JdbcAiWorkQueue::job, lane.name(), stage.name(), state.name());
    }

    public void nudgeCoverPoll(String id) {
        db.update("""
            UPDATE ai_capacity_job SET next_attempt_at=CURRENT_TIMESTAMP(6)
            WHERE id=? AND lane='COVER' AND state='WAITING' AND stage='POLL' AND next_attempt_at>CURRENT_TIMESTAMP(6)
            """, id);
    }

    /** Polling and archive work give up the local thread and retain the global execution permit. */
    public void waitFor(Job job, Stage stage, Duration delay) {
        tx.executeWithoutResult(status -> db.update("""
            UPDATE ai_capacity_job SET state='WAITING',stage=?,next_attempt_at=?,updated_at=CURRENT_TIMESTAMP,
                lease_token=NULL,lease_until=NULL
            WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
            """, stage.name(), at(now().plus(delay)), job.id(), job.token()));
    }

    public boolean suspendForLyrics(Job agent, String work, String requestKey) {
        if (agent.lane() != Lane.AGENT) throw new IllegalArgumentException("A parent Agent job is required");
        return Boolean.TRUE.equals(tx.execute(status -> {
            int parked = db.update("""
                UPDATE ai_capacity_job SET state='SUSPENDED',dependency_id=?,updated_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
                """, identity(Lane.LYRICS, work, requestKey), agent.id(), agent.token());
            if (parked == 1) releaseExecution(agent);
            return parked == 1;
        }));
    }

    public boolean resumeAgent(Job agent, Limits limits) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            var valid = db.queryForList("""
                SELECT id FROM ai_capacity_job WHERE id=? AND state='SUSPENDED' AND lease_token=?
                    AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE
                """, String.class, agent.id(), agent.token());
            if (valid.isEmpty() || !take(agent.lane(), "EXECUTE", limits.running(), agent.id())) return false;
            db.update("""
                UPDATE ai_capacity_job SET state='RUNNING',dependency_id=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?
                """, agent.id());
            return true;
        }));
    }

    public boolean renew(Job job) {
        return renewAll(List.of(job)) == 1;
    }

    public int renewAll(Collection<Job> jobs) {
        if (jobs.isEmpty()) return 0;
        var arguments = new ArrayList<Object>(jobs.size() * 2 + 1);
        arguments.add(lease.toMillis() * 1000);
        jobs.forEach(job -> { arguments.add(job.id()); arguments.add(job.token()); });
        return db.update("""
            UPDATE ai_capacity_job SET lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))
            WHERE state IN ('RUNNING','SUSPENDED') AND lease_until>CURRENT_TIMESTAMP(6)
              AND (id,lease_token) IN (
            """ + String.join(",", Collections.nCopies(jobs.size(), "(?,?)")) + ")", arguments.toArray());
    }

    public void finish(Job job, State state, String errorCode) {
        if (!state.terminal())
            throw new IllegalArgumentException("A terminal job state is required");
        tx.executeWithoutResult(status -> {
            int changed = db.update("""
                UPDATE ai_capacity_job SET state=?,error_code=?,active_owner=NULL,payload=NULL,
                    updated_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL
                WHERE id=? AND state IN ('RUNNING','SUSPENDED') AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(6)
                """, state.name(), errorCode, job.id(), job.token());
            if (changed == 1) release(job);
        });
    }

    /** Recovery is bounded. An interrupted submission is handed to the domain for reconciliation. */
    public boolean recoverOne(Lane lane, Consumer<Job> onExpire, Function<Job, Recovery> onInterrupted) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = now();
            String due = """
                 lane=? AND ((state='QUEUED' AND queue_until<=?) OR (state IN ('RUNNING','SUSPENDED') AND lease_until<=?))
                """;
            var candidates = db.query("SELECT " + COLUMNS + " FROM ai_capacity_job WHERE " + due
                + " ORDER BY updated_at,id LIMIT 1", JdbcAiWorkQueue::job, lane.name(), at(now), at(now));
            if (candidates.isEmpty()) return false;
            // Settlement and new requests take the same owner lock before touching the job.
            var candidate = candidates.get(0);
            lockOwner(candidate.owner());
            var expired = db.query("SELECT " + COLUMNS + " FROM ai_capacity_job WHERE id=? AND " + due
                + " FOR UPDATE", JdbcAiWorkQueue::job, candidate.id(), lane.name(), at(now), at(now));
            if (expired.isEmpty()) return true;
            var job = expired.get(0);
            Recovery resolution;
            if (job.state() == State.QUEUED) {
                onExpire.accept(job);
                resolution = new Recovery(State.FAILED, null, "AI_QUEUE_EXPIRED");
            } else if (job.stage() == Stage.START) {
                resolution = onInterrupted.apply(job);
            } else {
                resolution = Recovery.resume(job.stage());
            }
            if (resolution.next() != null) {
                db.update("""
                    UPDATE ai_capacity_job SET state='WAITING',stage=?,lease_token=NULL,lease_until=NULL,next_attempt_at=?
                    WHERE id=?
                    """, resolution.next().name(), at(now), job.id());
            } else {
                db.update("""
                    UPDATE ai_capacity_job SET state=?,error_code=?,active_owner=NULL,payload=NULL,
                        updated_at=?,lease_token=NULL,lease_until=NULL WHERE id=?
                    """, resolution.state().name(), resolution.error(), at(now), job.id());
                release(job);
            }
            return true;
        }));
    }

    public Optional<Job> lyricsDependency(Job agent) {
        return db.query("SELECT " + COLUMNS + " " + """
             FROM ai_capacity_job WHERE id=(SELECT dependency_id FROM ai_capacity_job WHERE id=?)
                 AND owner_id=? AND lane='LYRICS'
            """, JdbcAiWorkQueue::job, agent.id(), agent.owner()).stream().findFirst();
    }

    public Counts counts(Lane lane) {
        return db.queryForObject("""
            SELECT COUNT(CASE WHEN state='QUEUED' THEN 1 END),
                   COUNT(CASE WHEN state IN ('RUNNING','WAITING') THEN 1 END),
                   COUNT(CASE WHEN state='UNKNOWN' THEN 1 END)
            FROM ai_capacity_job WHERE lane=? AND state IN ('QUEUED','RUNNING','WAITING','UNKNOWN')
            """, (row, i) -> new Counts(row.getLong(1), row.getLong(2), row.getLong(3)), lane.name());
    }

    private void lockOwner(String owner) {
        if (db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, owner).isEmpty())
            throw new DomainException("AI_ACCOUNT_NOT_FOUND", "创作账号不存在。");
    }

    private boolean take(Lane lane, String purpose, int limit, String job) {
        var free = db.queryForList("""
            SELECT slot_no FROM ai_capacity_slot
             WHERE lane=? AND purpose=? AND job_id IS NULL AND slot_no<?
             ORDER BY slot_no LIMIT 1 FOR UPDATE SKIP LOCKED
            """, Integer.class, lane.name(), purpose, limit);
        if (free.isEmpty()) return false;
        db.update("UPDATE ai_capacity_slot SET job_id=? WHERE lane=? AND purpose=? AND slot_no=?",
            job, lane.name(), purpose, free.get(0));
        return true;
    }

    private void release(Job job) {
        releaseExecution(job);
        db.update("UPDATE ai_capacity_slot SET job_id=NULL WHERE lane=? AND purpose='ACTIVE' AND job_id=?",
            job.lane().name(), job.id());
    }

    private void releaseExecution(Job job) {
        db.update("UPDATE ai_capacity_slot SET job_id=NULL WHERE lane=? AND purpose='EXECUTE' AND job_id=?",
            job.lane().name(), job.id());
    }

    private Instant now() {
        return Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class)).toInstant();
    }

    private static Timestamp at(Instant time) {
        return Timestamp.from(time);
    }

    private static Job job(ResultSet row, int index) throws SQLException {
        return new Job(row.getString("id"), Lane.valueOf(row.getString("lane")), row.getString("scope_id"),
            row.getString("request_key"), row.getString("owner_id"), row.getString("fingerprint"),
            row.getString("payload"), State.valueOf(row.getString("state")), Stage.valueOf(row.getString("stage")),
            row.getString("error_code"), row.getString("lease_token"), row.getTimestamp("queue_until").toInstant());
    }

    public static String identity(Lane lane, String scope, String requestKey) {
        return hash(lane.name() + ":" + scope + ":" + requestKey);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
