package com.qingfeng.music.generation.application.port;
import java.io.InputStream;
/** 仅供可信任务执行器调用，不映射到用户可指定来源的上传接口。 */
public interface GeneratedAudioArchive {
    String store(String taskId, String workId, InputStream audio);
    default java.util.Optional<String> existingCandidate(String task,String candidate){return java.util.Optional.empty();}
    default String storeCandidate(String task,String work,String candidate,MusicProvider.AudioInfo source,InputStream audio){
        if(!candidate.equals("primary"))throw new UnsupportedOperationException("归档器不支持多候选音频");
        return store(task,work,audio);
    }
}
