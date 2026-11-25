package com.qingfeng.music.common.concurrent;

import java.time.Duration;

/** One synchronous lyric request may change providers but must not restart its total time budget. */
public final class LyricsRequestBudget implements AutoCloseable {
    private static final ThreadLocal<LyricsRequestBudget> CURRENT = new ThreadLocal<>();
    private final LyricsRequestBudget previous;
    private final LyricsRequestBudget request;
    private final long deadline;
    private final boolean boundResponseBody;
    private boolean fallbackUsed;
    private boolean closed;

    private LyricsRequestBudget(Duration maximum, boolean boundResponseBody) {
        this.previous = CURRENT.get();
        this.request = previous == null ? this : previous.request;
        this.deadline = deadline(maximum);
        this.boundResponseBody = boundResponseBody || previous != null && previous.boundResponseBody;
        CURRENT.set(this);
    }

    public static LyricsRequestBudget enter(Duration maximum) { return enter(maximum, false); }

    public static LyricsRequestBudget enter(Duration maximum, boolean boundResponseBody) {
        return new LyricsRequestBudget(maximum, boundResponseBody);
    }

    public static boolean boundsResponseBody() {
        var current = CURRENT.get();
        return current != null && current.boundResponseBody;
    }

    /** Share one fallback across generation, review and section repair within the synchronous request. */
    public static boolean claimTimeoutFallback() {
        var current = CURRENT.get();
        if (current == null) return true;
        if (current.request.fallbackUsed) return false;
        current.request.fallbackUsed = true;
        return true;
    }

    public static boolean hasRemaining(Duration minimum) {
        var current = CURRENT.get();
        return current == null || current.deadline - System.nanoTime() >= minimum.toNanos();
    }

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
