package com.qingfeng.music.server.analytics.retention;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 不依赖真实数据库的可控锁实现，供单测注入"已占用 / 不支持"等状态。
 * 使用 {@link AtomicBoolean} CAS 模拟**节点级**互斥语义：一次只有一个清理任务持有锁，
 * 与 MySQL 命名锁 GET_LOCK 的"仅一个执行节点"抽象一致（刻意不用 ReentrantLock，
 * 因其为线程级可重入，同线程连续 tryAcquire 会误认为成功）。
 *
 * <p>对应隐私治理方案 §2.A.2：H2 测试环境用此实现替代 MySQL 命名锁，
 * 使锁的并发/获取失败/异常释放等行为可在无真实 MySQL 的情况下验证。
 */
public class InMemoryCleanupLock implements CleanupLock {

    private final AtomicBoolean locked = new AtomicBoolean(false);
    private volatile boolean supported = true;

    /** 设为 false 模拟"环境不支持可靠互斥锁" → fail-closed。 */
    public void setSupported(boolean supported) {
        this.supported = supported;
    }

    @Override
    public boolean supported() {
        return supported;
    }

    @Override
    public Optional<LockHandle> tryAcquire() {
        if (!supported) {
            return Optional.empty();
        }
        // CAS 保证节点级互斥：同实例（乃至同线程）第二次获取必失败，贴近 GET_LOCK 语义。
        if (!locked.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(new InMemoryHandle());
    }

    private class InMemoryHandle implements LockHandle {

        private final AtomicBoolean released = new AtomicBoolean(false);

        @Override
        public void release() {
            // 幂等：重复 close 不重复释放。
            if (!released.compareAndSet(false, true)) {
                return;
            }
            locked.set(false); // 无论成功/失败/异常，锁都必须被释放。
        }
    }
}
