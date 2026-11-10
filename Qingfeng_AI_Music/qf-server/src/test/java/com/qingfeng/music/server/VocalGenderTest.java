package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.settings.CreativePlan;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.VocalGender;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 人声的取值、优先级与请求归一化。
 * 优先级规则：作品上的明确选择（MALE/FEMALE）永远优先；只有作品是 AUTO 时才采用创作计划的人声。
 * 这保证「用户最后一次明确修改」不会被更早的 Agent 解析或计划悄悄盖掉。
 */
class VocalGenderTest {
    private static MusicSettings.Preferences preferences() {
        return new MusicSettings.Preferences(1, null, Map.of(), "AUTO", "", "");
    }

    private static MusicSettings.Direction direction(String planVoice) {
        var plan = new CreativePlan("", "", "", "", "", planVoice);
        return new MusicSettings.Direction(preferences(), "style", "", "zh", "en", 1, plan);
    }

    @Test void normalizesOnlyTheThreeStationValues() {
        assertEquals("AUTO", VocalGender.normalize(null));
        assertEquals("AUTO", VocalGender.normalize("AUTO"));
        assertEquals("MALE", VocalGender.normalize("MALE"));
        assertEquals("FEMALE", VocalGender.normalize("FEMALE"));
        // 供应商的 m/f 不得直接透传到业务层。
        assertEquals("INVALID_VOCAL_GENDER", assertThrows(DomainException.class, () -> VocalGender.normalize("f")).code());
        assertEquals("INVALID_VOCAL_GENDER", assertThrows(DomainException.class, () -> VocalGender.normalize("female")).code());
    }

    @Test void explicitVoiceOnTheWorkAlwaysWins() {
        assertEquals("FEMALE", direction("MALE").effectiveVoice("FEMALE"));
        assertEquals("MALE", direction("FEMALE").effectiveVoice("MALE"));
    }

    @Test void autoFallsBackToTheCreativePlan() {
        assertEquals("FEMALE", direction("FEMALE").effectiveVoice("AUTO"));
        assertEquals("MALE", direction("MALE").effectiveVoice("AUTO"));
        assertEquals("AUTO", direction("AUTO").effectiveVoice("AUTO"));
    }

    @Test void autoWithoutPlanStaysAuto() {
        var noPlan = new MusicSettings.Direction(preferences(), "style", "", "zh", "en", 1);
        assertNull(noPlan.creativePlan());
        assertEquals("AUTO", noPlan.effectiveVoice("AUTO"));
        assertEquals("FEMALE", noPlan.effectiveVoice("FEMALE"));
    }

    @Test void providerRequestNormalizesVoiceAndRejectsUnknownValues() {
        assertEquals("AUTO", new MusicProvider.Request("标题", "歌词", "民谣").vocalGender());
        assertEquals("FEMALE", new MusicProvider.Request("标题", "歌词", "民谣", "FEMALE").vocalGender());
        assertEquals("AUTO", new MusicProvider.Request("标题", "歌词", "民谣", (String) null).vocalGender());
        assertEquals("INVALID_VOCAL_GENDER",
            assertThrows(DomainException.class, () -> new MusicProvider.Request("标题", "歌词", "民谣", "ROBOT", "", false)).code());
        assertEquals("", new MusicProvider.Request("标题", "歌词", "民谣", "FEMALE", null, false).negativeTags());
    }
}
