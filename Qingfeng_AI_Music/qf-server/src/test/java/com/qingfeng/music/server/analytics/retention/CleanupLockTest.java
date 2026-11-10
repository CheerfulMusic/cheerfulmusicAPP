package com.qingfeng.music.server.analytics.retention;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 清理任务互斥锁验收测试（隐私治理方案 §2.A.2 / §15.2）。
 * 覆盖：并发互斥、锁获取失败不执行、异常必释放、H2 可控替代、fail-closed、
 * 以及 MySQL 命名锁返回码（1/0/NULL）语义与 release 异常吞掉。
 */
class CleanupLockTest {

    // ---- InMemoryCleanupLock（H2 / 测试替代实现）----

    @Test
    void concurrentAcquireOnlyOneSucceeds() {
        var lock = new InMemoryCleanupLock();
        var first = lock.tryAcquire();
        assertTrue(first.isPresent(), "首次获取应成功");
        var second = lock.tryAcquire();
        assertTrue(second.isEmpty(), "锁已被持有时，第二次获取应失败（并发互斥）");
        first.get().close();
        var third = lock.tryAcquire();
        assertTrue(third.isPresent(), "释放后应当可再次获取");
        third.get().close();
    }

    @Test
    void cleanupSkipsWhenLockBusy() {
        var lock = new InMemoryCleanupLock();
        var handle = lock.tryAcquire().orElseThrow();
        try (handle) {
            // 第二个执行节点尝试运行：锁被占用 → 必须跳过、不得执行清理
            var busy = new LockGuardedCleanup(lock, () -> fail("锁被占用时清理不得执行"));
            busy.run();
            assertTrue(busy.alerted.get(), "锁被占用时应告警并跳过");
        }
    }

    @Test
    void releasesLockEvenWhenCleanupBodyThrows() {
        var lock = new InMemoryCleanupLock();
        var ran = new AtomicBoolean(false);
        var original = new RuntimeException("cleanup boom");
        var handle = lock.tryAcquire().orElseThrow();
        var propagated = assertThrows(RuntimeException.class, () -> {
            try {
                ran.set(true);
                throw original;
            } finally {
                handle.close(); // 清理体抛异常，也必须释放锁后再传播
            }
        });
        assertTrue(ran.get());
        assertSame(original, propagated, "释放动作不得掩盖清理体原始失败");
        // 锁已被实际释放，后续可再次获取
        assertTrue(lock.tryAcquire().isPresent(), "异常释放后锁应可被再次获取");
    }

    @Test
    void failClosedWhenEnvironmentUnsupported() {
        var lock = new InMemoryCleanupLock();
        lock.setSupported(false);
        assertFalse(lock.supported(), "supported() 必须反映环境不支持");
        var acquired = lock.tryAcquire();
        assertTrue(acquired.isEmpty(), "不支持可靠互斥锁时 tryAcquire 必须返回 empty（fail-closed）");
        var runner = new LockGuardedCleanup(lock, () -> fail("不支持互斥锁时清理必须整体拒绝执行"));
        runner.run();
        assertTrue(runner.alerted.get(), "fail-closed 时应告警并拒绝执行");
    }

    // ---- MysqlNamedCleanupLock（命名锁，用 mock JdbcTemplate 验证返回码语义）----

    @Test
    void mysqlLockAcquiredOnReturnCode1() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, ?)"), any(RowMapper.class), any(), any())).thenReturn(1);
        var lock = new MysqlNamedCleanupLock(jdbc, "site_event_cleanup", 0);
        var handle = lock.tryAcquire();
        assertTrue(handle.isPresent(), "GET_LOCK 返回 1 时应获取成功");
        handle.get().close();
        verify(jdbc).queryForObject(eq("SELECT RELEASE_LOCK(?)"), any(RowMapper.class), any());
    }

    @Test
    void mysqlLockSkipsOnReturnCode0() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, ?)"), any(RowMapper.class), any(), any())).thenReturn(0);
        var lock = new MysqlNamedCleanupLock(jdbc, "site_event_cleanup", 0);
        assertTrue(lock.tryAcquire().isEmpty(), "GET_LOCK 返回 0（被占用）时应跳过");
    }

    @Test
    void mysqlLockFailClosedOnReturnCodeNull() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, ?)"), any(RowMapper.class), any(), any())).thenReturn(null);
        var lock = new MysqlNamedCleanupLock(jdbc, "site_event_cleanup", 0);
        assertTrue(lock.tryAcquire().isEmpty(), "GET_LOCK 返回 NULL（不支持/错误）时应 fail-closed 跳过");
    }

    @Test
    void mysqlReleaseSwallowsItsOwnException() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT GET_LOCK(?, ?)"), any(RowMapper.class), any(), any())).thenReturn(1);
        when(jdbc.queryForObject(eq("SELECT RELEASE_LOCK(?)"), any(RowMapper.class), any()))
                .thenThrow(new RuntimeException("release boom"));
        var lock = new MysqlNamedCleanupLock(jdbc, "site_event_cleanup", 0);
        var handle = lock.tryAcquire().orElseThrow();
        assertDoesNotThrow(handle::close, "release 自身异常必须被吞掉，不向上传播");
    }

    /**
     * 仅依赖 CleanupLock 接口的清理运行器演示（验收标准 6：清理任务只依赖接口，不直接调用 GET_LOCK）。
     * 承载 fail-closed / 获取失败跳过 / try-with-resources 释放 三件套。
     */
    static class LockGuardedCleanup {
        private final CleanupLock lock;
        private final Runnable task;
        final AtomicBoolean alerted = new AtomicBoolean();

        LockGuardedCleanup(CleanupLock lock, Runnable task) {
            this.lock = lock;
            this.task = task;
        }

        void run() {
            if (!lock.supported()) { // fail-closed
                alerted.set(true);
                return;
            }
            var handle = lock.tryAcquire();
            if (handle.isEmpty()) { // 获取失败不执行
                alerted.set(true);
                return;
            }
            try (var resource = handle.get()) {
                task.run();
            }
        }
    }
}
