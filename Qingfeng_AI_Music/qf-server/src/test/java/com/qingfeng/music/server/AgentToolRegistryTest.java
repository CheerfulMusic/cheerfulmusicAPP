package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.application.AgentToolRegistry;
import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验收 3：白名单 + 参数校验（style 走 StyleCatalog、bpm 40–220、language zh/en/yue、文本 ≤4000）。 */
class AgentToolRegistryTest {

    private final AgentToolRegistry registry = new AgentToolRegistry();

    @Test
    void validGenerateSongPasses() {
        assertDoesNotThrow(() -> registry.validate(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("一首旅行的歌", "温暖民谣", "zh", 120, null, null)));
    }

    @Test
    void invalidStyleIsRejected() {
        DomainException error = assertThrows(DomainException.class, () -> registry.validate(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("歌", "不存在的风格", "zh", 120, null, null)));
        assertEquals("INVALID_STYLE", error.code());
    }

    @Test
    void bpmOutOfRangeIsRejected() {
        DomainException error = assertThrows(DomainException.class, () -> registry.validate(AgentTool.SET_STYLE,
                new AgentTool.SetStyleArgs("温暖民谣", null, 999, "zh", null)));
        assertEquals("INVALID_BPM", error.code());

        assertDoesNotThrow(() -> registry.validate(AgentTool.SET_STYLE,
                new AgentTool.SetStyleArgs("温暖民谣", null, 40, "zh", null)));
        assertDoesNotThrow(() -> registry.validate(AgentTool.SET_STYLE,
                new AgentTool.SetStyleArgs("温暖民谣", null, 220, "zh", null)));
    }

    @Test
    void invalidLanguageIsRejected() {
        DomainException error = assertThrows(DomainException.class, () -> registry.validate(AgentTool.GENERATE_LYRICS,
                new AgentTool.GenerateLyricsArgs("歌", "fr", null)));
        assertEquals("INVALID_LANGUAGE", error.code());
    }

    @Test
    void oversizedTextIsRejected() {
        String tooLong = "歌".repeat(4001);
        DomainException error = assertThrows(DomainException.class, () -> registry.validate(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs(tooLong, "温暖民谣", "zh", 120, null, null)));
        assertEquals("INVALID_TOOL_ARGUMENT", error.code());
    }

    @Test
    void getCurrentSongHasNoRequiredArgs() {
        assertDoesNotThrow(() -> registry.validate(AgentTool.GET_CURRENT_SONG,
                new AgentTool.GetCurrentSongArgs()));
    }
}
