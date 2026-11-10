package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.common.exception.DomainException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * AI 改写：每次调用都会产生真实模型请求，因此纳入积分体系。
 *
 * 计费规则：每日免费额度内的改写不扣积分；超出后每次扣一次积分，失败全额退还。
 * 免费额度按调用计、失败不退还 —— 模型调用已经发生，退还免费次数会被用来刷免费调用。
 *
 * 幂等：同一个 requestKey 只会预扣一次，重复提交返回既有结果，不会重复扣费。
 */
public final class InspirationRewriteService {
    private final InspirationRewriter writer;
    private final boolean enabled;
    private final RewriteCharges charges;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Semaphore slots;

    public InspirationRewriteService(InspirationRewriter writer, boolean enabled, int concurrency) {
        this(writer, enabled, concurrency, RewriteCharges.FREE);
    }

    public InspirationRewriteService(InspirationRewriter writer, boolean enabled, int concurrency, RewriteCharges charges) {
        if (concurrency < 1) throw new IllegalArgumentException("Rewrite concurrency must be positive");
        this.writer = writer;
        this.enabled = enabled;
        this.charges = charges == null ? RewriteCharges.FREE : charges;
        this.slots = new Semaphore(concurrency);
    }

    public String rewrite(String owner, String text, String language) {
        return rewrite(owner, text, language, null);
    }

    public String rewrite(String owner, String text, String language, String requestKey) {
        if (owner == null || owner.isBlank()) throw new DomainException("INVALID_REWRITE_REQUEST", "请刷新页面后重试改写。");
        if (text == null || text.isBlank() || text.length() > 4000 || !Set.of("zh-CN", "en", "mixed").contains(language == null ? "" : language))
            throw new DomainException("INVALID_REWRITE_REQUEST", "请填写1至4000字的创作灵感后再改写。");
        if (!enabled || !writer.isConfigured()) throw new DomainException("REWRITE_PROVIDER_UNAVAILABLE", "AI改写暂不可用，请稍后再试；原文仍会保留。");
        if (requestKey == null || !requestKey.matches("[0-9a-fA-F-]{36}")) throw new DomainException("INVALID_REWRITE_REQUEST", "改写请求标识无效，请刷新后重试。");
        if (!active.add(owner)) throw busy();
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire();
            if (!acquired) throw busy();
            var reservation = charges.reserve(owner, requestKey);
            if (!reservation.fresh()) return replay(reservation);
            try {
                String result = writer.rewrite(text.trim(), language);
                if (result == null || result.isBlank() || result.length() > 4000)
                    throw new DomainException("INVALID_REWRITE_RESPONSE", "改写结果不完整，请稍后重试；原文仍会保留。");
                charges.succeed(owner, requestKey, result.trim());
                return result.trim();
            } catch (RuntimeException error) {
                // 失败退还已扣积分；免费额度不退还。
                charges.fail(owner, requestKey);
                throw error;
            }
        } finally {
            if (acquired) slots.release();
            active.remove(owner);
        }
    }

    /** 同一请求键重复提交：已成功直接返回上次结果，已失败或仍在处理中则明确拒绝，不重复调用模型。 */
    private String replay(RewriteCharges.Reservation reservation) {
        if ("SUCCEEDED".equals(reservation.status())) return reservation.result() == null ? "" : reservation.result();
        if ("FAILED".equals(reservation.status())) throw new DomainException("REWRITE_REQUEST_FAILED", "上次改写未完成，可以重新发起一次改写。");
        throw new DomainException("REWRITE_REQUEST_PENDING", "这次改写正在处理中，请稍后再试。");
    }
    private DomainException busy() { return new DomainException("REWRITE_GENERATION_BUSY", "AI改写正在处理中，请稍后再试。"); }
}
