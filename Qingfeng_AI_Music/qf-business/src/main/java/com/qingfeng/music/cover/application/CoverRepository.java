package com.qingfeng.music.cover.application;
import com.qingfeng.music.cover.api.CoverApi.*;
import java.time.Instant;
import java.util.*;
public interface CoverRepository {
 record Stored(String work,String key,long bytes,Asset asset){}
 record Selection(String id,long version){}
 record Job(Edit edit,String work,String requestKey,String model,String externalId,String resultUrl,String token){}
 List<Asset> list(String work);
 Optional<Stored> find(String work,String id);
 Stored insert(String work,CoverFileStore.Stored file,String origin,String edit);
 Selection selection(String work);
 boolean select(String work,String id,long version);
 List<Edit> edits(String work);
 Optional<Job> request(String work,String key);
 Job insertJob(String work,String source,String prompt,String key,String model);
 Optional<Job> claim();
 void accepted(Job job,String external);
 void result(Job job,String state,String url,String cover,String error);
 void release(Job job);
}
