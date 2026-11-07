package com.qingfeng.music.generation.api;
import java.time.Instant;
import java.util.List;
import com.qingfeng.music.generation.domain.GenerationStatus;
public interface GenerationApi {
 record Preflight(long workVersion,String revisionId,int styleCharacters,int styleLimit,int negativeCharacters,int negativeLimit,String notice){}
 default Preflight preflight(String owner,String work,String revision){throw new UnsupportedOperationException();}
 Task submit(String owner,String work,String revision,String requestKey);
 List<Task> list(String owner,String work);
 void retryArchive(String owner,String work,String task);
 Task cancel(String owner,String work,String task);
 Task closeChecking(String owner,String work,String task,boolean acknowledge);
 record Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt,String assetId,String errorCode,Instant updatedAt,Instant nextAttemptAt,String vocalGender){
  public Task{vocalGender=com.qingfeng.music.work.api.VocalGender.normalize(vocalGender);}
  public Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt){this(id,workId,revisionId,workVersion,requestKey,title,lyrics,style,provider,status,createdAt,"AUTO");}
  public Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt,String vocalGender){this(id,workId,revisionId,workVersion,requestKey,title,lyrics,style,provider,status,createdAt,null,null,createdAt,createdAt,vocalGender);}
  public Task(String id,String workId,String revisionId,long workVersion,String requestKey,String title,String lyrics,String style,String provider,GenerationStatus status,Instant createdAt,String assetId,String errorCode,Instant updatedAt,Instant nextAttemptAt){this(id,workId,revisionId,workVersion,requestKey,title,lyrics,style,provider,status,createdAt,assetId,errorCode,updatedAt,nextAttemptAt,"AUTO");}
 }
}
