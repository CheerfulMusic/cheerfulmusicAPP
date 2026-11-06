package com.qingfeng.music.infrastructure.persistence.media;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface MediaMapper {
    @Select("SELECT g.revision_id FROM media_asset m JOIN generation_task g ON g.id=m.generation_task_id WHERE m.work_id=#{work} AND m.id=#{asset}")String revision(@Param("work")String work,@Param("asset")String asset);
    @Insert("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at) VALUES(#{id},#{workId},#{storageKey},#{origin},#{contentType},#{byteSize},#{sha256},#{createdAt})") void insert(MediaRow row);
    @Select("SELECT m.*,EXISTS(SELECT 1 FROM section_edit e WHERE e.task_id=m.generation_task_id) AS section_edit FROM media_asset m WHERE m.work_id=#{work} AND m.id=#{id}") MediaRow find(@Param("work") String work,@Param("id") String id);
    @Select("SELECT m.*,EXISTS(SELECT 1 FROM section_edit e WHERE e.task_id=m.generation_task_id) AS section_edit FROM media_asset m WHERE m.work_id=#{work} ORDER BY m.created_at DESC,m.id DESC LIMIT 100") List<MediaRow> list(@Param("work") String work);
    @Select("SELECT asset_id,version FROM work_audio_selection WHERE work_id=#{work}") java.util.Map<String,Object> selection(String work);
    @Insert("INSERT INTO work_audio_selection(work_id,asset_id,version) VALUES(#{work},#{asset},#{version}) ON DUPLICATE KEY UPDATE work_id=work_id") void ensureSelection(@Param("work")String work,@Param("asset")String asset,@Param("version")long version);
    @Update("UPDATE work_audio_selection SET asset_id=#{asset},version=version+1 WHERE work_id=#{work} AND version=#{version} AND NOT EXISTS(SELECT 1 FROM media_asset a JOIN section_edit e ON e.task_id=a.generation_task_id JOIN generation_task g ON g.id=e.task_id WHERE a.id=#{asset} AND g.status<>'SUCCEEDED')") int select(@Param("work")String work,@Param("asset")String asset,@Param("version")long version);
    @Update("UPDATE work_audio_selection SET asset_id=#{asset},version=version+1 WHERE work_id=#{work}") void automaticSelection(@Param("work")String work,@Param("asset")String asset);
    @Select("SELECT COUNT(*) FROM section_edit WHERE task_id=#{task}") int section(String task);
    @Select("SELECT * FROM media_asset WHERE generation_task_id=#{task} AND candidate_key='primary'") MediaRow generated(String task);
    @Select("SELECT * FROM media_asset WHERE generation_task_id=#{task} AND candidate_key=#{candidate}") MediaRow candidate(@Param("task")String task,@Param("candidate")String candidate);
    @Insert("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at,generation_task_id,candidate_key,provider_audio_id,source_audio_url,duration_s) VALUES(#{asset.id},#{asset.workId},#{asset.storageKey},#{asset.origin},#{asset.contentType},#{asset.byteSize},#{asset.sha256},#{asset.createdAt},#{task},#{asset.candidateKey},#{asset.providerAudioId},#{asset.sourceAudioUrl},#{asset.durationS}) ON DUPLICATE KEY UPDATE generation_task_id=generation_task_id")
    void insertGenerated(@Param("asset")MediaRow asset,@Param("task")String task);
    @Update("UPDATE creator SET media_bytes=media_bytes+#{bytes} WHERE id=(SELECT owner_id FROM music_work WHERE id=#{work}) AND media_bytes<=#{capacity}-#{bytes}")
    int reserve(@Param("work")String work,@Param("bytes")long bytes,@Param("capacity")long capacity);
    @Update("UPDATE creator SET media_bytes=media_bytes-#{bytes} WHERE id=(SELECT owner_id FROM music_work WHERE id=#{work}) AND media_bytes>=#{bytes}")
    void release(@Param("work")String work,@Param("bytes")long bytes);
}
