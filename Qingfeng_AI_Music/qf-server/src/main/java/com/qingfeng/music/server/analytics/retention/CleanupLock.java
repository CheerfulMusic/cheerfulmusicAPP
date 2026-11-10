package com.qingfeng.music.server.analytics.retention;

import java.util.Optional;

/**
 * 清理任务互斥锁接口，与具体数据库解耦。
 * 清理任务只依赖此接口，绝不直接调用数据库专有函数（如 MySQL GET_LOCK）。
 *
 * 设计要点（见隐私治理方案 §2.A.2 / §15.2）：
 * - 并发互斥：同时仅一个执行节点能拿到锁；
 * - 获取失败不执行：{@link #tryAcquire()} 返回 empty（被占用或不支持）时清理任务必须跳过；
 * - fail-closed：{@link #supported()} 为 false 的环境，清理任务整体拒绝执行，绝不降级为无锁运行；
 * - 异常必释放：{@link LockHandle} 实现 AutoCloseable，配合 try-with-resources / finally 确保释放。
 */
public interface CleanupLock {

    /**
     * 尝试获取锁。
     *
     * @return 获取成功返回持有句柄；被占用或环境不支持可靠互斥锁时返回 {@link Optional#empty()}
     *         （清理任务应跳过，不删任何数据）。
     */
    Optional<LockHandle> tryAcquire();

    /**
     * 当前环境是否提供可靠的互斥锁。
     * 返回 false 时清理任务必须整体拒绝执行（fail-closed），绝不降级为无锁运行。
     */
    default boolean supported() {
        return true;
    }

    /** 锁持有句柄，实现 {@link AutoCloseable} 以便 try-with-resources 确保释放。 */
    interface LockHandle extends AutoCloseable {

        /** 释放锁。实现必须幂等（重复调用安全）。底层释放失败时应捕获并记录，不向上抛出以免掩盖清理体的原始异常。 */
        void release();

        @Override
        default void close() {
            release();
        }
    }
}
