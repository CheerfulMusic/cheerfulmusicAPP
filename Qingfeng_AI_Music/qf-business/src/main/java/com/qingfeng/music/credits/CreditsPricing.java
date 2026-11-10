package com.qingfeng.music.credits;

/**
 * 统一的积分定价服务：价格只来自服务端配置，前端不得硬编码。
 * 调整价格只需改配置并重启后端，不需要重新发布前端。
 */
public interface CreditsPricing {
    enum Kind { MUSIC, LYRICS, REWRITE }

    /** 积分扣费总开关；关闭时所有创作不扣积分。 */
    boolean enabled();

    /** 单次消费所需积分；必须是正整数。 */
    long cost(Kind kind);

    /**
     * 每日免费次数；0 表示该类型没有免费额度。
     * 目前只有 AI 改写使用免费额度，歌词与歌曲生成按次计费，返回 0。
     */
    long freePerDay(Kind kind);
}
