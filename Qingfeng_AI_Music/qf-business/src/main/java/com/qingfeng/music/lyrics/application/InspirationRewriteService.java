package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public final class InspirationRewriteService {
    private final InspirationRewriter writer;
    private final boolean enabled;
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private final Semaphore slots;

    public InspirationRewriteService(InspirationRewriter writer, boolean enabled, int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("Rewrite concurrency must be positive");
        this.writer = writer;
        this.enabled = enabled;
        this.slots = new Semaphore(concurrency);
    }

    public String rewrite(String owner, String text, String language) {
        if (owner == null || owner.isBlank()) throw new DomainException("INVALID_REWRITE_REQUEST", "请刷新页面后重试改写。");
        if (text == null || text.isBlank() || text.length() > 4000 || !Set.of("zh-CN", "en", "mixed").contains(language == null ? "" : language))
            throw new DomainException("INVALID_REWRITE_REQUEST", "请填写1至4000字的创作灵感后再改写。");
        if (!enabled || !writer.isConfigured()) throw new DomainException("REWRITE_PROVIDER_UNAVAILABLE", "AI改写暂不可用，请稍后再试；原文仍会保留。");
        if (!active.add(owner)) throw busy();
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire();
            if (!acquired) throw busy();
            String result = writer.rewrite(text.trim(), language);
            if (result == null || result.isBlank() || result.length() > 4000)
                throw new DomainException("INVALID_REWRITE_RESPONSE", "改写结果不完整，请稍后重试；原文仍会保留。");
            return result.trim();
        } finally {
            if (acquired) slots.release();
            active.remove(owner);
        }
    }
    private DomainException busy() { return new DomainException("REWRITE_GENERATION_BUSY", "AI改写正在处理中，请稍后再试。"); }
}
