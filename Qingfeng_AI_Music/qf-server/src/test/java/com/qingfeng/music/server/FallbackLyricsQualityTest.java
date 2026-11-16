package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A quality rejection must not silently bypass the editor through a second provider. */
class FallbackLyricsQualityTest {
    private static final WorkApi.Work WORK = new WorkApi.Work("offline-work", "妈妈", "生日", "门口的小碗",
        "想让她少操心", "民谣", "小碗", 0, Instant.now(), Instant.now());

    @ParameterizedTest @ValueSource(strings = {"LYRICS_QUALITY_NOT_MET", "LYRICS_QUALITY_UNVERIFIED",
        "LYRICS_PROVIDER_TIMEOUT", "LYRICS_PROVIDER_NETWORK_ERROR", "LYRICS_REQUEST_INTERRUPTED"})
    void qualityAndUncertainEditorialFailuresNeverStartTheQwenBackup(String code) {
        var primary = mock(LyricsWriter.class);
        var backup = mock(LyricsWriter.class);
        when(primary.isConfigured()).thenReturn(true); when(backup.isConfigured()).thenReturn(true);
        when(primary.write(WORK, "zh-CN")).thenThrow(new DomainException(code, "本次未交付歌词"));
        var writer = new FallbackLyricsWriter(primary, backup);
        var error = assertThrows(DomainException.class, () -> writer.write(WORK, "zh-CN"));
        assertEquals(code, error.code());
        verify(primary, times(1)).write(WORK, "zh-CN");
        verify(backup, never()).write(any(), anyString());
    }

    @Test void explicitPreGenerationRejectionStillAllowsOneConfiguredBackup() {
        var primary = mock(LyricsWriter.class);
        var backup = mock(LyricsWriter.class);
        when(primary.isConfigured()).thenReturn(true); when(backup.isConfigured()).thenReturn(true);
        when(primary.source()).thenReturn("OPENAI"); when(backup.source()).thenReturn("QWEN");
        when(primary.write(WORK, "zh-CN")).thenThrow(new DomainException("LYRICS_PROVIDER_REJECTED", "上游明确拒绝"));
        when(backup.write(WORK, "zh-CN")).thenReturn(new LyricsWriter.Draft("小碗", "[Chorus]\n妈，也留一瓣给自己"));
        var result = new FallbackLyricsWriter(primary, backup).write(WORK, "zh-CN");
        assertEquals("QWEN", result.source());
        verify(primary, times(1)).write(WORK, "zh-CN");
        verify(backup, times(1)).write(WORK, "zh-CN");
    }
}
