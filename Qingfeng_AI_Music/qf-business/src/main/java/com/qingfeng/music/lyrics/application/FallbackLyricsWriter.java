package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

public final class FallbackLyricsWriter implements LyricsWriter {
    private static final System.Logger LOG = System.getLogger(FallbackLyricsWriter.class.getName());
    private static final Set<String> SAFE_FAILURES = Set.of("LYRICS_PROVIDER_CONNECT_FAILED", "LYRICS_PROVIDER_REJECTED");
    // Leave time for the backup writer within the studio's 120-second request window.
    private static final long FAILOVER_WINDOW = Duration.ofSeconds(25).toNanos();
    private final LyricsWriter primary;
    private final LyricsWriter backup;

    public FallbackLyricsWriter(LyricsWriter primary, LyricsWriter backup) {
        this.primary = primary;
        this.backup = backup;
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

    private Draft attempt(Function<LyricsWriter, Draft> write) {
        if (backup.isConfigured() && !primary.isConfigured()) return writeWithSource(backup, write);
        long started = System.nanoTime();
        try {
            return writeWithSource(primary, write);
        } catch (DomainException error) {
            if (!SAFE_FAILURES.contains(error.code()) || !backup.isConfigured()
                    || System.nanoTime() - started > FAILOVER_WINDOW) throw error;
            LOG.log(System.Logger.Level.WARNING, "Switching lyric provider from {0} to {1}: {2}",
                primary.source(), backup.source(), error.code());
            return writeWithSource(backup, write);
        }
    }

    private Draft writeWithSource(LyricsWriter writer, Function<LyricsWriter, Draft> write) {
        var draft = write.apply(writer);
        return new Draft(draft.title(), draft.lyrics(), draft.plan(), writer.source());
    }
}
