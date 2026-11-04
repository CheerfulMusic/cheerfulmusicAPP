package com.qingfeng.music.generation.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.usage.UsageBudget;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class SectionEditingService {
    private final WorkApi works;private final LyricsApi lyrics;private final MediaApi media;private final MusicProvider provider;
    private final TaskRepository tasks;private final SectionEditRepository edits;private final UsageBudget budget;private final TransactionTemplate transactions;private final boolean enabled;
    private com.qingfeng.music.settings.MusicSettings settings;
    public SectionEditingService withSettings(com.qingfeng.music.settings.MusicSettings value){settings=value;return this;}
    private com.qingfeng.music.lyrics.playback.PlaybackLyricsService playback;
    public SectionEditingService withPlayback(com.qingfeng.music.lyrics.playback.PlaybackLyricsService value){playback=value;return this;}
    public SectionEditingService(WorkApi works,LyricsApi lyrics,MediaApi media,MusicProvider provider,TaskRepository tasks,SectionEditRepository edits,UsageBudget budget,TransactionTemplate transactions,boolean enabled){this.works=works;this.lyrics=lyrics;this.media=media;this.provider=provider;this.tasks=tasks;this.edits=edits;this.budget=budget;this.transactions=transactions;this.enabled=enabled;}
    public record Context(boolean available,String reason,String title,String lyrics,Double duration,double minimumSeconds,double maximumSeconds,List<com.qingfeng.music.lyrics.playback.PlaybackLyrics.Line> lines){}
    public record Request(String sourceAssetId,long workVersion,String requestKey,double start,double end,int textStart,int textEnd,String replacementLyrics){}
    public Context context(String owner,String work,String asset){
        works.get(owner,work);var audio=media.get(owner,work,asset);
        if(!audio.origin().equals("GENERATED"))return unavailable("局部改词目前仅支持本站生成的歌曲。");
        if(!enabled||!provider.isConfigured()||!provider.supportsSectionEditing())return unavailable("局部改词服务暂不可用，请稍后再试。");
        var original=edits.source(work,asset).orElse(null);
        if(original==null||!provider.name().equals(original.provider())||original.providerTaskId()==null)return unavailable("这首歌曲缺少可编辑的原曲信息。");
        if(original.audioId()==null||original.duration()==null){
            try{var info=provider.sourceInfo(original.providerTaskId(),original.audioUrl());if(info!=null)edits.recordAudio(original.taskId(),info);original=edits.source(work,asset).orElse(null);}
            catch(RuntimeException ignored){return unavailable("原曲信息暂时读取失败，请重试；不会重新生成歌曲。");}
        }
        if(original==null||original.audioId()==null||original.duration()==null||!Double.isFinite(original.duration())||original.duration()<20)return unavailable("这首歌曲暂时无法提供至少10秒的修改片段。");
        List<com.qingfeng.music.lyrics.playback.PlaybackLyrics.Line> lines=List.of();
        if(playback!=null)try{lines=playback.get(owner,work,asset).lines();}catch(RuntimeException ignored){/* 无时间轴时由用户试听定位，不触发付费同步。 */}
        return new Context(true,null,original.title(),original.lyrics(),original.duration(),10,Math.floor(original.duration()*50)/100,lines);
    }
    private Context unavailable(String reason){return new Context(false,reason,null,null,null,10,0,List.of());}
    public List<SectionEditRepository.Item> list(String owner,String work){works.get(owner,work);return edits.list(work);}
    /** 只在明确提交时创建新歌词和新任务；事务内不调用供应商。 */
    public GenerationApi.Task submit(String owner,String work,Request request){
        return transactions.execute(tx->{
            var current=works.get(owner,work);
            if(request==null||request.requestKey()==null||!request.requestKey().matches("[0-9a-fA-F-]{36}")||request.sourceAssetId()==null)throw invalid();
            String replacement=request.replacementLyrics()==null?"":request.replacementLyrics().trim();
            if(replacement.isBlank()||replacement.length()>5000||!seconds(request.start())||!seconds(request.end()))throw invalid();
            String hash=hash(request.sourceAssetId()+"\n"+request.workVersion()+"\n"+request.start()+"\n"+request.end()+"\n"+request.textStart()+"\n"+request.textEnd()+"\n"+replacement);
            var existing=tasks.findRequest(work,request.requestKey());
            if(existing.isPresent()){
                var prior=edits.edit(existing.get().id());
                if(prior.isEmpty()||!prior.get().requestHash().equals(hash))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于其他修改，请刷新任务状态。");
                return existing.get();
            }
            if(current.version()!=request.workVersion())throw new DomainException("WORK_VERSION_CONFLICT","故事已更新，请重新打开局部改词。");
            if(!enabled||!provider.isConfigured()||!provider.supportsSectionEditing())throw new DomainException("MUSIC_PROVIDER_UNAVAILABLE","局部改词服务暂不可用，请稍后再试。");
            media.get(owner,work,request.sourceAssetId());
            var source=edits.source(work,request.sourceAssetId()).orElseThrow(()->new DomainException("SECTION_SOURCE_UNAVAILABLE","这首歌曲缺少可编辑的原曲信息。"));
            if(!provider.name().equals(source.provider())||source.providerTaskId()==null||source.audioId()==null||source.duration()==null||!Double.isFinite(source.duration()))throw new DomainException("SECTION_SOURCE_UNAVAILABLE","请先重新读取原曲信息，再提交修改。");
            if(request.start()<0||request.end()>source.duration()||request.end()-request.start()<10-1e-8||request.end()-request.start()>source.duration()/2+1e-8)throw new DomainException("INVALID_SECTION_RANGE","修改片段至少10秒，且不能超过原曲时长的一半。");
            String original=source.lyrics();int from=request.textStart(),to=request.textEnd();
            if(from<0||to<=from||to>original.length()||(from>0&&original.charAt(from-1)!='\n')||(to<original.length()&&original.charAt(to)!='\n'&&original.charAt(to)!='\r'))throw invalid();
            if(original.substring(from,to).trim().equals(replacement))throw new DomainException("INVALID_SECTION_LYRICS","请先修改选中的歌词，再生成修改版。");
            String full=original.substring(0,from)+replacement+original.substring(to);
            if(full.length()>5000)throw new DomainException("INVALID_SECTION_LYRICS","修改后的完整歌词不能超过5000字，请缩短后重试。");
            var direction=settings==null?null:settings.frozen(work,source.taskId()).orElse(null);
            provider.validate(direction==null?new MusicProvider.Request(source.title(),full,source.style(),source.vocalGender()):new MusicProvider.Request(source.title(),full,direction.style(),source.vocalGender(),direction.negativeTags(),true));
            budget.reserve(owner,work,request.requestKey(),UsageBudget.Kind.MUSIC);
            var replay=tasks.findRequest(work,request.requestKey());
            if(replay.isPresent()){
                var prior=edits.edit(replay.get().id());if(prior.isEmpty()||!prior.get().requestHash().equals(hash))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于其他修改，请刷新任务状态。");
                return replay.get();
            }
            var revision=lyrics.save(owner,work,current.version(),source.title(),full);revision=lyrics.confirm(owner,work,revision.id());
            Instant now=Instant.now();String id=UUID.randomUUID().toString();
            var task=tasks.insertOrRead(new GenerationApi.Task(id,work,revision.id(),current.version(),request.requestKey(),source.title(),full,source.style(),provider.name(),GenerationStatus.QUEUED,now,null,null,now,now.plusSeconds(5),source.vocalGender()));
            // 相同请求的并发重放必须读取先前结果，事务与幂等键保证不重复计费。
            if(!task.id().equals(id)){
                var prior=edits.edit(task.id());if(prior.isEmpty()||!prior.get().requestHash().equals(hash))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于其他修改，请刷新任务状态。");
                throw new DomainException("SECTION_REQUEST_REPLAY","修改请求已受理，请刷新任务状态。");
            }
            edits.insert(new SectionEditRepository.Edit(id,request.sourceAssetId(),source.providerTaskId(),source.audioId(),request.start(),request.end(),source.duration(),from,to,replacement,hash));
            if(settings!=null)settings.snapshot(id,direction);
            media.keepOriginal(owner,work,request.sourceAssetId());
            return task;
        });
    }
    private boolean seconds(double value){return Double.isFinite(value)&&Math.abs(value*100-Math.rint(value*100))<1e-6;}
    private DomainException invalid(){return new DomainException("INVALID_SECTION_EDIT","请选择完整的歌词行并检查修改内容和时间范围。");}
    private String hash(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
