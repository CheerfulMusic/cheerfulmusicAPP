package com.qingfeng.music.generation.application;
import com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo;
import java.util.List;

/** 查询结果清单先持久化；归档重试只下载缺少的候选，绝不再次生成。 */
public interface AudioCandidates {
    record Candidate(String key,AudioInfo audio){}
    List<Candidate> get(String task);
    void remember(String task,List<AudioInfo> audio);
}
