package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public final class FallbackPhotoLyricsWriter implements PhotoLyricsWriter {
    private static final System.Logger LOG = System.getLogger(FallbackPhotoLyricsWriter.class.getName());
    private static final Set<String> CONFIRMED_FAILURES = Set.of(
        "LYRICS_PROVIDER_CONNECT_FAILED", "LYRICS_PROVIDER_REJECTED", "LYRICS_RESPONSE_INVALID");
    private final PhotoLyricsWriter doubao;
    private final PhotoLyricsWriter qwen;

    public FallbackPhotoLyricsWriter(PhotoLyricsWriter doubao, PhotoLyricsWriter qwen) {
        this.doubao = doubao;
        this.qwen = qwen;
    }

    @Override
    public LyricsWriter.Draft write(WorkApi.Work work, String language, List<byte[]> photos, LyricsWriter.Direction direction) {
        try (var ignored = LyricsRequestBudget.enter(Duration.ofSeconds(110))) {
            try {
                return doubao.write(work, language, photos, direction);
            } catch (DomainException failure) {
                if (!CONFIRMED_FAILURES.contains(failure.code())) throw failure;
                LOG.log(System.Logger.Level.WARNING, "Switching photo lyrics from Doubao to Qwen: {0}", failure.code());
                return qwen.write(work, language, photos, direction);
            }
        }
    }
}
