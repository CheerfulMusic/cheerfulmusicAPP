package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.work.api.WorkApi;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@FunctionalInterface
public interface PhotoLyricsWriter {
    LyricsStructure DEFAULT_STRUCTURE = new LyricsStructure("ABCD", false);

    LyricsWriter.Draft write(WorkApi.Work work, String language, List<byte[]> jpegs, LyricsWriter.Direction direction);

    default LyricsWriter.Draft writeValidated(WorkApi.Work work, String language, List<byte[]> jpegs,
                                             LyricsWriter.Direction direction,
                                             BiFunction<PhotoLyricsWriter, LyricsWriter.Draft, LyricsWriter.Draft> validate) {
        return validate.apply(this, write(work, language, jpegs, direction));
    }

    default Map<Integer, String> repairSections(LyricsWriter.SectionRepair request) {
        throw LyricsStructure.mismatch();
    }
}
