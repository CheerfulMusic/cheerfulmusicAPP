package com.qingfeng.music.lyrics.playback;
import java.util.List;

public interface LyricsTimingProvider {
    boolean isConfigured();
    List<PlaybackLyrics.Word> fetch(String providerTaskId,String archivedAudioUrl);
    /** 服务明确返回失败，可由用户另行发起新请求；网络超时/响应未知不能自动重试。 */
    class Rejected extends RuntimeException {public Rejected(){super("歌词同步服务暂时不可用");}}
}
