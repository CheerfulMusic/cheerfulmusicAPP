package com.qingfeng.music.generation.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 提交、只读查询和音频归档各有固定数量的循环，没有无界任务队列。
 * 每个循环完成后才等待扫描间隔；网络或上传阻塞不会占用其他阶段的线程。
 */
public final class GenerationDispatcher implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(GenerationDispatcher.class.getName());
    private final Consumer<ExecutionRepository.Phase> scan;
    private final long scanMillis;
    private final int submissions, queries, archives;
    private final List<ScheduledThreadPoolExecutor> pools = new ArrayList<>();
    private final EnumMap<ExecutionRepository.Phase,ScheduledThreadPoolExecutor> phasePools=new EnumMap<>(ExecutionRepository.Phase.class);
    private final EnumMap<ExecutionRepository.Phase,AtomicBoolean> wakeups=new EnumMap<>(ExecutionRepository.Phase.class);
    private boolean started, closed;

    public GenerationDispatcher(Consumer<ExecutionRepository.Phase> scan, Duration interval,
                                int submissions, int queries, int archives) {
        this.scan = java.util.Objects.requireNonNull(scan);
        this.scanMillis = interval.toMillis();
        if (scanMillis < 1) throw new IllegalArgumentException("任务扫描间隔必须大于0");
        validateWorkers(submissions);
        validateWorkers(queries);
        validateWorkers(archives);
        this.submissions = submissions;
        this.queries = queries;
        this.archives = archives;
    }

    private static void validateWorkers(int count) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("每个音乐执行阶段的线程数须在1到16之间");
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("音乐任务执行器已经关闭");
        if (started) return;
        started = true;
        startPool(ExecutionRepository.Phase.SUBMIT, submissions);
        startPool(ExecutionRepository.Phase.QUERY, queries);
        startPool(ExecutionRepository.Phase.ARCHIVE, archives);
    }

    private void startPool(ExecutionRepository.Phase phase, int workers) {
        var sequence = new AtomicInteger();
        var pool = new ScheduledThreadPoolExecutor(workers, runnable -> {
            var thread = new Thread(runnable, "music-" + phase.name().toLowerCase(java.util.Locale.ROOT)
                    + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pool.setRemoveOnCancelPolicy(true);
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        pools.add(pool);
        phasePools.put(phase,pool);wakeups.put(phase,new AtomicBoolean());
        for (int i = 0; i < workers; i++) {
            pool.scheduleWithFixedDelay(() -> scanOnce(phase), 0, scanMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** Broker notifications share the existing phase pool, with at most one extra queued/running scan. */
    public synchronized boolean wakeup(ExecutionRepository.Phase phase) {
        if(!started||closed||!phasePools.containsKey(phase))return false;
        var pending=wakeups.get(phase);if(!pending.compareAndSet(false,true))return false;
        try{phasePools.get(phase).execute(()->{try{scanOnce(phase);}finally{pending.set(false);}});return true;}
        catch(RejectedExecutionException stopping){pending.set(false);return false;}
    }
    private void scanOnce(ExecutionRepository.Phase phase){
        try{scan.accept(phase);}catch(RuntimeException error){
            LOG.warning("music_scan_failed phase="+phase+" exception="+error.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pools.forEach(ScheduledThreadPoolExecutor::shutdown);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean interrupted = false;
        for (var pool : pools) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (!pool.awaitTermination(remaining, TimeUnit.NANOSECONDS)) pool.shutdownNow();
            } catch (InterruptedException error) {
                interrupted = true;
                pool.shutdownNow();
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
