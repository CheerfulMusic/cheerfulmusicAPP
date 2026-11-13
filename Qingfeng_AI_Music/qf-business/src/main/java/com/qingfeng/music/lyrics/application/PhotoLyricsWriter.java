package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.work.api.WorkApi;
import java.util.List;

@FunctionalInterface
public interface PhotoLyricsWriter {
    LyricsWriter.Draft write(WorkApi.Work work, String language, List<byte[]> jpegs, LyricsWriter.Direction direction);
}
