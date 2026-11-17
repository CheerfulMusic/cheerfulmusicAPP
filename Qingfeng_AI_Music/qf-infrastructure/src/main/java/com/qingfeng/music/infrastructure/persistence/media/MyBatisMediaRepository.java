package com.qingfeng.music.infrastructure.persistence.media;
import com.qingfeng.music.media.application.MediaRepository;
import com.qingfeng.music.media.api.MediaApi.Asset;
import java.util.*;
public class MyBatisMediaRepository implements MediaRepository {
 private final MediaMapper mapper;
 private final org.springframework.transaction.support.TransactionTemplate registration,mutation;
 public MyBatisMediaRepository(MediaMapper mapper){this.mapper=mapper;this.registration=null;this.mutation=null;}
 public MyBatisMediaRepository(MediaMapper mapper,org.springframework.transaction.PlatformTransactionManager manager){this.mapper=mapper;this.mutation=new org.springframework.transaction.support.TransactionTemplate(manager);this.registration=new org.springframework.transaction.support.TransactionTemplate(manager);this.registration.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public Stored register(Asset a,String key,String task,long reserved){
  return registerCandidate(a,key,task,reserved,"primary",null);
 }
 public Stored registerCandidate(Asset a,String key,String task,long reserved,String candidate,com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo source){
  if(registration==null)throw new IllegalStateException("音频登记必须配置独立短事务");
  var completion=new java.util.concurrent.atomic.AtomicInteger(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
  try{return registration.execute(tx->{
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){completion.set(status);}});
   Stored effective;if(task==null){insert(a,key);effective=new Stored(a,key);}else effective=insertCandidate(a,key,task,candidate,source);
   long bytes=effective.storageKey().equals(key)?a.byteSize():0;release(a.workId(),reserved-bytes);return effective;
  });}catch(RuntimeException error){throw new RegistrationFailure(error,completion.get()==org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);}
 }
 public Optional<String> generatedRevision(String work,String asset){return Optional.ofNullable(mapper.revision(work,asset));}
 public void insert(Asset a,String key){var r=new MediaRow();r.id=a.id();r.workId=a.workId();r.storageKey=key;r.origin=a.origin();r.contentType=a.contentType();r.byteSize=a.byteSize();r.sha256=a.sha256();r.createdAt=a.createdAt();mapper.insert(r);mapper.ensureSelection(a.workId(),null,0);mapper.automaticSelection(a.workId(),a.id());}
 private Asset asset(MediaRow r){return new Asset(r.id,r.workId,r.origin,r.contentType,r.byteSize,r.sha256,r.createdAt,r.sectionEdit,r.generationTaskId,r.candidateKey,r.note==null?"":r.note,r.noteVersion,r.versionNumber);}
 public com.qingfeng.music.media.api.MediaApi.Selection selection(String work){var r=mapper.selection(work);return r==null?new com.qingfeng.music.media.api.MediaApi.Selection(null,0,null):new com.qingfeng.music.media.api.MediaApi.Selection((String)r.get("asset_id"),((Number)r.get("version")).longValue(),null);}
 public boolean select(String work,String asset,long version){
  return mutation.execute(tx->{var row=mapper.lockAsset(work,asset);if(row==null||row.deletedAt!=null)return false;mapper.ensureSelection(work,null,0);return mapper.select(work,asset,version)==1;});
 }
 public boolean saveNote(String work,String asset,String note,long version){return mutation.execute(tx->{
  var row=mapper.lockAsset(work,asset);if(row==null||row.deletedAt!=null)return false;
  mapper.ensureAnnotation(asset);return mapper.saveNote(work,asset,note,version)==1;
 });}
 public void deleteVersion(String work,String asset){
  mutation.executeWithoutResult(tx->{
   var row=mapper.lockAsset(work,asset);
   if(row==null)throw new com.qingfeng.music.common.exception.DomainException("MEDIA_NOT_FOUND","音频不存在");
   if(row.deletedAt!=null)return;
   var choice=mapper.lockSelection(work);
   if(choice!=null&&asset.equals(choice.get("asset_id")))throw new com.qingfeng.music.common.exception.DomainException("AUDIO_VERSION_SELECTED","请先切换到其他版本，再删除当前版本。");
   if(mapper.published(work,asset)>0)throw new com.qingfeng.music.common.exception.DomainException("AUDIO_VERSION_PUBLISHED","此版本已发布，请先更换公开版本或设为私密。");
   if(mapper.visibleCount(work)<=1)throw new com.qingfeng.music.common.exception.DomainException("AUDIO_VERSION_LAST","至少保留一个歌曲版本；如需全部删除，请删除作品。");
   mapper.ensureAnnotation(asset);mapper.deleteVersion(work,asset);
  });
 }
 public void keepOriginal(String work,String asset){mapper.ensureSelection(work,asset,1);}
 public Optional<Stored> find(String work,String id){return Optional.ofNullable(mapper.find(work,id)).map(r->new Stored(asset(r),r.storageKey));}
 public List<Asset> list(String work){return mapper.list(work).stream().map(this::asset).toList();}
 public Optional<Stored> findGenerated(String task){return Optional.ofNullable(mapper.generated(task)).map(r->new Stored(asset(r),r.storageKey));}
 public Optional<Stored> findGenerated(String task,String candidate){return Optional.ofNullable(mapper.candidate(task,candidate)).map(r->new Stored(asset(r),r.storageKey));}
 public Stored insertGenerated(Asset a,String key,String task){return insertCandidate(a,key,task,"primary",null);}
 private Stored insertCandidate(Asset a,String key,String task,String candidate,com.qingfeng.music.generation.application.port.MusicProvider.AudioInfo source){var r=new MediaRow();r.id=a.id();r.workId=a.workId();r.storageKey=key;r.origin=a.origin();r.contentType=a.contentType();r.byteSize=a.byteSize();r.sha256=a.sha256();r.createdAt=a.createdAt();r.candidateKey=candidate;if(source!=null){r.providerAudioId=source.audioId();r.sourceAudioUrl=source.audioUrl();r.durationS=source.duration();}mapper.insertGenerated(r,task);var result=findGenerated(task,candidate).orElseThrow();if(result.asset().id().equals(a.id())&&candidate.equals("primary")&&mapper.section(task)==0){mapper.ensureSelection(a.workId(),null,0);mapper.automaticSelection(a.workId(),a.id());}return result;}
 public boolean reserve(String work,long bytes,long capacity){return mapper.reserve(work,bytes,capacity)==1;}
 public void release(String work,long bytes){mapper.release(work,bytes);}
}
