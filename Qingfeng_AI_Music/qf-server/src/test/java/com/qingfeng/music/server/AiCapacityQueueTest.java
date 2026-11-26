package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Limits;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Recovery;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import com.qingfeng.music.server.ai.AiTextQuota;
import com.qingfeng.music.server.ai.AiCapacity;
import com.qingfeng.music.server.ai.AiCapacityWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AiCapacityQueueTest {
    private static final Limits SMALL = new Limits(1, 1, Duration.ofMinutes(10));

    @Test void reserveAndRetryAreAtomicAndClaimsAreFenced() {
        DataSource data = new DriverManagerDataSource("jdbc:h2:mem:ai_" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = schema(data);
        var queue = queue(data);
        var otherNode = queue(data);
        String owner = creator(db);
        String rejectedOwner = creator(db);
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> queue.submit(Lane.COVER, rejectedOwner, rejectedOwner, key, "photo", SMALL, ignored -> {
            db.update("INSERT INTO reserved_credit(owner_id) VALUES(?)", rejectedOwner);
            throw new DomainException("INSUFFICIENT_CREDITS", "fixture");
        })).isInstanceOf(DomainException.class);
        assertThat(queue.find(Lane.COVER, rejectedOwner, rejectedOwner, key)).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reserved_credit", Integer.class)).isZero();

        var original = queue.submit(Lane.COVER, owner, owner, key, "photo", SMALL,
            ignored -> db.update("INSERT INTO reserved_credit(owner_id) VALUES(?)", owner));
        var replay = otherNode.submit(Lane.COVER, owner, owner, key, "photo", SMALL,
            ignored -> { throw new AssertionError("replay charged again"); });
        assertThat(replay.id()).isEqualTo(original.id());
        assertThatThrownBy(() -> otherNode.submit(Lane.COVER, owner, owner, key, "different photo", SMALL, ignored -> {}))
            .isInstanceOfSatisfying(DomainException.class, failure -> assertThat(failure.code()).isEqualTo("REQUEST_KEY_CONFLICT"));

        var first = queue.claim(Lane.COVER, Stage.START, SMALL).orElseThrow();
        queue.waitFor(first, Stage.POLL, Duration.ZERO);
        var second = otherNode.claim(Lane.COVER, Stage.POLL, SMALL).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(otherNode.renew(first)).isFalse();
        queue.finish(first, State.FAILED, "STALE_WORKER");
        assertThat(queue.counts(Lane.COVER).running()).isEqualTo(1);

        db.update("UPDATE ai_capacity_job SET lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?", second.id());
        assertThat(queue.recoverOne(Lane.COVER, ignored -> { throw new AssertionError(); },
            ignored -> { throw new AssertionError("poll is safe to retry"); })).isTrue();
        var recovered = queue.claim(Lane.COVER, Stage.POLL, SMALL).orElseThrow();
        queue.finish(recovered, State.SUCCEEDED, null);
        assertThat(otherNode.find(Lane.COVER, owner, owner, key).orElseThrow().state()).isEqualTo(State.SUCCEEDED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_capacity_slot WHERE job_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test void queueExpiryAndUnknownProviderAcceptanceReleaseBothPermits() {
        DataSource data = new DriverManagerDataSource("jdbc:h2:mem:ai_" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = schema(data);
        var queue = queue(data);
        String firstOwner = creator(db), secondOwner = creator(db);
        var first = queue.submit(Lane.LYRICS, firstOwner, firstOwner, firstOwner, "lyrics", SMALL, ignored -> {});
        var second = queue.submit(Lane.LYRICS, secondOwner, secondOwner, secondOwner, "lyrics", SMALL, ignored -> {});
        // Equal due times are ordered by job id. Make the intended running request eligible first.
        db.update("UPDATE ai_capacity_job SET next_attempt_at=DATEADD('SECOND',-1,next_attempt_at) WHERE id=?", first.id());
        var running = queue.claim(Lane.LYRICS, Stage.START, SMALL).orElseThrow();
        assertThat(running.id()).isEqualTo(first.id());
        assertThat(queue.claim(Lane.LYRICS, Stage.START, SMALL)).isEmpty();
        db.update("UPDATE ai_capacity_job SET queue_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?", second.id());
        assertThat(queue.recoverOne(Lane.LYRICS, job -> assertThat(job.id()).isEqualTo(second.id()),
            ignored -> { throw new AssertionError(); })).isTrue();
        db.update("UPDATE ai_capacity_job SET lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE id=?", first.id());
        assertThat(queue.recoverOne(Lane.LYRICS, ignored -> { throw new AssertionError(); }, ignored -> Recovery.unknown())).isTrue();
        assertThat(queue.claim(Lane.LYRICS, Stage.START, SMALL)).isEmpty();
        assertThat(queue.find(Lane.LYRICS, firstOwner, firstOwner, firstOwner).orElseThrow().state()).isEqualTo(State.UNKNOWN);
        assertThat(queue.find(Lane.LYRICS, secondOwner, secondOwner, secondOwner).orElseThrow().errorCode()).isEqualTo("AI_QUEUE_EXPIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_capacity_slot WHERE job_id IS NOT NULL", Integer.class)).isZero();
    }

    @Test void sharedModelAccountsReserveAndSettleQuotaAcrossInstances() {
        DataSource data = new DriverManagerDataSource("jdbc:h2:mem:ai_" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = schema(data);
        var env = new MockEnvironment()
            .withProperty("qingfeng.ai.capacity.text.qwen.rpm", "2")
            .withProperty("qingfeng.ai.capacity.text.qwen.tpm", "100")
            .withProperty("qingfeng.ai.capacity.text.qwen.daily-tokens", "100");
        var first = new AiTextQuota(db, tx(data), env);
        var second = new AiTextQuota(db, tx(data), env);
        var endpoint = URI.create("https://example.test/v1/chat/completions");
        first.reserve("qwen", endpoint, 90).accept(30);
        assertThatThrownBy(() -> second.reserve("qwen", endpoint, 71)).isInstanceOfSatisfying(DomainException.class,
            failure -> assertThat(failure.code()).isEqualTo("AI_PROVIDER_RATE_LIMITED"));
        second.reserve("qwen", endpoint, 60).accept(20);
        assertThat(db.queryForObject("SELECT requests FROM ai_capacity_model_usage WHERE period_kind='DAY'", Long.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT tokens FROM ai_capacity_model_usage WHERE period_kind='DAY'", Long.class)).isEqualTo(50);
    }

    @Test void mysqlAdmitsAndLeasesOneThousandInEachLaneAtOnce() throws Exception {
        String expectedUuid = System.getProperty("qf.ai.isolated-mysql-uuid", "");
        assumeTrue(expectedUuid.matches("[0-9a-fA-F-]{36}"), "Isolated MySQL test is opt-in");
        String name = "qf_ai_capacity_" + System.currentTimeMillis();
        String address = "jdbc:mysql://127.0.0.1:57857/";
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(address, "root", ""));
        assertThat(bootstrap.queryForObject("SELECT @@server_uuid", String.class)).isEqualToIgnoringCase(expectedUuid);
        bootstrap.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var config = new HikariConfig();
        config.setJdbcUrl(address + name + "?connectionTimeZone=SERVER");
        config.setUsername("root");
        config.setMaximumPoolSize(40);
        config.setMinimumIdle(12);
        try (var pool = new HikariDataSource(config)) {
            var db = new JdbcTemplate(pool);
            db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            db.execute("CREATE TABLE reserved_credit(owner_id VARCHAR(36) PRIMARY KEY)");
            var migration = org.flywaydb.core.Flyway.configure().dataSource(pool).locations("classpath:db/migration")
                    .baselineVersion("57").target("59").cleanDisabled(true).load();
            migration.baseline();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(migration.migrate().migrationsExecuted).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_capacity_slot", Long.class)).isEqualTo(9000);
            String[] owners = IntStream.range(0, 1003).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
            db.batchUpdate("INSERT INTO creator(id) VALUES(?)", List.of(owners).stream().map(id -> new Object[]{id}).toList());
            var nodes = new JdbcAiWorkQueue[]{queue(pool), queue(pool), queue(pool), queue(pool)};
            var limits = new Limits(1000, 2, Duration.ofMinutes(10));
            var executor = Executors.newFixedThreadPool(36);
            var calls = new ArrayList<Callable<Void>>();
            try {
                for (int i = 0; i < 1002; i++) {
                    String owner = owners[i];
                    var node = nodes[i % nodes.length];
                    for (Lane lane : Lane.values())
                        calls.add(() -> { node.submit(lane, owner, owner, owner, "fixture", limits, ignored -> {}); return null; });
                }
                long start = System.nanoTime();
                for (var future : executor.invokeAll(calls)) future.get();
                long accepted = System.nanoTime();
                for (Lane lane : Lane.values()) {
                    assertThatThrownBy(() -> nodes[0].submit(lane, owners[1002], owners[1002], owners[1002],
                        "overflow", limits, ignored -> {})).isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.code()).isEqualTo("AI_GENERATION_BUSY"));
                }

                if (Boolean.getBoolean("qf.ai.mock-workers")) {
                    exerciseWorkerPools(pool, db);
                    return;
                }

                var jobs = ConcurrentHashMap.<Job>newKeySet();
                var empty = new AtomicInteger();
                calls.clear();
                for (int i = 0; i < 1020; i++) {
                    var node = nodes[i % nodes.length];
                    for (Lane lane : Lane.values())
                        calls.add(() -> { node.claim(lane, Stage.START, limits).ifPresentOrElse(jobs::add, empty::incrementAndGet); return null; });
                }
                for (var future : executor.invokeAll(calls)) future.get();
                long claimed = System.nanoTime();
                assertThat(jobs.size()).isEqualTo(3000);
                assertThat(jobs.stream().map(Job::id).distinct().count()).isEqualTo(3000);
                assertThat(empty.get()).isEqualTo(60);
                for (Lane lane : Lane.values()) {
                    assertThat(nodes[0].counts(lane)).isEqualTo(new JdbcAiWorkQueue.Counts(2, 1000, 0));
                    assertThat(nodes[0].claim(lane, Stage.START, limits)).isEmpty();
                }
                System.out.printf("MySQL capacity: 3006 admitted in %.3fs, 3000 simultaneous leases (1000 per lane) in %.3fs%n",
                    (accepted - start) / 1e9, (claimed - accepted) / 1e9);

                calls.clear();
                jobs.forEach(job -> calls.add(() -> { nodes[1].finish(job, State.SUCCEEDED, null); return null; }));
                for (var future : executor.invokeAll(calls)) future.get();
                for (Lane lane : Lane.values()) {
                    assertThat(nodes[2].counts(lane)).isEqualTo(new JdbcAiWorkQueue.Counts(2, 0, 0));
                    assertThat(nodes[3].claim(lane, Stage.START, limits)).isPresent();
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void exerciseWorkerPools(DataSource data, JdbcTemplate db) throws InterruptedException {
        var reachedProvider = new CountDownLatch(3000);
        var release = new CountDownLatch(1);
        var queue = queue(data);
        var workers = new ArrayList<AiCapacityWorker>();
        var meters = new SimpleMeterRegistry();
        var handlers = List.of(blockingProvider(Lane.LYRICS, reachedProvider, release),
            blockingProvider(Lane.AGENT, reachedProvider, release), blockingProvider(Lane.COVER, reachedProvider, release));
        var text = new AiCapacity(new MockEnvironment().withProperty("qingfeng.ai.capacity.worker-lanes","lyrics,agent")
            .withProperty("qingfeng.ai.capacity.lyrics.start-threads", "200")
            .withProperty("qingfeng.ai.capacity.agent.start-threads", "200"));
        var cover = new AiCapacity(new MockEnvironment().withProperty("qingfeng.ai.capacity.worker-lanes","cover"));
        try {
            for (int i = 0; i < 5; i++) workers.add(new AiCapacityWorker(queue(data), text, handlers, meters));
            for (int i = 0; i < 2; i++) workers.add(new AiCapacityWorker(queue(data), cover, handlers, meters));
            long started = System.nanoTime();
            workers.forEach(AiCapacityWorker::start);
            assertThat(reachedProvider.await(55, TimeUnit.SECONDS))
                .as("3,000 jobs entered fake provider handlers, remaining %d", reachedProvider.getCount()).isTrue();
            System.out.printf("AI worker load: 1000 per lane reached fake providers in %.3fs%n", (System.nanoTime() - started) / 1e9);
            Thread.sleep(12_000);
            for (Lane lane : Lane.values()) assertThat(queue.counts(lane)).isEqualTo(new JdbcAiWorkQueue.Counts(2, 1000, 0));
            release.countDown();
            db.update("UPDATE ai_capacity_job SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE lane='COVER' AND state='WAITING'");
            long stop = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < stop) {
                boolean drained = true;
                for (Lane lane : Lane.values()) drained &= queue.counts(lane).equals(new JdbcAiWorkQueue.Counts(0, 0, 0));
                if (drained) break;
                Thread.sleep(100);
            }
            for (Lane lane : Lane.values()) assertThat(queue.counts(lane)).isEqualTo(new JdbcAiWorkQueue.Counts(0, 0, 0));
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_capacity_job WHERE state='SUCCEEDED'", Integer.class)).isEqualTo(3006);
        } finally {
            release.countDown();
            workers.forEach(AiCapacityWorker::close);
            meters.close();
        }
    }

    private static AiCapacityWorker.Handler blockingProvider(Lane lane, CountDownLatch reached, CountDownLatch release) {
        return new AiCapacityWorker.Handler() {
            @Override public Lane lane() { return lane; }
            @Override public Set<Stage> stages() { return lane == Lane.COVER ? Set.of(Stage.START, Stage.POLL) : Set.of(Stage.START); }
            @Override public AiCapacityWorker.Completion run(Job job) {
                if (job.stage() == Stage.START) reached.countDown();
                if (lane == Lane.COVER && release.getCount() != 0)
                    return AiCapacityWorker.Completion.waitFor(Stage.POLL, Duration.ofMinutes(2));
                try {
                    if (!release.await(100, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Fixture interrupted", interrupted);
                }
                return AiCapacityWorker.Completion.success();
            }
            @Override public void expired(Job job) { throw new AssertionError("Fixture admission expired"); }
            @Override public Recovery interrupted(Job job) { throw new AssertionError("Fixture lease expired"); }
        };
    }

    private static JdbcTemplate schema(DataSource data) {
        var db = new JdbcTemplate(data);
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE reserved_credit(owner_id VARCHAR(36) PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V59__ai_capacity_queue.sql")).execute(data);
        return db;
    }

    private static JdbcAiWorkQueue queue(DataSource data) {
        return new JdbcAiWorkQueue(new JdbcTemplate(data), tx(data), Duration.ofMinutes(3));
    }

    private static TransactionTemplate tx(DataSource data) {
        return new TransactionTemplate(new DataSourceTransactionManager(data));
    }

    private static String creator(JdbcTemplate db) {
        var id = UUID.randomUUID().toString();
        db.update("INSERT INTO creator(id) VALUES(?)", id);
        return id;
    }
}
