package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.ReferenceAnalysis;
import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.agent.model.StyleCatalog;
import com.qingfeng.music.common.exception.DomainException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 参考音乐语义化的应用层：校验输入、限制并发，然后把模型输出收敛到可安全使用的结果。
 * 模型只负责措辞；风格白名单、BPM 采信、长度上限都在这里裁决，任何异常都降级为可回退错误码。
 */
public final class ReferenceAnalysisService {
    private static final double BPM_MIN = 80, BPM_MAX = 180;
    private static final double BPM_CONFIDENCE = 0.55;
    private static final int SUMMARY_LIMIT = 120;
    private static final int TEXT_LIMIT = 400;

    private final ReferenceAnalyst analyst;
    private final boolean enabled;
    private final Semaphore slots;
    private final com.qingfeng.music.credits.DailyQuota quota;
    private final long dailyLimit;
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ReferenceAnalysisService(ReferenceAnalyst analyst, boolean enabled, int concurrency) {
        this(analyst, enabled, concurrency, com.qingfeng.music.credits.NoopDailyQuota.FREE, 0L);
    }

    public ReferenceAnalysisService(ReferenceAnalyst analyst, boolean enabled, int concurrency, com.qingfeng.music.credits.DailyQuota quota, long dailyLimit) {
        if (concurrency < 1) throw new IllegalArgumentException("Reference analysis concurrency must be positive");
        this.analyst = analyst;
        this.enabled = enabled;
        this.quota = quota == null ? com.qingfeng.music.credits.NoopDailyQuota.FREE : quota;
        this.dailyLimit = dailyLimit;
        this.slots = new Semaphore(concurrency);
    }

    public ReferenceAnalysis analyse(String owner, ReferenceAnalyst.Request request) {
        validate(owner, request);
        if (!enabled || !analyst.isConfigured()) throw unavailable();
        // 上传参考音频后会自动触发，每次都是真实模型请求；用每日次数上限封顶成本敞口。
        if (!quota.consume(owner, com.qingfeng.music.credits.DailyQuota.REFERENCE_ANALYSIS, dailyLimit))
            throw new DomainException("REFERENCE_ANALYSIS_QUOTA_EXCEEDED", "今日AI参考分析次数已用完，已保留参考音乐的基础信息。");
        if (!active.add(owner)) throw busy();
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire();
            if (!acquired) throw busy();
            ReferenceAnalyst.Suggestion suggestion = analyst.analyse(request);
            if (suggestion == null) throw unavailable();
            return sanitize(request, suggestion);
        } finally {
            if (acquired) slots.release();
            active.remove(owner);
        }
    }

    private void validate(String owner, ReferenceAnalyst.Request request) {
        if (owner == null || owner.isBlank()) throw invalid("请刷新页面后重试参考音乐分析。");
        if (request == null) throw invalid("缺少参考音乐分析所需的信息。");
        if (request.text() != null && request.text().length() > TEXT_LIMIT) throw invalid("创作需求过长，请精简后再分析参考音乐。");
        if (request.fileName() != null && request.fileName().length() > 160) throw invalid("参考音乐文件名过长。");
        if (request.locale() == null || !Set.of("zh-CN", "en").contains(request.locale())) throw invalid("语言参数不正确。");
        ReferenceAnalyst.Features features = request.features();
        if (features == null) throw invalid("缺少音频特征，请重新上传参考音乐。");
        ratio(features.rms());
        ratio(features.peak());
        ratio(features.dynamicRange());
        ratio(features.low());
        ratio(features.mid());
        ratio(features.high());
        ratio(features.brightness());
        ratio(features.noisiness());
        ratio(features.bpmConfidence());
        bounded(features.bpm(), 0, 300);
        bounded(features.duration(), 0, 7200);
        bounded(features.analyzedSeconds(), 0, 180);
    }

    private static void ratio(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw invalid("音频特征超出有效范围。");
    }

    private static void bounded(double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) throw invalid("音频特征超出有效范围。");
    }

    /**
     * 需求 4.4/4.5/4.6：BPM 一律以浏览器端可信值为准；置信度不足时给 0，绝不伪造精确速度。
     * 需求 4.7：乐器字段改名为 inferredInstrumentation，避免被当成算法检测结果。
     */
    private ReferenceAnalysis sanitize(ReferenceAnalyst.Request request, ReferenceAnalyst.Suggestion suggestion) {
        ReferenceAnalyst.Features features = request.features();
        boolean verified = Double.isFinite(features.bpm()) && features.bpm() >= BPM_MIN && features.bpm() <= BPM_MAX
            && features.bpmConfidence() >= BPM_CONFIDENCE;
        double tempo = verified ? round1(features.bpm()) : 0;
        String style = StyleCatalog.safe(suggestion.style());
        String mood = clamp(suggestion.mood(), 20);
        String summary = clamp(suggestion.summary(), SUMMARY_LIMIT);
        if (summary.isBlank()) summary = fallbackSummary(request.locale(), style, mood, tempo, verified);
        return new ReferenceAnalysis(style, mood, tempo, verified,
            distinct(suggestion.instrumentation(), 4, 12),
            distinct(suggestion.negativeTags(), 4, 20),
            summary, ReferenceAnalysis.SOURCE_AI);
    }

    private static String fallbackSummary(String locale, String style, String mood, double tempo, boolean verified) {
        boolean english = "en".equals(locale);
        StringBuilder text = new StringBuilder(english ? "Reference track: " : "参考音乐：");
        text.append(style);
        if (!mood.isBlank()) text.append(english ? ", " : "，").append(mood);
        if (verified) text.append(english ? ", about " : "，约 ").append(tempoString(tempo)).append(" BPM");
        return clamp(text.toString(), SUMMARY_LIMIT);
    }

    private static String tempoString(double tempo) {
        return tempo == Math.rint(tempo) ? String.valueOf((long) tempo) : String.valueOf(tempo);
    }

    private static List<String> distinct(List<String> values, int maxItems, int maxLength) {
        if (values == null) return List.of();
        var unique = new LinkedHashSet<String>();
        for (String value : values) {
            String cleaned = clamp(value, maxLength);
            if (!cleaned.isBlank()) unique.add(cleaned);
            if (unique.size() >= maxItems) break;
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    /** 统一压平空白并按码点截断，避免把 emoji 截成半个字符。 */
    private static String clamp(String value, int maxCodePoints) {
        if (value == null) return "";
        String flattened = value.replaceAll("\\s+", " ").trim();
        if (flattened.isEmpty()) return "";
        if (flattened.codePointCount(0, flattened.length()) <= maxCodePoints) return flattened;
        return flattened.substring(0, flattened.offsetByCodePoints(0, maxCodePoints)).trim();
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static DomainException invalid(String message) {
        return new DomainException("INVALID_REFERENCE_REQUEST", message);
    }

    private static DomainException busy() {
        return new DomainException("REFERENCE_ANALYSIS_BUSY", "参考音乐分析正在处理中，请稍后再试。");
    }

    private static DomainException unavailable() {
        return new DomainException("REFERENCE_ANALYSIS_UNAVAILABLE", "AI 参考分析暂不可用，已保留参考音乐的基础信息。");
    }
}
