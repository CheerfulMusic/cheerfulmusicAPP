package com.qingfeng.music.infrastructure.persistence.sharing;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ShareMapper {
    @Insert("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at,cover_id) VALUES(#{id},#{workId},#{tokenHash},#{assetId},#{title},#{recipient},#{message},#{lyrics},#{audioOrigin},#{active},#{createdAt},#{expiresAt},#{coverId})") void insert(ShareRow row);
    @Select("SELECT s.* FROM share_link s JOIN music_work w ON w.id=s.work_id WHERE s.token_hash=#{hash} AND s.active=TRUE AND s.expires_at>#{now} AND w.deleted_at IS NULL") ShareRow active(@Param("hash") String hash,@Param("now") Instant now);
    @Update("UPDATE share_link SET active=FALSE WHERE work_id=#{work}") void revokeAll(@Param("work") String work);
}
