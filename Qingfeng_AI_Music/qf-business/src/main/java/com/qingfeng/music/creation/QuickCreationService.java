package com.qingfeng.music.creation;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import java.util.*;

/** 一个用户动作启动写词和谱曲；各阶段复用固定请求键，恢复不等于再次付费生成。 */
public final class QuickCreationService {
    private static final System.Logger LOG=System.getLogger(QuickCreationService.class.getName());
    private final QuickCreationRepository repository;
    private final WorkApi works;
    private final LyricsApi lyrics;
    private final LyricsCompositionService composer;
    private final GenerationApi generation;
    private final boolean enabled;
    public QuickCreationService(QuickCreationRepository repository,WorkApi works,LyricsApi lyrics,
                                LyricsCompositionService composer,GenerationApi generation,boolean enabled){
        this.repository=repository;this.works=works;this.lyrics=lyrics;this.composer=composer;this.generation=generation;this.enabled=enabled;
    }
    public record View(String id,String workId,String status,String revisionId,String taskId,String errorCode,
                       boolean canRetry,Instant createdAt){}
    public View start(String owner,String work,long version,String key,String language){
        var current=works.get(owner,work);
        if(!enabled)throw new DomainException("QUICK_CREATION_UNAVAILABLE","一键创作暂不可用，可以切换自定义创作，先完善歌词。");
        if(key==null||!key.matches("[0-9a-fA-F-]{36}")||!Set.of("zh-CN","en","mixed").contains(language==null?"":language))
            throw new DomainException("INVALID_QUICK_CREATION","创作参数无效，请刷新后重试。");
        if(current.version()!=version)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请载入当前内容后继续。");
        if(current.story().isBlank()&&current.message().isBlank())throw new DomainException("INVALID_QUICK_CREATION","请先写下一点创作灵感。");
        var entry=new QuickCreationRepository.Entry(UUID.randomUUID().toString(),owner,work,version,key,language,
            "QUEUED",UUID.randomUUID().toString(),UUID.randomUUID().toString(),null,null,null,null,Instant.now());
        return view(owner,repository.create(entry));
    }
    public View get(String owner,String work,String id){works.get(owner,work);return view(owner,required(work,id));}
    public Optional<View> latest(String owner,String work){works.get(owner,work);return repository.latest(work).map(e->view(owner,e));}
    public View retry(String owner,String work,String id){
        var current=works.get(owner,work);var entry=required(work,id);
        if(!enabled)throw new DomainException("QUICK_CREATION_UNAVAILABLE","一键创作暂不可用，可以切换自定义创作，先完善歌词。");
        // 已创建的音乐任务交给原任务恢复策略；不得在这里重新购买音乐。
        var task=music(entry);if(task.isPresent())return view(owner,entry);
        if(current.version()!=entry.workVersion())throw new DomainException("WORK_VERSION_CONFLICT","内容已修改，请切换自定义创作继续制作。");
        if(!repository.retry(work,id,UUID.randomUUID().toString()))throw new DomainException("QUICK_CREATION_CONFLICT","创作仍在处理中，请先刷新进度。");
        return get(owner,work,id);
    }
    private QuickCreationRepository.Entry required(String work,String id){return repository.find(work,id).orElseThrow(()->new DomainException("QUICK_CREATION_NOT_FOUND","创作记录不存在。"));}
    private Optional<GenerationApi.Task> music(QuickCreationRepository.Entry entry){
        return generation.list(entry.owner(),entry.workId()).stream().filter(t->t.requestKey().equals(entry.musicKey())).findFirst();
    }
    private View view(String owner,QuickCreationRepository.Entry entry){
        var task=music(entry);
        return task.map(t->new View(entry.id(),entry.workId(),t.status().name(),t.revisionId(),t.id(),t.errorCode(),false,entry.createdAt()))
            .orElseGet(()->new View(entry.id(),entry.workId(),entry.status(),entry.revisionId(),entry.taskId(),entry.errorCode(),
                entry.status().equals("FAILED")&&works.get(owner,entry.workId()).version()==entry.workVersion(),entry.createdAt()));
    }
    public boolean runOnce(){
        String token=UUID.randomUUID().toString();var found=repository.claim(token);if(found.isEmpty())return false;
        var entry=found.get();String revision=entry.revisionId();
        try{
            var submitted=music(entry);
            if(submitted.isPresent()){checkpoint(entry,token,"MUSIC_QUEUED",submitted.get().revisionId(),submitted.get().id(),null,0);return true;}
            if(works.get(entry.owner(),entry.workId()).version()!=entry.workVersion())throw new DomainException("WORK_VERSION_CONFLICT","内容已修改，请切换自定义创作继续制作。");
            if(revision==null){
                var state=composer.requestState(entry.owner(),entry.workId(),entry.lyricsKey());
                if(Set.of("PENDING","UNKNOWN").contains(state.status())){checkpoint(entry,token,"WRITING",null,null,null,5);return true;}
                if(state.status().equals("FAILED"))throw new DomainException(state.errorCode()==null?"LYRICS_REQUEST_FAILED":state.errorCode(),"歌词未完成，素材已保留。");
                if(state.status().equals("SUCCEEDED"))revision=state.revisionId();
                else{
                    checkpoint(entry,token,"WRITING",null,null,null,0);
                    revision=composer.compose(entry.owner(),entry.workId(),entry.workVersion(),entry.lyricsKey(),entry.language()).id();
                }
                checkpoint(entry,token,"LYRICS_READY",revision,null,null,0);
            }
            lyrics.confirm(entry.owner(),entry.workId(),revision);
            // 预检与提交使用同一版设置；submit 内再次检查版本并冻结音乐快照。
            generation.preflight(entry.owner(),entry.workId(),revision);
            checkpoint(entry,token,"SUBMITTING_MUSIC",revision,null,null,0);
            var task=generation.submit(entry.owner(),entry.workId(),revision,entry.musicKey());
            checkpoint(entry,token,"MUSIC_QUEUED",revision,task.id(),null,0);
        }catch(LeaseLost ignored){/* 其他执行者取得租约后，迟到线程不能继续推进。 */}
        catch(RuntimeException error){
            String code=error instanceof DomainException d?d.code():"QUICK_CREATION_INTERRUPTED";
            if(code.equals("LYRICS_REQUEST_PENDING"))repository.checkpoint(entry.id(),token,"WRITING",revision,null,null,5);
            else if(Set.of("GLOBAL_GENERATION_BUSY","PERSONAL_GENERATION_BUSY").contains(code))
                repository.checkpoint(entry.id(),token,revision==null?"QUEUED":"LYRICS_READY",revision,null,null,5);
            else repository.checkpoint(entry.id(),token,"FAILED",revision,null,code,0);
            LOG.log(System.Logger.Level.WARNING,"Quick creation stopped: id={0}, code={1}",entry.id(),code);
        }finally{repository.release(entry.id(),token);}
        return true;
    }
    private void checkpoint(QuickCreationRepository.Entry entry,String token,String status,String revision,String task,String error,int delay){
        if(!repository.checkpoint(entry.id(),token,status,revision,task,error,delay))throw new LeaseLost();
    }
    private static final class LeaseLost extends RuntimeException{}
}
