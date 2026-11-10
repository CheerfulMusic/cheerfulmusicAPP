package com.qingfeng.music.infrastructure.persistence.lyrics;
import com.qingfeng.music.lyrics.application.LyricsRepository;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import java.util.*;
public class MyBatisLyricsRepository implements LyricsRepository {
 private final LyricsMapper mapper;
 public MyBatisLyricsRepository(LyricsMapper mapper){this.mapper=mapper;}
 public void insert(Revision r){var row=new LyricsRow();row.id=r.id();row.workId=r.workId();row.workVersion=r.workVersion();row.title=r.title();row.lyrics=r.lyrics();row.source=r.source();row.confirmed=r.confirmed();row.createdAt=r.createdAt();row.language=r.language();row.templateId=r.templateId();row.chorusFirst=r.chorusFirst();mapper.insert(row);}
 private Revision revision(LyricsRow r){return new Revision(r.id,r.workId,r.workVersion,r.title,r.lyrics,r.source,r.confirmed,r.createdAt,r.language,r.templateId,r.chorusFirst);}
 public Optional<Revision> find(String work,String id){return Optional.ofNullable(mapper.find(work,id)).map(this::revision);}
 public List<Revision> list(String work){return mapper.list(work).stream().map(this::revision).toList();}
 public void confirm(String work,String id){mapper.confirm(work,id);}
}
