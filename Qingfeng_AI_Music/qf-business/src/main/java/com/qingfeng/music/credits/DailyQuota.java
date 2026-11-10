package com.qingfeng.music.credits;

/**
 * 每日次数型配额：跨实例共享，用于给不直接计费的 AI 调用封顶成本。
 *
 * 这些调用每次都会产生真实上游请求，但没有交付物、也不适合按次收费，
 * 因此用「每日次数上限」控制成本敞口，而不是扣积分。
 *
 * 约定与 usage-limits.properties 一致：-1 = 不限；0 = 禁止；正整数 = 实际次数上限。
 */
public interface DailyQuota {
    /** AI 改写的每日免费额度桶。 */
    String REWRITE = "REWRITE";
    /** 音乐要求精简的每日次数桶。 */
    String MUSIC_BRIEF = "MUSIC_BRIEF";
    /** 参考音乐语义化分析的每日次数桶。 */
    String REFERENCE_ANALYSIS = "REFERENCE_ANALYSIS";

    /** 不限次数的降级实现：用于没有数据库、无法记录配额的演示环境。 */
    DailyQuota FREE = new NoopDailyQuota();

    /**
     * 消耗一次当日额度。
     *
     * @return true 表示额度已占用、可以继续调用；false 表示额度已用尽或被禁止
     */
    boolean consume(String owner, String bucket, long limit);

    /** 今日剩余额度；limit 为 -1 时返回 {@link Long#MAX_VALUE} 表示不限。 */
    long remaining(String owner, String bucket, long limit);
}
