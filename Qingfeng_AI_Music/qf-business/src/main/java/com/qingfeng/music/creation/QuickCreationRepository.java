package com.qingfeng.music.creation;

import java.time.Instant;
import java.util.Optional;

/** 一键创作的持久化检查点；租约只保护推进权，不在数据库事务中执行模型请求。 */
public interface QuickCreationRepository {
    record Entry(String id,String owner,String workId,long workVersion,String requestKey,String language,
                 String status,String lyricsKey,String musicKey,String revisionId,String taskId,
                 String errorCode,String leaseToken,Instant createdAt) {}
    Entry create(Entry entry);
    Optional<Entry> find(String work,String id);
    Optional<Entry> latest(String work);
    Optional<Entry> claim(String token);
    boolean checkpoint(String id,String token,String status,String revision,String task,String error,int delaySeconds);
    void release(String id,String token);
    boolean retry(String work,String id,String lyricsKey);
}
