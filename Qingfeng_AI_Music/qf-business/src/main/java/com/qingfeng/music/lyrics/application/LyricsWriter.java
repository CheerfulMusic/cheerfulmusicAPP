package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.work.api.WorkApi;
public interface LyricsWriter {
    boolean isConfigured();
    Draft write(WorkApi.Work work,String language);
    default Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics){return write(work,language);}
    record Draft(String title,String lyrics) {}
}
