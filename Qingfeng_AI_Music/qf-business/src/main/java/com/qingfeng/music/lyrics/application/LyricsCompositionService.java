package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.UUID;

/** 外部请求不占用数据库事务；请求键先持久化，超时或响应丢失时禁止相同请求再次扣费。 */
public final class LyricsCompositionService {
    private final WorkApi works;private final LyricsApi lyrics;private final LyricsRepository revisions;
    private final LyricsWriter writer;private final CompositionRequests requests;private final boolean enabled;
    public LyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsRepository revisions,LyricsWriter writer,CompositionRequests requests,boolean enabled){this.works=works;this.lyrics=lyrics;this.revisions=revisions;this.writer=writer;this.requests=requests;this.enabled=enabled;}
    public LyricsApi.Revision compose(String owner,String work,long expectedVersion,String key,String language){
        var snapshot=works.get(owner,work);
        if(key==null||!key.matches("[0-9a-fA-F-]{36}")||!java.util.Set.of("zh-CN","en").contains(language))throw new DomainException("INVALID_LYRICS_REQUEST","歌词生成参数无效，请刷新后重试");
        var prior=requests.find(work,key);
        if(prior.isPresent()){
            if(prior.get().status().equals("SUCCEEDED"))return lyrics.get(owner,work,prior.get().revisionId());
            throw new DomainException("LYRICS_REQUEST_PENDING","这次歌词请求已处理或仍在确认中，请先刷新歌词版本，避免重复提交");
        }
        if(snapshot.version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
        if(!enabled||!writer.isConfigured())throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词生成暂不可用，请稍后再试或手动填写");
        if(snapshot.story().isBlank()&&snapshot.message().isBlank())throw new DomainException("INVALID_LYRICS_REQUEST","请先在故事中填写一段回忆或祝福，再生成歌词");
        if(!requests.claim(work,key))throw new DomainException("LYRICS_REQUEST_PENDING","这次歌词请求已处理或仍在确认中，请先刷新歌词版本，避免重复提交");
        try{
            var draft=writer.write(snapshot,language);
            if(works.get(owner,work).version()!=expectedVersion)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
            if(draft.title()==null||draft.title().isBlank()||draft.title().length()>80||draft.lyrics()==null||draft.lyrics().isBlank()||draft.lyrics().length()>5000)throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");
            var revision=new LyricsApi.Revision(UUID.randomUUID().toString(),work,expectedVersion,draft.title().trim(),draft.lyrics(),"DEEPSEEK",false,Instant.now());
            revisions.insert(revision);requests.complete(work,key,revision.id());return lyrics.get(owner,work,revision.id());
        }catch(RuntimeException error){requests.failed(work,key);throw error;}
    }
}
