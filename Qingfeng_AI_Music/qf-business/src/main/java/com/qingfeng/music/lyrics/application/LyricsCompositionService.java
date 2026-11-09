package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.UUID;

/** 外部请求不占用数据库事务；失败请求保留幂等键和额度，只有用户明确新建请求才再次调用模型。 */
public final class LyricsCompositionService {
    private static final System.Logger LOG=System.getLogger(LyricsCompositionService.class.getName());
    private final WorkApi works;private final LyricsApi lyrics;private final LyricsRepository revisions;
    private final LyricsWriter writer;private final CompositionRequests requests;private final boolean enabled;
    private com.qingfeng.music.settings.MusicSettings settings;
    private com.qingfeng.music.settings.CreativePlan.Repository plans;
    public LyricsCompositionService withCreativePlans(com.qingfeng.music.settings.MusicSettings settings,com.qingfeng.music.settings.CreativePlan.Repository plans){this.settings=settings;this.plans=plans;return this;}
    public LyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsRepository revisions,LyricsWriter writer,CompositionRequests requests,boolean enabled){this.works=works;this.lyrics=lyrics;this.revisions=revisions;this.writer=writer;this.requests=requests;this.enabled=enabled;}
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language){
        return compose(owner,work,expectedVersion,key,language,"",null);
    }
    public record RequestState(String status,String revisionId,String errorCode){}
    public RequestState requestState(String owner,String work,String key){
        works.get(owner,work);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_LYRICS_REQUEST","歌词生成参数无效，请刷新后重试");
        return requests.find(work,key).map(e->new RequestState(e.status(),e.revisionId(),e.errorCode())).orElseGet(()->new RequestState("NOT_FOUND",null,null));
    }
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language,String instructions,String baseRevisionId){
        return compose(owner,work,expectedVersion,key,language,instructions,baseRevisionId,null,false);
    }
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language,String instructions,String baseRevisionId,String templateId,boolean chorusFirst){
        var structure=new LyricsStructure(templateId,chorusFirst);
        var snapshot=works.get(owner,work);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}")||language==null||!java.util.Set.of("zh-CN","en","mixed").contains(language))throw new DomainException("INVALID_LYRICS_REQUEST","歌词生成参数无效，请刷新后重试");
        String requirements=instructions==null?"":instructions.trim();
        if(requirements.length()>1000)throw new DomainException("INVALID_LYRICS_REQUEST","修改要求不能超过1000字");
        String hash=fingerprint(expectedVersion+"\n"+language+"\n"+requirements+"\n"+(baseRevisionId==null?"":baseRevisionId)+structure.instruction());
        var prior=requests.find(work,key);
        if(prior.isPresent()){
            if(prior.get().requestHash()!=null&&!prior.get().requestHash().equals(hash))throw new DomainException("REQUEST_KEY_CONFLICT","该请求标识已用于其他创作要求，请刷新版本列表后重试");
            if(prior.get().status().equals("SUCCEEDED"))return lyrics.get(owner,work,prior.get().revisionId());
            if(prior.get().status().equals("FAILED"))throw new DomainException("LYRICS_REQUEST_FAILED","上次歌词编写已结束，可以重新生成；已有内容仍会保留。");
            throw new DomainException("LYRICS_REQUEST_PENDING","这次歌词请求已处理或仍在确认中，请先刷新歌词版本，避免重复提交");
        }
        if(snapshot.version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
        if(!enabled||!writer.isConfigured())throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词生成暂不可用，请稍后再试或手动填写");
        if(snapshot.story().isBlank()&&snapshot.message().isBlank())throw new DomainException("INVALID_LYRICS_REQUEST","请先在故事中填写一段回忆或祝福，再生成歌词");
        String previous=baseRevisionId==null?"":lyrics.get(owner,work,baseRevisionId).lyrics();
        if(!requests.claim(owner,work,key,hash))throw new DomainException("LYRICS_REQUEST_PENDING","这次歌词请求已处理或仍在确认中，请先刷新歌词版本，避免重复提交");
        try{
            var direction=settings==null?null:settings.resolve(owner,snapshot);
            var draft=writer.write(snapshot,language,requirements+structure.instruction(),previous,direction==null?snapshot.style():direction.style());
            if(works.get(owner,work).version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
            if(draft.title()==null||draft.title().isBlank()||draft.title().length()>80||draft.lyrics()==null||draft.lyrics().isBlank()||draft.lyrics().length()>5000)throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");
            structure.validate(draft.lyrics());
            var revision=new LyricsApi.Revision(UUID.randomUUID().toString(),work,expectedVersion,draft.title().trim(),draft.lyrics(),writer.source(),false,Instant.now());
            requests.completeWithRevision(work,key,revision.id(),()->{
                revisions.insert(revision);
                if(plans!=null&&draft.plan()!=null)plans.save(work,revision.id(),com.qingfeng.music.settings.CreativePlan.sourceHash(snapshot),draft.plan());
            });return lyrics.get(owner,work,revision.id());
        }catch(RuntimeException error){
            String code=error instanceof DomainException domain?domain.code():"LYRICS_INTERNAL_ERROR";
            requests.failed(work,key,code);
            LOG.log(System.Logger.Level.WARNING,"Lyrics request failed: work={0}, request={1}, code={2}, type={3}",work,key,code,error.getClass().getSimpleName());
            throw error;
        }
    }
    private String fingerprint(String value){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
