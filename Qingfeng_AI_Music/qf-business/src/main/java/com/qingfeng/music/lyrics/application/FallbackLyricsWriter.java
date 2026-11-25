package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class FallbackLyricsWriter implements LyricsWriter {
    private static final System.Logger LOG = System.getLogger(FallbackLyricsWriter.class.getName());
    private static final Set<String> SAFE_FAILURES = Set.of("LYRICS_PROVIDER_CONNECT_FAILED", "LYRICS_PROVIDER_REJECTED",
        "LYRICS_RESPONSE_INVALID", "LYRICS_STRUCTURE_MISMATCH");
    private static final Duration MINIMUM_BACKUP_TIME = Duration.ofSeconds(30);
    private final LyricsWriter primary;
    private final LyricsWriter backup;
    private final boolean boundResponseBody;

    public FallbackLyricsWriter(LyricsWriter primary, LyricsWriter backup) {
        this(primary, backup, false);
    }

    public FallbackLyricsWriter(LyricsWriter primary, LyricsWriter backup, boolean boundResponseBody) {
        this.primary = primary;
        this.backup = backup;
        this.boundResponseBody = boundResponseBody;
    }

    @Override public boolean isConfigured() { return primary.isConfigured() || backup.isConfigured(); }
    @Override public String source() { return primary.source(); }

    @Override public Draft write(WorkApi.Work work, String language) {
        return attempt(writer -> writer.write(work, language));
    }

    @Override public Draft write(WorkApi.Work work, String language, String instructions, String previousLyrics) {
        return attempt(writer -> writer.write(work, language, instructions, previousLyrics));
    }

    @Override public Draft write(WorkApi.Work work, String language, String instructions, String previousLyrics, String direction) {
        return attempt(writer -> writer.write(work, language, instructions, previousLyrics, direction));
    }

    @Override public Draft write(WorkApi.Work work, String language, String instructions, String previousLyrics, Direction direction) {
        return attempt(writer -> writer.write(work, language, instructions, previousLyrics, direction));
    }

    @Override public Draft writeValidated(WorkApi.Work work, String language, String instructions, String previousLyrics,
                                          Direction direction, BiFunction<LyricsWriter, Draft, Draft> validate) {
        return attempt(writer -> writer.writeValidated(work, language, instructions, previousLyrics, direction, validate));
    }

    @Override public Map<Integer, String> repairSections(SectionRepair request) {
        if (backup.source().equals(request.draft().source())) return backup.repairSections(request);
        return primary.repairSections(request);
    }

    private Draft attempt(Function<LyricsWriter, Draft> write) {
        try (var ignored = LyricsRequestBudget.enter(Duration.ofSeconds(110), boundResponseBody)) {
            if (backup.isConfigured() && !primary.isConfigured()) return writeWithSource(backup, write);
            try {
                return writeWithSource(primary, write);
            } catch (DomainException error) {
                if (!SAFE_FAILURES.contains(error.code()) || !backup.isConfigured()
                        || !LyricsRequestBudget.hasRemaining(MINIMUM_BACKUP_TIME)) throw error;
                LOG.log(System.Logger.Level.WARNING, "Switching lyric provider from {0} to {1}: {2}",
                    primary.source(), backup.source(), error.code());
                return writeWithSource(backup, write);
            }
        }
    }

    private Draft writeWithSource(LyricsWriter writer, Function<LyricsWriter, Draft> write) {
        var draft = write.apply(writer);
        if (boundResponseBody && !LyricsRequestBudget.hasRemaining(Duration.ZERO))
            throw new DomainException("LYRICS_PROVIDER_TIMEOUT", "等待歌词结果超时，请先查看原请求进度。");
        return draft.source() == null ? new Draft(draft.title(), draft.lyrics(), draft.plan(), writer.source()) : draft;
    }
}
