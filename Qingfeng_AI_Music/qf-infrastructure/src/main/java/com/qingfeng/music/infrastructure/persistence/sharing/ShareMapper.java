package com.qingfeng.music.infrastructure.persistence.sharing;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ShareMapper {
    @Insert("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at,cover_id) VALUES(#{id},#{workId},#{tokenHash},#{assetId},#{title},#{recipient},#{message},#{lyrics},#{audioOrigin},#{active},#{createdAt},#{expiresAt},#{coverId})") void insert(ShareRow row);
    @Select("SELECT * FROM share_link WHERE token_hash=#{hash} AND active=TRUE AND expires_at>#{now}") ShareRow active(@Param("hash") String hash,@Param("now") Instant now);
    @Update("UPDATE share_link SET active=FALSE WHERE work_id=#{work}") void revokeAll(@Param("work") String work);
}
