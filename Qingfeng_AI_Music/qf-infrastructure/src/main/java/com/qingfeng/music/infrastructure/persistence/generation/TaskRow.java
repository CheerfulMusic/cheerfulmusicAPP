package com.qingfeng.music.infrastructure.persistence.generation;
import java.time.Instant;
public class TaskRow {
 public String id,workId,revisionId,requestKey,title,lyrics,style,provider,status,vocalGender;
 public long workVersion;
 public Instant createdAt;
 public String providerTaskId,audioUrl,assetId,leaseToken,lastErrorCode;
 public int submitAttempts,archiveAttempts,queryFailures;
 public Instant leaseUntil,nextAttemptAt,updatedAt;
}
