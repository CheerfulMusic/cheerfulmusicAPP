package com.qingfeng.music.generation.api;
import java.time.Instant;
import java.util.List;
import com.qingfeng.music.generation.domain.GenerationStatus;
public interface GenerationApi {
 Task submit(String owner,String work,String revision,String requestKey);
 List<Task> list(String owner,String work);
 void retryArchive(String owner,String work,String task);
 record Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt,String assetId,String errorCode,Instant updatedAt,Instant nextAttemptAt){
  public Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt){this(id,workId,revisionId,workVersion,requestKey,title,lyrics,style,provider,status,createdAt,null,null,createdAt,createdAt);}
 }
}
