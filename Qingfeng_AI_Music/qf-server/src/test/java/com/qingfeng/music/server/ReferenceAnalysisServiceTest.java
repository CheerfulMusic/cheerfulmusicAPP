package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.ReferenceAnalysis;
import com.qingfeng.music.agent.api.ReferenceAnalyst;
import com.qingfeng.music.agent.application.ReferenceAnalysisService;
import com.qingfeng.music.agent.model.StyleCatalog;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 应用层裁决：白名单、BPM 采信、长度、并发与降级。不涉及网络。 */
class ReferenceAnalysisServiceTest {

    private static ReferenceAnalyst.Request request(double bpm, double confidence, String text, String locale) {
        return new ReferenceAnalyst.Request(text, "ref.mp3", 128, locale,
            new ReferenceAnalyst.Features(bpm, confidence, 0.3, 0.8, 0.4, 0.5, 0.3, 0.2, 0.6, 0.2, 128, 90));
    }

    private static ReferenceAnalyst.Suggestion suggestion(String style, double tempo, String summary) {
        return new ReferenceAnalyst.Suggestion(style, "温柔平静", tempo, List.of("木吉他", "轻鼓"), List.of("失真"), summary);
    }

    private static ReferenceAnalyst analyst(ReferenceAnalyst.Suggestion value) {
        return new ReferenceAnalyst() {
            public boolean isConfigured() { return true; }
            public Suggestion analyse(Request request) { return value; }
        };
    }

    @Test void keepsWhitelistedStyleAndTrustsMeasuredTempoOverModel() {
        var service = new ReferenceAnalysisService(analyst(suggestion("国风古韵", 90, "偏东方的叙事歌")), true, 2);
        var result = service.analyse("owner", request(128.4, 0.8, "想写一首给妈妈的歌", "zh-CN"));
        assertEquals("国风古韵", result.style());
        // 模型给 90，测量值 128.4，偏差 > 8：必须采用浏览器端。
        assertEquals(128.4, result.tempo());
        assertTrue(result.tempoVerified());
        assertEquals("偏东方的叙事歌", result.summary());
        assertEquals(ReferenceAnalysis.SOURCE_AI, result.source());
    }

    @Test void fallsBackOnIllegalOrMissingStyle() {
        var illegal = new ReferenceAnalysisService(analyst(suggestion("赛博朋克电音", 120, "x")), true, 2);
        assertEquals(StyleCatalog.FALLBACK, illegal.analyse("owner", request(120, 0.8, "", "zh-CN")).style());
        var missing = new ReferenceAnalysisService(analyst(suggestion(null, 120, "x")), true, 2);
        assertEquals(StyleCatalog.FALLBACK, missing.analyse("owner", request(120, 0.8, "", "zh-CN")).style());
    }

    @Test void neverReportsTempoWithoutConfidenceOrInRange() {
        var lowConfidence = new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 120, "x")), true, 2);
        var low = lowConfidence.analyse("owner", request(120, 0.4, "", "zh-CN"));
        assertEquals(0, low.tempo());
        assertFalse(low.tempoVerified());
        var outOfRange = new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 60, "x")), true, 2);
        var slow = outOfRange.analyse("owner", request(60, 0.9, "", "zh-CN"));
        assertEquals(0, slow.tempo());
        assertFalse(slow.tempoVerified());
    }

    @Test void boundsSummaryListsAndFlattensWhitespace() {
        String longText = "参".repeat(200) + "\n\n第二段";
        var service = new ReferenceAnalysisService(analyst(new ReferenceAnalyst.Suggestion("温暖民谣", "温柔平静", 120,
            List.of("木吉他", "木吉他", " ", "钢琴", "鼓", "贝斯", "弦乐"), List.of("失真", "失真", "说唱"),
            longText)), true, 2);
        var result = service.analyse("owner", request(120, 0.9, "", "zh-CN"));
        assertEquals(120, result.summary().codePointCount(0, result.summary().length()));
        assertFalse(result.summary().contains("\n"));
        assertEquals(List.of("木吉他", "钢琴", "鼓", "贝斯"), result.inferredInstrumentation());
        assertEquals(List.of("失真", "说唱"), result.negativeTags());
    }

    @Test void buildsLocaleAwareFallbackSummaryWhenModelOmitsIt() {
        var zh = new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 0, "  ")), true, 2);
        assertTrue(zh.analyse("owner", request(120, 0.9, "", "zh-CN")).summary().startsWith("参考音乐："));
        var en = new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 0, "")), true, 2);
        assertTrue(en.analyse("owner", request(120, 0.9, "", "en")).summary().startsWith("Reference track:"));
    }

    @Test void passesUserTextThroughWithoutRewriting() {
        AtomicReference<ReferenceAnalyst.Request> captured = new AtomicReference<>();
        var service = new ReferenceAnalysisService(new ReferenceAnalyst() {
            public boolean isConfigured() { return true; }
            public Suggestion analyse(Request request) { captured.set(request); return suggestion("温暖民谣", 120, "x"); }
        }, true, 2);
        String text = "用粤语唱一首情歌，不要电音";
        service.analyse("owner", request(120, 0.9, text, "zh-CN"));
        assertEquals(text, captured.get().text());
    }

    @Test void rejectsOutOfRangeOrMissingInput() {
        var service = new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 120, "x")), true, 2);
        List<ReferenceAnalyst.Request> invalid = new ArrayList<>();
        invalid.add(new ReferenceAnalyst.Request("", "ref.mp3", 128, "zh-CN",
            new ReferenceAnalyst.Features(Double.NaN, 0.9, 0.3, 0.8, 0.4, 0.5, 0.3, 0.2, 0.6, 0.2, 128, 90)));
        invalid.add(new ReferenceAnalyst.Request("", "ref.mp3", 128, "zh-CN",
            new ReferenceAnalyst.Features(120, 0.9, -0.1, 0.8, 0.4, 0.5, 0.3, 0.2, 0.6, 0.2, 128, 90)));
        invalid.add(new ReferenceAnalyst.Request("", "ref.mp3", 128, "zh-CN",
            new ReferenceAnalyst.Features(120, 0.9, 0.3, 0.8, 0.4, 0.5, 0.3, 0.2, 1.4, 0.2, 128, 90)));
        invalid.add(new ReferenceAnalyst.Request("x".repeat(401), "ref.mp3", 128, "zh-CN",
            new ReferenceAnalyst.Features(120, 0.9, 0.3, 0.8, 0.4, 0.5, 0.3, 0.2, 0.6, 0.2, 128, 90)));
        for (var request : invalid) {
            assertEquals("INVALID_REFERENCE_REQUEST", assertThrows(DomainException.class, () -> service.analyse("owner", request)).code());
        }
        assertEquals("INVALID_REFERENCE_REQUEST", assertThrows(DomainException.class,
            () -> service.analyse("owner", new ReferenceAnalyst.Request("", "ref.mp3", 128, "fr",
                new ReferenceAnalyst.Features(120, 0.9, 0.3, 0.8, 0.4, 0.5, 0.3, 0.2, 0.6, 0.2, 128, 90)))).code());
        assertEquals("INVALID_REFERENCE_REQUEST", assertThrows(DomainException.class,
            () -> service.analyse(" ", request(120, 0.9, "", "zh-CN"))).code());
    }

    @Test void reportsUnavailableWhenProviderIsOff() {
        ReferenceAnalyst off = new ReferenceAnalyst() {
            public boolean isConfigured() { return false; }
            public Suggestion analyse(Request request) { throw new AssertionError("不应该被调用"); }
        };
        assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE",
            assertThrows(DomainException.class, () -> new ReferenceAnalysisService(off, true, 2).analyse("owner", request(120, 0.9, "", "zh-CN"))).code());
        assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE",
            assertThrows(DomainException.class, () -> new ReferenceAnalysisService(analyst(suggestion("温暖民谣", 0, "x")), false, 2)
                .analyse("owner", request(120, 0.9, "", "zh-CN"))).code());
        ReferenceAnalyst broken = new ReferenceAnalyst() {
            public boolean isConfigured() { return true; }
            public Suggestion analyse(Request request) { throw new DomainException("REFERENCE_ANALYSIS_UNAVAILABLE", "AI 服务暂时繁忙"); }
        };
        assertEquals("REFERENCE_ANALYSIS_UNAVAILABLE",
            assertThrows(DomainException.class, () -> new ReferenceAnalysisService(broken, true, 2).analyse("owner", request(120, 0.9, "", "zh-CN"))).code());
    }

    @Test void rejectsConcurrentCallsFromSameCreator() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        ReferenceAnalyst blocking = new ReferenceAnalyst() {
            public boolean isConfigured() { return true; }
            public Suggestion analyse(Request request) {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return suggestion("温暖民谣", 120, "x");
            }
        };
        var service = new ReferenceAnalysisService(blocking, true, 2);
        Thread first = new Thread(() -> service.analyse("owner", request(120, 0.9, "", "zh-CN")));
        first.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals("REFERENCE_ANALYSIS_BUSY",
            assertThrows(DomainException.class, () -> service.analyse("owner", request(120, 0.9, "", "zh-CN"))).code());
        release.countDown();
        first.join(5000);
        // 释放后同一个创作者可以再次分析。
        assertEquals("温暖民谣", service.analyse("owner", request(120, 0.9, "", "zh-CN")).style());
    }
}
