package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.lyrics.api.LyricLanguage;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.common.exception.DomainException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * AI 改写按用户请求计费；同一次选句改写内的有限补写不另扣积分或免费次数。
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
        return rewrite(owner, text, language, requestKey, 4000, 4000, UnaryOperator.identity());
    }

    String rewrite(String owner, String text, String language, String requestKey, int inputLimit, int outputLimit,
                   UnaryOperator<String> validateAndAssemble) {
        return rewriteValidated(owner, text, language, requestKey, inputLimit, input -> writer.rewrite(input, language), result -> {
            if (result == null || result.isBlank() || result.length() > outputLimit)
                throw new DomainException("INVALID_REWRITE_RESPONSE", "改写结果不完整，请稍后重试；原文仍会保留。");
            return validateAndAssemble.apply(result.trim());
        });
    }

    // Both the initial result and its one permitted repair share this reservation and replay key.
    // Run the whole operation in generate: an unknown repair response must remain UNKNOWN, not refunded.
    String rewriteSelection(String owner, String text, String language, String requestKey, int inputLimit,
                            Function<InspirationRewriter, String> generateAndAssemble) {
        return rewriteValidated(owner, text, language, requestKey, inputLimit,
            input -> generateAndAssemble.apply(writer), UnaryOperator.identity());
    }

    private <T> String rewriteValidated(String owner, String text, String language, String requestKey, int inputLimit,
                                        Function<String, T> generate, Function<T, String> validateAndAssemble) {
        if (owner == null || owner.isBlank()) throw new DomainException("INVALID_REWRITE_REQUEST", "请刷新页面后重试改写。");
        if (text == null || text.isBlank() || text.length() > inputLimit || !LyricLanguage.isSupported(language))
            throw new DomainException("INVALID_REWRITE_REQUEST", inputLimit==4000 ? "请填写1至4000字的创作灵感后再改写。" : "歌词内容无效，请检查歌词后再重写。");
        if (!enabled || !writer.isConfigured()) throw new DomainException("REWRITE_PROVIDER_UNAVAILABLE", "AI改写暂不可用，请稍后再试；原文仍会保留。");
        if (requestKey == null || !requestKey.matches("[0-9a-fA-F-]{36}")) throw new DomainException("INVALID_REWRITE_REQUEST", "改写请求标识无效，请刷新后重试。");
        if (!active.add(owner)) throw busy();
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire();
            if (!acquired) throw busy();
            var reservation = charges.reserve(owner, requestKey);
            if (!reservation.fresh()) return replay(reservation);
            String validated;
            boolean received=false;
            charges.submitted(owner,requestKey);
            try {
                T result = generate.apply(text.trim());
                received=true;
                validated = validateAndAssemble.apply(result);
            } catch (RuntimeException error) {
                // Only an explicit rejection or a received invalid result proves a final failure.
                if(received || error instanceof DomainException domain && Set.of("REWRITE_PROVIDER_UNAVAILABLE","REWRITE_PROVIDER_REJECTED","INVALID_REWRITE_RESPONSE","WORK_VERSION_CONFLICT","WORK_NOT_FOUND").contains(domain.code()))
                    charges.fail(owner, requestKey);
                else charges.unknown(owner,requestKey);
                throw error;
            }
            // A persistence failure after a valid provider result must not mint a refund.
            charges.succeed(owner, requestKey, validated);
            return validated;
        } finally {
            if (acquired) slots.release();
            active.remove(owner);
        }
    }

    /** 同一请求键只返回历史结果或当前状态。 */
    private String replay(RewriteCharges.Reservation reservation) {
        if ("SUCCEEDED".equals(reservation.status())) return reservation.result() == null ? "" : reservation.result();
        if ("FAILED".equals(reservation.status())) throw new DomainException("REWRITE_REQUEST_FAILED", "上次改写未完成，可以重新发起一次改写。");
        if ("UNKNOWN".equals(reservation.status())) throw new DomainException("REWRITE_PROVIDER_RESULT_UNKNOWN", "上次改写未取得结果，原文仍会保留。");
        throw new DomainException("REWRITE_REQUEST_PENDING", "这次改写正在处理中，请稍后再试。");
    }
    private DomainException busy() { return new DomainException("REWRITE_GENERATION_BUSY", "AI改写正在处理中，请稍后再试。"); }
}
