package com.qingfeng.music.infrastructure.persistence.lyrics;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.LyricsRepository;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import java.sql.SQLException;
import java.util.*;
import org.springframework.dao.DataAccessException;
public class MyBatisLyricsRepository implements LyricsRepository {
 private final LyricsMapper mapper;
 public MyBatisLyricsRepository(LyricsMapper mapper){this.mapper=mapper;}
 @Override
 public void requireCustomSectionsStorage(){
     try {
         mapper.probeCustomSectionsColumn();
     } catch (DataAccessException error) {
         if (error.getMostSpecificCause() instanceof SQLException sql && "42S22".equals(sql.getSQLState())) {
             throw new DomainException("LYRICS_CUSTOM_STRUCTURE_UNAVAILABLE", "当前环境暂未启用自定义结构生成，请选择预设模板或由 AI 安排。");
         }
         throw error;
     }
 }
 public void insert(Revision revision){
     var row=new LyricsRow();
     row.id=revision.id();
     row.workId=revision.workId();
     row.workVersion=revision.workVersion();
     row.title=revision.title();
     row.lyrics=revision.lyrics();
     row.source=revision.source();
     row.confirmed=revision.confirmed();
     row.createdAt=revision.createdAt();
     row.language=revision.language();
     row.templateId=revision.templateId();
     row.chorusFirst=revision.chorusFirst();
     row.customSections=revision.customSections()==null?null:String.join(",",revision.customSections());
     mapper.insert(row);
 }
 private Revision revision(LyricsRow row){
     var sections=row.customSections==null?null:List.of(row.customSections.split(","));
     return new Revision(row.id,row.workId,row.workVersion,row.title,row.lyrics,row.source,row.confirmed,row.createdAt,
         row.language,row.templateId,row.chorusFirst,sections);
 }
 public Optional<Revision> find(String work,String id){return Optional.ofNullable(mapper.find(work,id)).map(this::revision);}
 public List<Revision> list(String work){return mapper.list(work).stream().map(this::revision).toList();}
 public void confirm(String work,String id){mapper.confirm(work,id);}
}
