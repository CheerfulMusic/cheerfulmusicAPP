package com.qingfeng.music.infrastructure.persistence.generation;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface TaskMapper {
 @Insert("INSERT INTO generation_task(id,work_id,revision_id,work_version,request_key,title,lyrics,style,provider,status,created_at,next_attempt_at,updated_at,vocal_gender) VALUES(#{id},#{workId},#{revisionId},#{workVersion},#{requestKey},#{title},#{lyrics},#{style},#{provider},#{status},#{createdAt},#{nextAttemptAt},#{createdAt},#{vocalGender}) ON DUPLICATE KEY UPDATE request_key=request_key") void insertOrIgnore(TaskRow row);
 @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
 @Select("SELECT * FROM generation_task WHERE work_id=#{work} AND request_key=#{requestKey}")TaskRow find(@Param("work")String work,@Param("requestKey")String requestKey);
 @Select("SELECT * FROM generation_task WHERE work_id=#{work} ORDER BY created_at DESC,id DESC LIMIT 50")List<TaskRow> list(@Param("work")String work);
 @Update("UPDATE generation_task SET status='CANCELLED',updated_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL WHERE work_id=#{work} AND id=#{task} AND status='QUEUED' AND provider_task_id IS NULL AND submit_attempts=0")
 int cancel(@Param("work")String work,@Param("task")String task);
}
