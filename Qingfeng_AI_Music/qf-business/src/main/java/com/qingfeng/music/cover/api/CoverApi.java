package com.qingfeng.music.cover.api;
import com.qingfeng.music.media.api.MediaApi.Content;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
public interface CoverApi {
 State state(String owner,String work);
 Asset upload(String owner,String work,InputStream input);
 State select(String owner,String work,String cover,long expectedVersion);
 Content open(String owner,String work,String cover);
 Edit edit(String owner,String work,String source,String prompt,String requestKey);
 record Asset(String id,String origin,Instant createdAt){}
 record State(String currentCoverId,long version,List<Asset> assets,List<Edit> edits,boolean aiAvailable){}
 record Edit(String id,String sourceId,String prompt,String status,String coverId,String errorCode,Instant createdAt){}
}
