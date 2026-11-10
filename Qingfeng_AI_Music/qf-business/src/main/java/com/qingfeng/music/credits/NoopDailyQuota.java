package com.qingfeng.music.credits;

/**
 * 不计费的每日配额实现：用于没有数据库的环境（无账本可用）时降级为不限。
 * 生产与测试环境使用 {@link com.qingfeng.music.infrastructure.persistence.credits.JdbcDailyQuota}。
 */
public final class NoopDailyQuota implements DailyQuota {
    public boolean consume(String owner,String bucket,long limit){return true;}
    public long remaining(String owner,String bucket,long limit){return Long.MAX_VALUE;}
}
