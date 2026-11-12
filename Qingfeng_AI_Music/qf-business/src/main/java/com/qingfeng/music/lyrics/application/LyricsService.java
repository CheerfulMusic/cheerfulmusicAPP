package com.qingfeng.music.lyrics.application;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class LyricsService implements LyricsApi {
    private final WorkApi works; private final LyricsRepository repository;
    public LyricsService(WorkApi works,LyricsRepository repository){this.works=works;this.repository=repository;}
    public Revision save(String owner,String work,long version,String title,String lyrics){
        return save(owner,work,version,title,lyrics,null);
    }
    @Override
    public Revision save(String owner,String work,long version,String title,String lyrics,String baseRevisionId){
        var current=works.get(owner,work);
        if(current.version()!=version)throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新载入后保存歌词");
        if(title==null||title.isBlank()||title.length()>100||lyrics==null||lyrics.isBlank()||lyrics.length()>12000)throw new DomainException("INVALID_LYRICS","请填写标题和歌词，并检查长度");
        var base=baseRevisionId==null?null:get(owner,work,baseRevisionId);
        var revision=new Revision(UUID.randomUUID().toString(),work,version,title.trim(),lyrics,"MANUAL",false,Instant.now(),
            base==null?null:base.language(),base==null?null:base.templateId(),base==null?null:base.chorusFirst(),
            base==null?null:base.customSections());
        repository.insert(revision);return get(owner,work,revision.id());
    }
    @Transactional
    public Revision confirm(String owner,String work,String id){
        var revision=get(owner,work,id);
        if(revision.confirmed())return revision;
        if(works.get(owner,work).version()!=revision.workVersion())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请为当前作品保存新版歌词后确认");
        repository.confirm(work,id);return get(owner,work,id);
    }
    public Revision get(String owner,String work,String id){works.get(owner,work);return repository.find(work,id).orElseThrow(()->new DomainException("LYRICS_NOT_FOUND","歌词版本不存在"));}
    public List<Revision> list(String owner,String work){works.get(owner,work);return repository.list(work);}
}
