package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.work.api.WorkApi;

@FunctionalInterface
public interface PhotoLyricsWriter {
    LyricsWriter.Draft write(WorkApi.Work work, String language, byte[] jpeg, LyricsWriter.Direction direction);
}
