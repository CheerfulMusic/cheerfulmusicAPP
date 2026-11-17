package com.qingfeng.music.common.concurrent;

import java.time.Duration;

/** One synchronous lyric request may change providers but must not restart its total time budget. */
public final class LyricsRequestBudget implements AutoCloseable {
    private static final ThreadLocal<LyricsRequestBudget> CURRENT = new ThreadLocal<>();
    private final LyricsRequestBudget previous;
    private final long deadline;
    private boolean closed;

    private LyricsRequestBudget(Duration maximum) {
        this.previous = CURRENT.get();
        this.deadline = deadline(maximum);
        CURRENT.set(this);
    }

    public static LyricsRequestBudget enter(Duration maximum) { return new LyricsRequestBudget(maximum); }

    /** Does not change the current context; callers retain the earlier parent or phase deadline. */
    public static long deadline(Duration maximum) {
        if (maximum == null || maximum.isZero() || maximum.isNegative())
            throw new IllegalArgumentException("Positive lyric request budget required");
        long now = System.nanoTime();
        long nanos = maximum.toNanos();
        var current = CURRENT.get();
        return current != null && current.deadline - now <= nanos ? current.deadline : now + nanos;
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Lyric budgets must close in their owning thread and scope order");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        closed = true;
    }
}
