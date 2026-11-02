package com.qingfeng.music.generation.application.port;
import java.io.InputStream;
/** 仅供可信任务执行器调用，不映射到用户可指定来源的上传接口。 */
public interface GeneratedAudioArchive {
    String store(String taskId, String workId, InputStream audio);
}
