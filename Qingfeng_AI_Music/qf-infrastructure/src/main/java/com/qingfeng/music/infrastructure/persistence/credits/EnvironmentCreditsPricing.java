package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditsPricing;
import java.util.Locale;
import org.springframework.core.env.Environment;

/** 价格来自服务端配置（qingfeng.credits.*-cost），前端只读取接口返回值。 */
public final class EnvironmentCreditsPricing implements CreditsPricing {
    private final Environment env;
    public EnvironmentCreditsPricing(Environment env) { this.env = env; }
    public boolean enabled() { return env.getProperty("qingfeng.credits.enabled", Boolean.class, true); }
    public long cost(Kind kind) {
        long value = env.getProperty("qingfeng.credits." + kind.name().toLowerCase(Locale.ROOT) + "-cost", Long.class, defaultCost(kind));
        if (value <= 0) throw new IllegalStateException("积分价格必须为正整数：" + kind);
        return value;
    }
    /**
     * 每日免费次数；0 表示该类型没有免费额度。
     * 目前只有 AI 改写使用免费额度，歌词与歌曲生成按次计费，返回 0。
     * 改写支持 -1（永久免费）、0（无免费额度，全部按积分收费）、正数（前 N 次免费）。
     */
    public long freePerDay(Kind kind) {
        if (kind != Kind.REWRITE) return 0L;
        long value = env.getProperty("qingfeng.credits.rewrite-free-per-day", Long.class, 5L);
        if (value < -1) throw new IllegalStateException("每日免费次数不能小于 -1：" + kind);
        return value;
    }
    private long defaultCost(Kind kind) { return kind == Kind.LYRICS ? 5L : kind == Kind.REWRITE ? 1L : 15L; }
}
