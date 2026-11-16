package com.qingfeng.music.lyrics.application;

import org.junit.jupiter.api.Test;

import static com.qingfeng.music.lyrics.application.LyricRhyme.Match.*;
import static org.junit.jupiter.api.Assertions.*;

class LyricRhymeTest {
    @Test
    void groupsMandarinFinalsUsingTongyun() {
        assertEquals(EXACT, compare("剩下月光", "打开一扇窗。"));
        assertEquals(EXACT, compare("这盏路灯还亮", "就放到我手上"));
        assertEquals(EXACT, compare("听海风吹过街道", "多想再给你个拥抱"));
        assertEquals(EXACT, compare("有几句话想讲", "月光落在窗"));
        assertEquals(EXACT, compare("我等你回归", "就陪我到天黑"));
        assertEquals(EXACT, compare("你听我说", "让河水静静流过"));
        assertEquals(NONE, compare("想要心安", "抬头声昂"));
        assertEquals(NONE, compare("我留在城市的尽头", "未寄出的信"));
    }

    @Test
    void usesContextForMandarinPolyphones() {
        assertEquals("háng", tail("街角的银行").pronunciation());
        assertEquals("xíng", tail("我说这样不行").pronunciation());
        assertEquals("yuè", tail("在深夜听音乐").pronunciation());
        assertEquals("lè", tail("祝你快乐").pronunciation());
        assertEquals(NEAR, compare("在深夜听音乐", "如果有一点快乐"));
        assertTrue(LyricRhyme.tail("行").isEmpty());
    }

    @Test
    void comparesEnglishPronunciationFromLastStressedVowel() {
        assertEquals(EXACT, compare("I watched the fading light", "I stayed awake all night!"));
        assertEquals(EXACT, compare("We learned to sing", "What did you bring?"));
        assertEquals(EXACT, compare("I saw her walking", "We keep on talking"));
        assertEquals(NEAR, compare("Give me more time", "Don't let go tonight"));
        assertEquals(NONE, compare("I know what I love", "Now watch me move"));
        assertEquals(NONE, compare("That feeling came in a hurry", "My home is in the city"));
        assertEquals("night", tail("The last night! 🎵").word());
        assertEquals("/naɪt/", tail("The last night!").pronunciation());
        assertTrue(LyricRhyme.tail("I have to read").isEmpty());
        assertTrue(LyricRhyme.tail("在这里 Glorphxyz").isEmpty());
        assertTrue(LyricRhyme.tail("stop at 8").isEmpty());
    }

    @Test
    void selectsTheActualLanguageAtTheEndOfMixedLyrics() {
        var mandarin = tail("Take my hand，等你回来。");
        var english = tail("这一次说 goodbye!");
        assertEquals("zh-CN", mandarin.language());
        assertEquals("en", english.language());
        assertEquals(NONE, LyricRhyme.match(mandarin, english, false));
        assertEquals(CROSS_LANGUAGE, LyricRhyme.match(mandarin, english, true));
        assertEquals(CROSS_LANGUAGE, LyricRhyme.match(english, mandarin, true));
        assertEquals(NONE, LyricRhyme.match(mandarin, tail("城市的 night"), true));
        assertEquals(EXACT, compare("一整个 night", "还有一点 light"));
    }

    @Test
    void countsSpokenSyllablesAcrossLanguagesAndRejectsUnknownCounts() {
        assertEquals(8, LyricRhyme.syllableCount("多想再给你个拥抱"));
        assertEquals(6, LyricRhyme.syllableCount("I stayed awake all night"));
        assertEquals(6, LyricRhyme.syllableCount("I stayed awake till dawn"));
        assertEquals(5, LyricRhyme.syllableCount("跟我说 goodbye"));
        assertEquals(3, LyricRhyme.syllableCount("we’re almost"));
        assertEquals(-1, LyricRhyme.syllableCount("the fire"));
        assertEquals(-1, LyricRhyme.syllableCount("here at 8"));
    }

    private static LyricRhyme.Match compare(String first, String second) {
        return LyricRhyme.match(tail(first), tail(second), false);
    }

    private static LyricRhyme.Tail tail(String line) {
        return LyricRhyme.tail(line).orElseThrow(() -> new AssertionError("Missing rhyme tail: " + line));
    }
}
