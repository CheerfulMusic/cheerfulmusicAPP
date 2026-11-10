package com.qingfeng.music.server.analytics.retention;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于 MySQL 命名锁 {@code GET_LOCK(name, timeout)} 的实现。
 *
 * <p>GET_LOCK 返回码语义：1=获取成功、0=被占用（超时）、NULL=错误/不支持。
 * release 调用 {@code RELEASE_LOCK(name)}。
 *
 * <p>若返回 NULL（驱动/版本不支持或执行错误）按"不支持/异常"处理 → {@link #tryAcquire()} 返回 empty（fail-closed）。
 * release 自身异常被捕获并记录，不向上抛出，以免掩盖清理体的原始失败。
 */
public class MysqlNamedCleanupLock implements CleanupLock {

    private static final Logger LOG = Logger.getLogger(MysqlNamedCleanupLock.class.getName());

    private final JdbcTemplate jdbc;
    private final String name;
    private final int timeoutSeconds;

    public MysqlNamedCleanupLock(JdbcTemplate jdbc, String name, int timeoutSeconds) {
        this.jdbc = jdbc;
        this.name = name;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Optional<LockHandle> tryAcquire() {
        Integer result = jdbc.queryForObject("SELECT GET_LOCK(?, ?)", (rs, n) -> rs.getInt(1), name, timeoutSeconds);
        if (result == null || result == 0) {
            return Optional.empty();
        }
        if (result == 1) {
            return Optional.of(new MysqlHandle());
        }
        return Optional.empty();
    }

    @Override
    public boolean supported() {
        return true;
    }

    private class MysqlHandle implements LockHandle {

        private volatile boolean released = false;

        @Override
        public void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", (rs, n) -> rs.getInt(1), name);
            } catch (RuntimeException e) {
                // 释放失败：记录但不抛出，避免掩盖清理体的原始异常。
                // 连接关闭时 MySQL 会自动释放命名锁，不会造成永久死锁。
                LOG.log(Level.WARNING, "RELEASE_LOCK failed for " + name, e);
            }
        }
    }
}
