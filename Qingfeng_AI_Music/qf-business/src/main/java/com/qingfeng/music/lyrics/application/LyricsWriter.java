package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.work.api.WorkApi;
public interface LyricsWriter {
    boolean isConfigured();
    default String source(){return "AI";}
    record Direction(String style,String exclusions,String vocalGender){}
    default Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,Direction direction){return write(work,language,instructions,previousLyrics,direction.style());}
    Draft write(WorkApi.Work work,String language);
    default Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics){return write(work,language);}
    default Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,String musicDirection){return write(work,language,instructions,previousLyrics);}
    record Draft(String title,String lyrics,com.qingfeng.music.settings.CreativePlan plan) {
        public Draft(String title,String lyrics){this(title,lyrics,null);}
    }
}
