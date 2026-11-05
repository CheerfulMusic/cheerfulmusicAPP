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
    @Select("SELECT id FROM creator WHERE id=#{id} FOR UPDATE") String lockCreator(String id);
    @Select("SELECT creator_id FROM creator_google_identity WHERE subject_hash=#{hash}") String googleCreator(String hash);
    @Select("SELECT subject_hash,email,display_name AS name FROM creator_google_identity WHERE creator_id=#{id}") com.qingfeng.music.identity.api.IdentityApi.GoogleIdentity googleIdentity(String id);
    @Insert("INSERT INTO creator_google_identity(subject_hash,creator_id,email,display_name,created_at,updated_at) VALUES(#{hash},#{id},#{email},#{name},#{now},#{now})")
    void bindGoogle(@Param("id")String id,@Param("hash")String hash,@Param("email")String email,@Param("name")String name,@Param("now")Instant now);
    @Update("UPDATE creator_google_identity SET email=#{email},display_name=#{name},updated_at=#{now} WHERE subject_hash=#{hash}")
    void updateGoogle(@Param("hash")String hash,@Param("email")String email,@Param("name")String name,@Param("now")Instant now);
    @Select("SELECT (SELECT COUNT(*) FROM music_work WHERE owner_id=#{id}) + (SELECT COUNT(*) FROM model_usage_reservation WHERE owner_id=#{id}) + (SELECT COUNT(*) FROM credit_ledger WHERE owner_id=#{id}) + (SELECT COUNT(*) FROM payment_order WHERE creator_id=#{id})") long contentCount(String id);
    @Select("SELECT (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=#{id}) + (SELECT COUNT(*) FROM creator_authing_identity WHERE creator_id=#{id})") long otherIdentityCount(String id);
    @Delete("DELETE FROM creator_session WHERE token_hash=#{hash}") void deleteSession(String hash);
}
