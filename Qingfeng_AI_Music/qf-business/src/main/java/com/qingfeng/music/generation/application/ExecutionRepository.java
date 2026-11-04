package com.qingfeng.music.generation.application;

import com.qingfeng.music.generation.api.GenerationApi.Task;
import com.qingfeng.music.generation.domain.GenerationStatus;
import java.time.Instant;
import java.util.Optional;

/** 原子条件更新与租约由仓储实现；网络调用不能处于数据库事务中。 */
public interface ExecutionRepository {
    record Execution(Task task, String providerTaskId, String audioUrl, int submitAttempts,
                     int archiveAttempts, int queryFailures, String leaseToken) {}
    boolean lockProvider(String provider, String token, Instant now, Instant until);
    void unlockProvider(String provider, String token);
    Optional<Execution> claim(String provider, String token, Instant now, Instant until);
    void release(String task, String token);
    boolean renew(String provider,String task,String token,Instant now,Instant until);
    boolean startSubmission(String task, String token, Instant now);
    void accepted(String task, String externalId, Instant now, Instant next);
    void unknown(String task, String token, String code, Instant now);
    void running(String task, String token, int failures, String code, Instant now, Instant next);
    void saving(String task, String token, String url, Instant now);
    void refreshAudioUrl(String task,String token,String url);
    void archiveFailed(String task, String token, int attempts, boolean terminal, Instant now, Instant next);
    void succeeded(String task, String token, String asset, Instant now);
    void failed(String task, String token, String code, Instant now);
    boolean retryArchive(String work, String task, Instant now);
    default boolean closeChecking(String work,String task,Instant now){return false;}
    String attempt(String task, String phase, Instant now);
    void finishAttempt(String id, String outcome, String code, Instant now);
}
