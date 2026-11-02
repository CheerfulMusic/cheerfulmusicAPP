package com.qingfeng.music.infrastructure.persistence.media;
import com.qingfeng.music.media.application.MediaRepository;
import com.qingfeng.music.media.api.MediaApi.Asset;
import java.util.*;
public class MyBatisMediaRepository implements MediaRepository {
 private final MediaMapper mapper;
 public MyBatisMediaRepository(MediaMapper mapper){this.mapper=mapper;}
 public void insert(Asset a,String key){var r=new MediaRow();r.id=a.id();r.workId=a.workId();r.storageKey=key;r.origin=a.origin();r.contentType=a.contentType();r.byteSize=a.byteSize();r.sha256=a.sha256();r.createdAt=a.createdAt();mapper.insert(r);}
 private Asset asset(MediaRow r){return new Asset(r.id,r.workId,r.origin,r.contentType,r.byteSize,r.sha256,r.createdAt);}
 public Optional<Stored> find(String work,String id){return Optional.ofNullable(mapper.find(work,id)).map(r->new Stored(asset(r),r.storageKey));}
 public List<Asset> list(String work){return mapper.list(work).stream().map(this::asset).toList();}
 public Optional<Stored> findGenerated(String task){return Optional.ofNullable(mapper.generated(task)).map(r->new Stored(asset(r),r.storageKey));}
 public Stored insertGenerated(Asset a,String key,String task){var r=new MediaRow();r.id=a.id();r.workId=a.workId();r.storageKey=key;r.origin=a.origin();r.contentType=a.contentType();r.byteSize=a.byteSize();r.sha256=a.sha256();r.createdAt=a.createdAt();mapper.insertGenerated(r,task);return findGenerated(task).orElseThrow();}
 public boolean reserve(String work,long bytes,long capacity){return mapper.reserve(work,bytes,capacity)==1;}
 public void release(String work,long bytes){mapper.release(work,bytes);}
}
