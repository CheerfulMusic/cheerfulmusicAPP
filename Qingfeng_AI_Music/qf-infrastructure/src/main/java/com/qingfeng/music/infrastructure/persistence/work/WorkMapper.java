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

    // 删除作品时按外键依赖顺序清理关联数据：先删子表，再删父表，避免违反外键约束。
    @Delete("DELETE FROM section_edit WHERE task_id IN (SELECT id FROM generation_task WHERE work_id=#{id})") void deleteSectionEdits(String id);
    @Delete("DELETE FROM generation_audio_source WHERE task_id IN (SELECT id FROM generation_task WHERE work_id=#{id})") void deleteGenerationAudioSources(String id);
    @Delete("DELETE FROM generation_music_preferences WHERE task_id IN (SELECT id FROM generation_task WHERE work_id=#{id})") void deleteGenerationMusicPreferences(String id);
    @Delete("DELETE FROM generation_audio_candidates WHERE task_id IN (SELECT id FROM generation_task WHERE work_id=#{id})") void deleteGenerationAudioCandidates(String id);
    @Delete("DELETE FROM work_audio_selection WHERE work_id=#{id}") void deleteAudioSelection(String id);
    @Delete("DELETE FROM share_link WHERE work_id=#{id}") void deleteShares(String id);
    @Delete("DELETE FROM media_lyrics_timeline WHERE work_id=#{id}") void deleteLyricsTimelines(String id);
    @Delete("DELETE FROM music_listening_feedback WHERE asset_id IN (SELECT id FROM media_asset WHERE work_id=#{id})") void deleteListeningFeedback(String id);
    @Delete("DELETE FROM cover_edit_task WHERE work_id=#{id}") void deleteCoverEditTasks(String id);
    @Delete("DELETE FROM work_cover WHERE work_id=#{id}") void deleteWorkCover(String id);
    @Delete("DELETE FROM lyric_creative_plan WHERE work_id=#{id}") void deleteLyricCreativePlans(String id);
    @Delete("DELETE FROM lyric_revision WHERE work_id=#{id}") void deleteLyricRevisions(String id);
    @Delete("DELETE FROM generation_task WHERE work_id=#{id}") void deleteGenerationTasks(String id);
    @Delete("DELETE FROM media_asset WHERE work_id=#{id}") void deleteMediaAssets(String id);
    @Delete("DELETE FROM cover_asset WHERE work_id=#{id}") void deleteCoverAssets(String id);
    @Delete("DELETE FROM model_usage_reservation WHERE work_id=#{id}") void deleteModelUsageReservations(String id);
    @Delete("DELETE FROM lyrics_composition_request WHERE work_id=#{id}") void deleteLyricsCompositionRequests(String id);
    @Delete("DELETE FROM work_music_preferences WHERE work_id=#{id}") void deleteWorkMusicPreferences(String id);
    @Delete("DELETE FROM music_work WHERE owner_id=#{owner} AND id=#{id}") void deleteWork(@Param("owner") String owner,@Param("id") String id);
}
