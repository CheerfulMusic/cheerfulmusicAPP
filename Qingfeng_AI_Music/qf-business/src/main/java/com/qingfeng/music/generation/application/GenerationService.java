package com.qingfeng.music.generation.application;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** 仅落库任务快照，不在请求线程调用供应商。供应商不可用时不创建虚假排队任务。 */
public class GenerationService implements GenerationApi {
 private final WorkApi works;private final LyricsApi lyrics;private final MusicProvider provider;private final TaskRepository repository;private final ExecutionRepository execution;private final boolean enabled;
 public GenerationService(WorkApi works,LyricsApi lyrics,MusicProvider provider,TaskRepository repository,ExecutionRepository execution){this(works,lyrics,provider,repository,execution,true);}
 public GenerationService(WorkApi works,LyricsApi lyrics,MusicProvider provider,TaskRepository repository,ExecutionRepository execution,boolean enabled){this.works=works;this.lyrics=lyrics;this.provider=provider;this.repository=repository;this.execution=execution;this.enabled=enabled;}
 // 并发重复插入后重新读取已提交任务，避免MySQL可重复读快照看不到竞争方的新记录。
 @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Task submit(String owner,String work,String revisionId,String key){
  var w=works.get(owner,work);
  if(key==null||!key.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_REQUEST_KEY","请提供有效的请求标识");
  var existing=repository.findRequest(work,key);if(existing.isPresent())return checked(existing.get(),revisionId);
  var revision=lyrics.get(owner,work,revisionId);
  if(!revision.confirmed())throw new DomainException("LYRICS_NOT_CONFIRMED","请先确认歌词");
  if(revision.workVersion()!=w.version())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请保存并确认当前版本歌词");
  if(!enabled||!provider.isConfigured())throw new DomainException("MUSIC_PROVIDER_UNAVAILABLE","音乐模型暂不可用，未提交生成或创建付费任务");
  provider.validate(new MusicProvider.Request(revision.title(),revision.lyrics(),w.style()));
  return checked(repository.insertOrRead(new Task(UUID.randomUUID().toString(),work,revisionId,w.version(),key,revision.title(),revision.lyrics(),w.style(),provider.name(),GenerationStatus.QUEUED,Instant.now())),revisionId);
 }
 public List<Task> list(String owner,String work){works.get(owner,work);return repository.list(work);}
 public void retryArchive(String owner,String work,String task){works.get(owner,work);if(!execution.retryArchive(work,task,Instant.now()))throw new DomainException("ARCHIVE_RETRY_CONFLICT","该任务不支持仅重试音频保存，请刷新状态");}
 private Task checked(Task task,String revision){if(!task.revisionId().equals(revision))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于另一个歌词版本，请勿复用");return task;}
}
