package com.qingfeng.music.server;

import com.qingfeng.music.agent.model.StyleCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 风格编译：基础风格与补充描述分离、幂等合并、超长只丢补充项。 */
class StyleCompilerTest {
    @Test void splitsOnTheLastMarkerSoUserTextSurvives() {
        assertEquals(new StyleCompiler.Split("温暖民谣", "90 BPM, piano"), StyleCompiler.split("温暖民谣 | 90 BPM, piano"));
        assertEquals(new StyleCompiler.Split("温暖民谣", ""), StyleCompiler.split("温暖民谣"));
        assertEquals(new StyleCompiler.Split("", ""), StyleCompiler.split(null));
        assertEquals(new StyleCompiler.Split("A | B", "90 BPM"), StyleCompiler.split("A | B | 90 BPM"));
        assertEquals("温暖民谣", StyleCompiler.base("温暖民谣 | 90 BPM"));
    }

    @Test void mergesIdempotently() {
        String once = StyleCompiler.merge("温暖民谣", "90 BPM, piano", 1000);
        assertEquals("温暖民谣 | 90 BPM, piano", once);
        assertEquals(once, StyleCompiler.merge(once, "90 BPM, piano", 1000));
        var split = StyleCompiler.split(once);
        assertEquals(once, StyleCompiler.merge(split.base(), split.tail(), 1000));
    }

    @Test void dropsTailSegmentsBeforeTouchingTheBaseStyle() {
        String tail = "x".repeat(400) + ", " + "y".repeat(400) + ", " + "z".repeat(400);
        String merged = StyleCompiler.merge("温暖民谣", tail, 1000);
        assertTrue(merged.length() <= 1000);
        assertTrue(merged.startsWith("温暖民谣 | "));
        assertTrue(merged.contains("x".repeat(400)));
        assertTrue(merged.contains("y".repeat(400)));
        assertFalse(merged.contains("z".repeat(400)));
    }

    @Test void neverTruncatesTheBaseEvenWhenItAloneIsAtTheLimit() {
        String base = "基".repeat(1000);
        assertEquals(base, StyleCompiler.merge(base, "90 BPM", 1000));
    }

    @Test void reportsExactlyWhichSegmentsWereDropped() {
        String tail = "90 BPM, piano, strings, guzheng";
        var merged = StyleCompiler.mergeWithDropped("温暖民谣", tail, 25);
        // 放不下的补充项必须被明确报告出来，不能静默丢弃。
        assertEquals(List.of("strings", "guzheng"), merged.dropped());
        assertEquals("温暖民谣 | 90 BPM, piano", merged.style());
        assertEquals(merged.style(), StyleCompiler.merge("温暖民谣", tail, 25));
    }

    @Test void reportsNothingDroppedWhenEverythingFits() {
        var merged = StyleCompiler.mergeWithDropped("温暖民谣", "90 BPM, piano", 80);
        assertEquals(List.of(), merged.dropped());
        assertEquals("温暖民谣 | 90 BPM, piano", merged.style());
    }

    @Test void reportsEverySegmentDroppedWhenTheBaseAloneFillsTheBudget() {
        var merged = StyleCompiler.mergeWithDropped("基".repeat(80), "90 BPM, piano", 80);
        assertEquals(List.of("90 BPM", "piano"), merged.dropped());
        assertEquals("基".repeat(80), merged.style());
    }

    @Test void handlesEmptyInputs() {
        assertEquals("复古港风", StyleCompiler.merge("复古港风", "", 1000));
        assertEquals("90 BPM", StyleCompiler.merge("", "90 BPM", 1000));
        assertEquals("", StyleCompiler.merge("", "", 1000));
    }
}
