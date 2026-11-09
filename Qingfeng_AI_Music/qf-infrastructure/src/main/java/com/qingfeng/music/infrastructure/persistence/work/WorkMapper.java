package com.qingfeng.music.infrastructure.persistence.work;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkMapper {
    @Select("SELECT id FROM creator WHERE id=#{id} FOR UPDATE") String lockOwner(String id);
    @Select("SELECT token_hash FROM creator_session WHERE creator_id=#{id} AND expires_at>CURRENT_TIMESTAMP FOR UPDATE") List<String> activeSessions(String id);
    @Insert("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at,vocal_gender) VALUES(#{id},#{ownerId},#{recipient},#{occasion},#{story},#{message},#{style},#{title},#{version},#{createdAt},#{updatedAt},#{vocalGender}) ON DUPLICATE KEY UPDATE id=id") void insert(WorkRow row);
    @Update("UPDATE music_work SET recipient=#{row.recipient},occasion=#{row.occasion},story=#{row.story},message=#{row.message},style=#{row.style},title=#{row.title},version=#{row.version},updated_at=#{row.updatedAt},vocal_gender=#{row.vocalGender} WHERE id=#{row.id} AND owner_id=#{row.ownerId} AND version=#{expected} AND deleted_at IS NULL") int update(@Param("row") WorkRow row,@Param("expected") long expected);
    @Select("SELECT * FROM music_work WHERE owner_id=#{owner} AND id=#{id} AND deleted_at IS NULL") WorkRow find(@Param("owner") String owner,@Param("id") String id);
    @Select("SELECT * FROM music_work WHERE owner_id=#{owner} AND deleted_at IS NULL ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}") List<WorkRow> list(@Param("owner") String owner,@Param("offset") int offset,@Param("limit") int limit);
    @Select("SELECT * FROM music_work WHERE owner_id=#{owner} AND id=#{id} FOR UPDATE") WorkRow lockDeletion(@Param("owner")String owner,@Param("id")String id);
    @Select("SELECT COUNT(*) FROM media_asset WHERE work_id=#{id}") int audioCount(String id);
    @Update("UPDATE music_work SET deleted_at=#{now},updated_at=#{now},version=version+1 WHERE owner_id=#{owner} AND id=#{id} AND deleted_at IS NULL") void delete(@Param("owner")String owner,@Param("id")String id,@Param("now")java.time.Instant now);
}
