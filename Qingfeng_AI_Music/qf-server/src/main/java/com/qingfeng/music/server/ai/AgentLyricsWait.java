package com.qingfeng.music.server.ai;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Limits;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Keep turn order and the child identity while returning the independent execution permit. */
public final class AgentLyricsWait implements AutoCloseable {
    private static final ThreadLocal<AgentLyricsWait> ACTIVE = new ThreadLocal<>();
    private final JdbcAiWorkQueue queue;
    private final Job parent;
    private final Limits limits;

    private AgentLyricsWait(JdbcAiWorkQueue queue, Job parent, Limits limits) {
        this.queue = queue;
        this.parent = parent;
        this.limits = limits;
        ACTIVE.set(this);
    }

    public static AgentLyricsWait open(JdbcAiWorkQueue queue, Job parent, Limits limits) {
        return new AgentLyricsWait(queue, parent, limits);
    }

    public static <T> T await(String work, String key, Supplier<T> result) {
        var waiter = ACTIVE.get();
        if (waiter == null) return result.get();
        if (!waiter.queue.suspendForLyrics(waiter.parent, work, key)) throw AgentRequestScope.expired();
        T value = result.get();
        waiter.resume();
        return value;
    }

    private void resume() {
        for (;;) {
            AgentRequestScope.check();
            if (queue.resumeAgent(parent, limits)) return;
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(160, 360));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw AgentRequestScope.expired();
            }
        }
    }

    @Override public void close() {
        ACTIVE.remove();
    }
}
