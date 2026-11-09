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
 private final WorkApi works;private final LyricsApi lyrics;private final MusicProvider provider;private final TaskRepository repository;private final ExecutionRepository execution;private final boolean enabled;private final com.qingfeng.music.usage.UsageBudget budget;
 public GenerationService(WorkApi works,LyricsApi lyrics,MusicProvider provider,TaskRepository repository,ExecutionRepository execution){this(works,lyrics,provider,repository,execution,true);}
 public GenerationService(WorkApi works,LyricsApi lyrics,MusicProvider provider,TaskRepository repository,ExecutionRepository execution,boolean enabled){this(works,lyrics,provider,repository,execution,enabled,(o,w,k,t)->{});}
 public GenerationService(WorkApi works,LyricsApi lyrics,MusicProvider provider,TaskRepository repository,ExecutionRepository execution,boolean enabled,com.qingfeng.music.usage.UsageBudget budget){this.works=works;this.lyrics=lyrics;this.provider=provider;this.repository=repository;this.execution=execution;this.enabled=enabled;this.budget=budget;}
 private com.qingfeng.music.settings.MusicSettings settings;
 public GenerationService withSettings(com.qingfeng.music.settings.MusicSettings value){settings=value;return this;}
 /** 不占额、不落库、不调用供应商；正式提交仍在事务内重新校验并冻结快照。 */
 public Preflight preflight(String owner,String work,String revisionId){
  var w=works.get(owner,work);var revision=lyrics.get(owner,work,revisionId);
  if(!revision.confirmed())throw new DomainException("LYRICS_NOT_CONFIRMED","请先确认歌词");
  if(revision.workVersion()!=w.version())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请保存并确认当前版本歌词");
  available();
  var direction=settings==null?null:settings.forGeneration(owner,w);
  var request=request(w,revision,direction);provider.validate(request);
  var limits=provider.limits();
  return new Preflight(w.version(),revisionId,request.style().length(),limits.style(),request.negativeTags().length(),limits.negativeTags(),direction!=null&&direction.compilation()!=null?direction.compilation().notice():"");
 }
 private void available(){
  if(!enabled||!provider.isConfigured())throw new DomainException("MUSIC_PROVIDER_UNAVAILABLE","音乐生成暂不可用，内容已保留，尚未提交生成。");
  provider.checkAdmission();
 }
 private MusicProvider.Request request(WorkApi.Work work,LyricsApi.Revision revision,com.qingfeng.music.settings.MusicSettings.Direction direction){
  return direction==null?new MusicProvider.Request(revision.title(),revision.lyrics(),work.style(),work.vocalGender()):new MusicProvider.Request(revision.title(),revision.lyrics(),direction.style(),direction.effectiveVoice(work.vocalGender()),direction.negativeTags(),true);
 }
 // 并发重复插入后重新读取已提交任务，避免MySQL可重复读快照看不到竞争方的新记录。
 @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
 public Task submit(String owner,String work,String revisionId,String key){
  var w=works.get(owner,work);
  if(key==null||!key.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_REQUEST_KEY","请提供有效的请求标识");
  var existing=repository.findRequest(work,key);if(existing.isPresent())return checked(existing.get(),revisionId);
  var revision=lyrics.get(owner,work,revisionId);
  if(!revision.confirmed())throw new DomainException("LYRICS_NOT_CONFIRMED","请先确认歌词");
  if(revision.workVersion()!=w.version())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请保存并确认当前版本歌词");
  available();
  // 与局部编辑保持准入锁→作品锁顺序；后续校验失败会随整个事务回滚占额。
  budget.reserve(owner,work,key,com.qingfeng.music.usage.UsageBudget.Kind.MUSIC);
  var direction=settings==null?null:settings.forGeneration(owner,w);
  String voice=direction==null?w.vocalGender():direction.effectiveVoice(w.vocalGender());
  provider.validate(direction==null?new MusicProvider.Request(revision.title(),revision.lyrics(),w.style(),voice):new MusicProvider.Request(revision.title(),revision.lyrics(),direction.style(),voice,direction.negativeTags(),true));
  Instant now=Instant.now();
  String id=UUID.randomUUID().toString();var task=checked(repository.insertOrRead(new Task(id,work,revisionId,w.version(),key,revision.title(),revision.lyrics(),w.style(),provider.name(),GenerationStatus.QUEUED,now,null,null,now,now.plusSeconds(5),voice)),revisionId);
  if(settings!=null&&task.id().equals(id))settings.snapshot(id,direction);return task;
 }
 public List<Task> list(String owner,String work){works.get(owner,work);return repository.list(work);}
 public Task closeChecking(String owner,String work,String id,boolean acknowledge){
  works.get(owner,work);
  var task=repository.list(work).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new DomainException("TASK_NOT_FOUND","生成任务不存在"));
  if(!acknowledge)throw new DomainException("INVALID_CHECKING_ACKNOWLEDGEMENT","请先确认：结束等待不会取消平台任务或返还本次创作额度。");
  if(task.status()==GenerationStatus.FAILED&&"CHECKING_CLOSED".equals(task.errorCode()))return task;
  if(!execution.closeChecking(work,id,Instant.now()))throw new DomainException("CHECKING_CLOSE_CONFLICT","目前不能结束等待：请至少等待3分钟；已取得平台任务编号的任务会继续自动查询。请刷新状态。");
  return repository.findRequest(work,task.requestKey()).orElseThrow();
 }
 public void retryArchive(String owner,String work,String task){works.get(owner,work);if(!execution.retryArchive(work,task,Instant.now()))throw new DomainException("ARCHIVE_RETRY_CONFLICT","该任务不支持仅重试音频保存，请刷新状态");}
 public Task cancel(String owner,String work,String id){
  works.get(owner,work);
  var task=repository.list(work).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new DomainException("TASK_NOT_FOUND","生成任务不存在"));
  if(task.status()==GenerationStatus.CANCELLED)return task;
  if(!budget.cancelMusic(work,task.requestKey(),()->repository.cancelQueued(work,id)))throw new DomainException("TASK_ALREADY_SUBMITTED","歌曲已开始提交，无法撤销；请等待结果，避免重复生成。");
  return repository.findRequest(work,task.requestKey()).orElseThrow();
 }
 private Task checked(Task task,String revision){if(!task.revisionId().equals(revision))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于另一个歌词版本，请勿复用");return task;}
}
