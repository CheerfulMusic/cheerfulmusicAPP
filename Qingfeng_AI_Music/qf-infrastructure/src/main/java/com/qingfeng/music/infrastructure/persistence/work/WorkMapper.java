package com.qingfeng.music.infrastructure.persistence.work;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface WorkMapper {
    @Select("SELECT id FROM creator WHERE id=#{id} FOR UPDATE") String lockOwner(String id);
    @Select("SELECT token_hash FROM creator_session WHERE creator_id=#{id} AND expires_at>CURRENT_TIMESTAMP FOR UPDATE") List<String> activeSessions(String id);
    @Insert("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at,vocal_gender) VALUES(#{id},#{ownerId},#{recipient},#{occasion},#{story},#{message},#{style},#{title},#{version},#{createdAt},#{updatedAt},#{vocalGender}) ON DUPLICATE KEY UPDATE id=id") void insert(WorkRow row);
    @Update("UPDATE music_work SET recipient=#{row.recipient},occasion=#{row.occasion},story=#{row.story},message=#{row.message},style=#{row.style},title=#{row.title},version=#{row.version},updated_at=#{row.updatedAt},vocal_gender=#{row.vocalGender} WHERE id=#{row.id} AND owner_id=#{row.ownerId} AND version=#{expected}") int update(@Param("row") WorkRow row,@Param("expected") long expected);
    @Select("SELECT * FROM music_work WHERE owner_id=#{owner} AND id=#{id}") WorkRow find(@Param("owner") String owner,@Param("id") String id);
    @Select("SELECT * FROM music_work WHERE owner_id=#{owner} ORDER BY updated_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}") List<WorkRow> list(@Param("owner") String owner,@Param("offset") int offset,@Param("limit") int limit);
}
