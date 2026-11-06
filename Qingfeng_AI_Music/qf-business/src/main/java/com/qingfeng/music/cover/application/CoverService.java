package com.qingfeng.music.cover.application;
import com.qingfeng.music.cover.api.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.api.MediaApi.Content;
import com.qingfeng.music.media.api.MediaCapacity;
import com.qingfeng.music.usage.UsageBudget;
import com.qingfeng.music.common.exception.DomainException;
import java.io.*;
import org.springframework.transaction.annotation.Transactional;

/** 封面和音乐独立版本；外部图片请求仅由显式编辑任务触发。 */
public class CoverService implements CoverApi,SharedCoverAccess {
 private final WorkApi works;private final CoverRepository repo;private final CoverFileStore files;private final MediaCapacity capacity;
 private final ImageEditProvider provider;private final UsageBudget budget;private final ImageResultDownloader downloader;private final long limit;
 public CoverService(WorkApi works,CoverRepository repo,CoverFileStore files,MediaCapacity capacity,ImageEditProvider provider,UsageBudget budget,ImageResultDownloader downloader,long limit){this.works=works;this.repo=repo;this.files=files;this.capacity=capacity;this.provider=provider;this.budget=budget;this.downloader=downloader;this.limit=limit;}
 public State state(String owner,String work){works.get(owner,work);var selected=repo.selection(work);return new State(selected.id(),selected.version(),repo.list(work),repo.edits(work),provider.configured());}
 public Asset upload(String owner,String work,InputStream input){works.get(owner,work);return save(work,input,"UPLOADED",null).asset();}
 private CoverRepository.Stored save(String work,InputStream input,String origin,String edit){
  long reserved=5L*1024*1024;if(!capacity.reserve(work,reserved,limit))throw new DomainException("STORAGE_QUOTA_EXCEEDED","作品存储容量不足");
  CoverFileStore.Stored saved=null;long committed=0;
  try{saved=files.save(input);var result=repo.insert(work,saved,origin,edit);if(result.key().equals(saved.key()))committed=saved.size();return result;}
  catch(IOException e){throw new DomainException("COVER_STORAGE_UNAVAILABLE","封面保存失败，请稍后重试");}
  finally{try{if(saved!=null&&committed==0)files.discard(saved.key());}catch(IOException ignored){}finally{capacity.release(work,reserved-committed);}}
 }
 public State select(String owner,String work,String id,long version){works.get(owner,work);if(id!=null)find(work,id);if(!repo.select(work,id,version))throw new DomainException("COVER_VERSION_CONFLICT","另一页面已修改封面，请刷新后重试");return state(owner,work);}
 public String current(String work){return repo.selection(work).id();}
 public Content open(String owner,String work,String id){works.get(owner,work);return content(work,id);}
 public Content content(String work,String id){var stored=find(work,id);return new Content(stored.bytes(),"image/jpeg","cover.jpg",()->files.open(stored.key()));}
 public MediaApi.MediaRef ref(String work,String id){var stored=find(work,id);return new MediaApi.MediaRef(stored.key(),stored.bytes(),"image/jpeg","cover.jpg");}
 private CoverRepository.Stored find(String work,String id){return repo.find(work,id).orElseThrow(()->new DomainException("COVER_NOT_FOUND","封面不存在"));}
 @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Edit edit(String owner,String work,String source,String prompt,String key){
  works.get(owner,work);find(work,source);
  if(key==null||!key.matches("[0-9a-fA-F-]{36}")||prompt==null||prompt.isBlank()||prompt.length()>1000)throw new DomainException("INVALID_COVER_EDIT","请填写不超过1000字的封面加工要求");
  var existing=repo.request(work,key);
  if(existing.isPresent()){var old=existing.get();if(!old.edit().sourceId().equals(source)||!old.edit().prompt().equals(prompt.trim()))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已经用于其他封面要求");return old.edit();}
  if(!provider.configured())throw new DomainException("COVER_PROVIDER_UNAVAILABLE","AI封面暂不可用，可以先使用上传的图片");
  budget.reserve(owner,work,key,UsageBudget.Kind.COVER);
  var result=repo.insertJob(work,source,prompt.trim(),key,provider.model()).edit();
  if(!result.sourceId().equals(source)||!result.prompt().equals(prompt.trim()))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已经用于其他封面要求");return result;
 }
 /** 一次只执行一个阶段；提交结果未知不重试，查询/本地归档可重试。 */
 public synchronized void runOnce(){
  if(!provider.configured())return;
  var claimed=repo.claim();if(claimed.isEmpty())return;var job=claimed.get();
  try{switch(job.edit().status()){
   case "QUEUED"->{try(var input=files.open(find(job.work(),job.edit().sourceId()).key())){String external=provider.submit(input.readNBytes(5*1024*1024+1),job.edit().prompt(),job.model());repo.accepted(job,external);}catch(ImageEditProvider.Rejected e){repo.result(job,"FAILED",null,null,"PROVIDER_REJECTED");}catch(Exception e){repo.result(job,"SUBMISSION_UNKNOWN",null,null,"SUBMISSION_UNKNOWN");}}
   case "SUBMITTING"->repo.result(job,"SUBMISSION_UNKNOWN",null,null,"WORKER_INTERRUPTED");
   case "RUNNING"->{var result=provider.query(job.externalId(),job.model());repo.result(job,result.status(),result.url(),null,"FAILED".equals(result.status())?"PROVIDER_FAILED":null);}
   case "SAVING"->{var latest=provider.query(job.externalId(),job.model());if(latest.url()==null)throw new IllegalStateException("封面结果暂不可用");try(var image=downloader.open(latest.url())){var stored=save(job.work(),image,"AI",job.edit().id());repo.result(job,"SUCCEEDED",null,stored.asset().id(),null);}}
   default->{}
  }}catch(DomainException e){if(e.code().startsWith("INVALID_"))repo.result(job,"FAILED",null,null,"INVALID_IMAGE_RESULT");}
  catch(Exception e){/* 保持阶段，只查询或归档重试，绝不重复付费提交。 */}finally{repo.release(job);}
 }
}
