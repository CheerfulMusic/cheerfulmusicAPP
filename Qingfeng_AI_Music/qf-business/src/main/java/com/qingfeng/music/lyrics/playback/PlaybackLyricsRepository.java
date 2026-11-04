package com.qingfeng.music.lyrics.playback;
import java.time.Instant;
import java.util.*;

public interface PlaybackLyricsRepository {
    record Source(String assetId,String workId,String provider,String providerTaskId,String audioUrl,String lyrics) {}
    record Stored(String status,List<PlaybackLyrics.Word> words,String requestKey,Instant updatedAt) {}
    Optional<Source> source(String work,String asset);
    Optional<Stored> find(String asset);
    boolean claim(String owner,Source source,String key);
    void finish(String asset,String key,String state,List<PlaybackLyrics.Word> words);
    void expire(String asset,Instant before);
}
