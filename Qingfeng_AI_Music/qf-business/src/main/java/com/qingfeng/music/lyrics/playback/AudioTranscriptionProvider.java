package com.qingfeng.music.lyrics.playback;

import java.util.List;

/** Submission and polling are separate so a restart never loses an accepted provider task. */
public interface AudioTranscriptionProvider {
    boolean isConfigured();
    String submit(String audioUrl);
    Result poll(String taskId);
    record Result(String state, List<PlaybackLyrics.Word> words) {}
    class Rejected extends RuntimeException {
        public final String code;
        public Rejected(String code) { super("音频识别服务明确拒绝请求"); this.code = code; }
    }
}
