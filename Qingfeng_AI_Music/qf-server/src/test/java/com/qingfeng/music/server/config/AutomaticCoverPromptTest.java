package com.qingfeng.music.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class AutomaticCoverPromptTest {
    @ParameterizedTest
    @CsvSource({
            "抒情流行, 流行",
            "Dark House, 电子",
            "Indie Folk, 民谣与乡村",
            "国风古韵, 古风与国风",
            "独立摇滚, 摇滚与独立",
            "R&B节奏蓝调, 爵士与灵魂乐",
            "English Hip-Hop, 嘻哈与说唱",
            "Chill Lo-fi, 氛围与轻音乐",
            "无人归类, 流行"
    })
    void routesKnownStylesAndFallsBackToPop(String style, String group) {
        String prompt = new AutomaticCoverPrompt().create("id", "歌名", "", "", style);
        assertTrue(prompt.contains("主风格（" + group + "）"));
        assertTrue(prompt.contains("画面内无人物、脸、人体、人物剪影、歌名"));
        assertTrue(prompt.contains("仅供理解，无需绘制任何字符"));
    }

    @Test
    void keepsASecondaryStyleSubtleAndUsesTheChorusAsTheme() {
        String prompt = new AutomaticCoverPrompt().create("id", "海的回音", """
                [Verse]
                远处的光熄灭
                [Chorus]
                风把船铃吹进长夜
                13912345678 name@example.com https://example.com/private
                """, "不需要的创作描述", "国风 + Techno");
        assertTrue(prompt.contains("主风格（古风与国风）"));
        assertTrue(prompt.contains("次风格特征（电子）"));
        assertTrue(prompt.contains("歌词意象来源：风把船铃吹进长夜"));
        assertFalse(prompt.contains("不需要的创作描述"));
        assertFalse(prompt.contains("13912345678"));
        assertFalse(prompt.contains("example.com"));
    }

    @Test
    void consecutiveSongsInTheSameGroupUseDifferentCompositionDirections() {
        var prompts = new AutomaticCoverPrompt();
        // These IDs and titles yield the same initial slot.
        String first = prompts.create("job-1", "夜车", null, null, "House");
        String second = prompts.create("job-5", "夜车", null, null, "House");
        assertEquals(composition(first), composition(new AutomaticCoverPrompt().create("job-5", "夜车", null, null, "House")));
        assertNotEquals(composition(first), composition(second));
    }

    private String composition(String prompt) {
        return prompt.lines().filter(line -> line.startsWith("本首构图：")).findFirst().orElseThrow();
    }
}
