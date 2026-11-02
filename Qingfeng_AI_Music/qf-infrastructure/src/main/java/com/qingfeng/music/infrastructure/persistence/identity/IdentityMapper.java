package com.qingfeng.music.infrastructure.persistence.identity;

import org.apache.ibatis.annotations.*;
import java.time.Instant;

@Mapper
public interface IdentityMapper {
    @Insert("INSERT INTO creator(id,created_at) VALUES(#{id},#{now})") void insertCreator(@Param("id") String id, @Param("now") Instant now);
    @Insert("INSERT INTO creator_session(token_hash,creator_id,expires_at) VALUES(#{hash},#{id},#{expires})") void insertSession(@Param("hash") String hash, @Param("id") String id, @Param("expires") Instant expires);
    @Select("SELECT creator_id FROM creator_session WHERE token_hash=#{hash} AND expires_at>#{now}") String find(@Param("hash") String hash, @Param("now") Instant now);
    @Update("UPDATE creator SET recovery_hash=#{hash} WHERE id=#{id}") void recovery(@Param("id")String id,@Param("hash")String hash);
    @Select("SELECT COUNT(*) FROM creator WHERE id=#{id} AND recovery_hash IS NOT NULL") int hasRecovery(String id);
    @Select("SELECT id FROM creator WHERE recovery_hash=#{hash} FOR UPDATE") String lockRecovery(String hash);
}
