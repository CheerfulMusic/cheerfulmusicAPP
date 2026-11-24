package com.qingfeng.music.server.ai;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class AiCapacityWorker implements AutoCloseable {
    public interface Handler {
        Lane lane();
        default Set<Stage> stages() { return Set.of(Stage.START); }
        Completion run(Job job);
        void expired(Job job);
        JdbcAiWorkQueue.Recovery interrupted(Job job);
    }

    public record Completion(State state, String error, Stage next, Duration delay) {
        public static Completion success() { return new Completion(State.SUCCEEDED, null, null, null); }
        public static Completion failed(String error) { return new Completion(State.FAILED, error, null, null); }
        public static Completion unknown(String error) { return new Completion(State.UNKNOWN, error, null, null); }
        public static Completion waitFor(Stage step, Duration delay) { return new Completion(State.WAITING, null, step, delay); }
    }

    private record Pool(Handler handler, Stage stage, ExecutorService executor, Semaphore permits) {}
    private static final System.Logger LOG = System.getLogger(AiCapacityWorker.class.getName());
    private final JdbcAiWorkQueue queue;
    private final AiCapacity capacity;
    private final MeterRegistry meters;
    private final List<Pool> pools = new ArrayList<>();
    private final List<Handler> handlers;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Set<Job> inFlight = ConcurrentHashMap.newKeySet();

    public AiCapacityWorker(JdbcAiWorkQueue queue, AiCapacity capacity, List<Handler> handlers, MeterRegistry meters) {
        this.queue = queue;
        this.capacity = capacity;
        this.meters = meters;
        this.handlers = handlers.stream().filter(h -> capacity.runs(h.lane())).toList();
        for (Lane lane : Lane.values()) {
            if (capacity.runs(lane) && this.handlers.stream().noneMatch(handler -> handler.lane() == lane))
                throw new IllegalStateException("A configured AI worker has no enabled handler: " + lane);
        }
        for (var handler : this.handlers) {
            capacity.limits(handler.lane());
            for (Stage stage : handler.stages()) {
                int size = capacity.threads(handler.lane(), stage);
                pools.add(new Pool(handler, stage, Executors.newFixedThreadPool(size), new Semaphore(size)));
            }
        }
    }

    public void start() {
        for (var pool : pools) scheduler.scheduleWithFixedDelay(() -> guard(() -> dispatch(pool)), 0, 150, TimeUnit.MILLISECONDS);
        if (!pools.isEmpty()) {
            scheduler.scheduleWithFixedDelay(() -> guard(this::heartbeat), 10, 10, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(() -> handlers.forEach(handler -> guard(() -> recover(handler))), 2, 2, TimeUnit.SECONDS);
        }
    }

    private void dispatch(Pool pool) {
        // Only claim work for a thread which can actually start it.
        for (int batch = 0; batch < 32 && pool.permits().tryAcquire(); batch++) {
            boolean handedOff = false;
            try {
                var job = queue.claim(pool.handler().lane(), pool.stage(), capacity.limits(pool.handler().lane()));
                if (job.isEmpty()) return;
                pool.executor().execute(() -> {
                    try { execute(pool.handler(), job.get()); }
                    finally { pool.permits().release(); }
                });
                handedOff = true;
            } finally {
                if (!handedOff) pool.permits().release();
            }
        }
    }

    private void execute(Handler handler, Job job) {
        long started = System.nanoTime();
        inFlight.add(job);
        try {
            var result = handler.run(job);
            if (result.state() == State.WAITING) queue.waitFor(job, result.next(), result.delay());
            else {
                queue.finish(job, result.state(), result.error());
                meters.counter("qingfeng.ai.capacity.completed", "lane", job.lane().name(), "outcome", result.state().name()).increment();
            }
        } catch (RuntimeException e) {
            // A write may already have committed. The lease recovery checks the domain record before settling.
            LOG.log(System.Logger.Level.WARNING, "AI job stage failed: {0} {1} {2}", handler.lane(), job.stage(),
                e instanceof DomainException domain ? domain.code() : e.getClass().getSimpleName());
        } finally {
            inFlight.remove(job);
            meters.timer("qingfeng.ai.capacity.stage.duration", "lane", job.lane().name(), "stage", job.stage().name())
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private void heartbeat() {
        var running = List.copyOf(inFlight);
        for (int offset = 0; offset < running.size(); offset += 32) {
            queue.renewAll(running.subList(offset, Math.min(offset + 32, running.size())));
        }
    }

    private void recover(Handler handler) {
        for (int i = 0; i < 16 && queue.recoverOne(handler.lane(), handler::expired, handler::interrupted); i++) {
            // Bounded to leave time for other lanes.
        }
    }

    private void guard(Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "AI queue dispatcher: {0}", e.getClass().getSimpleName());
        }
    }

    @Override public void close() {
        scheduler.shutdown();
        pools.forEach(pool -> pool.executor().shutdown());
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        try {
            for (var pool : pools) {
                pool.executor().awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            pools.forEach(pool -> pool.executor().shutdownNow());
        }
    }
}
