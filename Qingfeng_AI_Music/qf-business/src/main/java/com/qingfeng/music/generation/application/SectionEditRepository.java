package com.qingfeng.music.generation.application;

import java.time.Instant;
import java.util.*;
import com.qingfeng.music.generation.application.port.MusicProvider;

/** 局部修改只增加版本，不修改原任务快照和原音频。供应商标识仅在后端使用。 */
public interface SectionEditRepository {
    record Source(String taskId,String provider,String providerTaskId,String audioUrl,String audioId,Double duration,
                  String title,String lyrics,String style,String vocalGender){}
    record Edit(String taskId,String sourceAssetId,String sourceProviderTaskId,String sourceProviderAudioId,
                double start,double end,double sourceDuration,int textStart,int textEnd,String replacementLyrics,String requestHash){
        public MusicProvider.Replacement replacement(){return new MusicProvider.Replacement(sourceProviderTaskId,sourceProviderAudioId,start,end,replacementLyrics);}
    }
    record Item(String taskId,String sourceAssetId,String resultAssetId,double start,double end,String replacementLyrics,
                String status,String errorCode,String title,Instant createdAt){}
    Optional<Source> source(String work,String asset);
    Optional<Edit> edit(String task);
    void insert(Edit edit);
    void recordAudio(String task,MusicProvider.AudioInfo info);
    List<Item> list(String work);
}
