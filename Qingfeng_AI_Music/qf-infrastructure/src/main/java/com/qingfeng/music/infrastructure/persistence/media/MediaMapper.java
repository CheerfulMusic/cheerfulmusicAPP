package com.qingfeng.music.infrastructure.persistence.media;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface MediaMapper {
    @Insert("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at) VALUES(#{id},#{workId},#{storageKey},#{origin},#{contentType},#{byteSize},#{sha256},#{createdAt})") void insert(MediaRow row);
    @Select("SELECT * FROM media_asset WHERE work_id=#{work} AND id=#{id}") MediaRow find(@Param("work") String work,@Param("id") String id);
    @Select("SELECT * FROM media_asset WHERE work_id=#{work} ORDER BY created_at DESC,id DESC LIMIT 100") List<MediaRow> list(@Param("work") String work);
    @Select("SELECT * FROM media_asset WHERE generation_task_id=#{task}") MediaRow generated(String task);
    @Insert("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at,generation_task_id) VALUES(#{asset.id},#{asset.workId},#{asset.storageKey},#{asset.origin},#{asset.contentType},#{asset.byteSize},#{asset.sha256},#{asset.createdAt},#{task}) ON DUPLICATE KEY UPDATE generation_task_id=generation_task_id")
    void insertGenerated(@Param("asset")MediaRow asset,@Param("task")String task);
    @Update("UPDATE creator SET media_bytes=media_bytes+#{bytes} WHERE id=(SELECT owner_id FROM music_work WHERE id=#{work}) AND media_bytes<=#{capacity}-#{bytes}")
    int reserve(@Param("work")String work,@Param("bytes")long bytes,@Param("capacity")long capacity);
    @Update("UPDATE creator SET media_bytes=media_bytes-#{bytes} WHERE id=(SELECT owner_id FROM music_work WHERE id=#{work}) AND media_bytes>=#{bytes}")
    void release(@Param("work")String work,@Param("bytes")long bytes);
}
