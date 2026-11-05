package com.qingfeng.music.infrastructure.persistence.generation;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface ExecutionMapper {
    @Insert("INSERT INTO generation_worker_lock(provider) VALUES(#{provider}) ON DUPLICATE KEY UPDATE provider=provider")
    void ensureProvider(String provider);
    @Update("UPDATE generation_worker_lock SET lease_token=#{token},lease_until=#{until} WHERE provider=#{provider} AND (lease_until IS NULL OR lease_until<=#{now})")
    int lock(@Param("provider")String provider,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
    @Update("UPDATE generation_worker_lock SET lease_token=NULL,lease_until=NULL WHERE provider=#{provider} AND lease_token=#{token}")
    void unlock(@Param("provider")String provider,@Param("token")String token);
    @Select("SELECT g.id FROM generation_task g WHERE g.provider=#{provider} AND (g.status IN ('QUEUED','SUBMITTING','RUNNING','SAVING') OR (g.status='SUBMISSION_UNKNOWN' AND g.provider_task_id IS NOT NULL)) AND g.next_attempt_at<=#{now} AND (g.lease_until IS NULL OR g.lease_until<=#{now}) AND (g.status<>'QUEUED' OR NOT EXISTS(SELECT 1 FROM generation_task busy WHERE busy.provider=#{provider} AND busy.status IN ('SUBMITTING','RUNNING','SAVING'))) ORDER BY g.next_attempt_at,g.id LIMIT 20")
    List<String> candidates(@Param("provider")String provider,@Param("now")Instant now);
    @Update("UPDATE generation_task SET lease_token=#{token},lease_until=#{until} WHERE id=#{id} AND (status IN ('QUEUED','SUBMITTING','RUNNING','SAVING') OR (status='SUBMISSION_UNKNOWN' AND provider_task_id IS NOT NULL)) AND next_attempt_at<=#{now} AND (lease_until IS NULL OR lease_until<=#{now})")
    int claim(@Param("id")String id,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
    @Select("SELECT * FROM generation_task WHERE id=#{id}") TaskRow find(String id);
    @Update("UPDATE generation_task SET lease_token=NULL,lease_until=NULL WHERE id=#{id} AND lease_token=#{token}")
    void release(@Param("id")String id,@Param("token")String token);
    @Update("UPDATE generation_worker_lock SET lease_until=#{until} WHERE provider=#{provider} AND lease_token=#{token} AND lease_until>#{now}")
    int renewProvider(@Param("provider")String provider,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
    @Update("UPDATE generation_task SET lease_until=#{until} WHERE id=#{id} AND lease_token=#{token} AND lease_until>#{now}")
    int renewTask(@Param("id")String id,@Param("token")String token,@Param("now")Instant now,@Param("until")Instant until);
    @Update("UPDATE generation_task SET status='SUBMITTING',submit_attempts=submit_attempts+1,updated_at=#{now} WHERE id=#{id} AND lease_token=#{token} AND status='QUEUED'")
    int start(@Param("id")String id,@Param("token")String token,@Param("now")Instant now);
    @Update("UPDATE generation_task SET status='RUNNING',provider_task_id=#{external},last_error_code=NULL,updated_at=#{now},next_attempt_at=#{next} WHERE id=#{id} AND status IN ('SUBMITTING','SUBMISSION_UNKNOWN') AND provider_task_id IS NULL")
    void accepted(@Param("id")String id,@Param("external")String external,@Param("now")Instant now,@Param("next")Instant next);
    @Update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN',last_error_code=#{code},updated_at=#{now} WHERE id=#{id} AND lease_token=#{token} AND status IN ('SUBMITTING','RUNNING')")
    int unknown(@Param("id")String id,@Param("token")String token,@Param("code")String code,@Param("now")Instant now);
    @Update("UPDATE generation_attempt SET outcome='UNKNOWN',error_code='WORKER_INTERRUPTED',ended_at=#{now} WHERE task_id=#{id} AND ended_at IS NULL")
    void interrupted(@Param("id")String id,@Param("now")Instant now);
    @Update("UPDATE generation_task SET status='RUNNING',query_failures=#{failures},last_error_code=#{code},updated_at=#{now},next_attempt_at=#{next} WHERE id=#{id} AND lease_token=#{token} AND status IN ('RUNNING','SUBMISSION_UNKNOWN')")
    void running(@Param("id")String id,@Param("token")String token,@Param("failures")int failures,@Param("code")String code,@Param("now")Instant now,@Param("next")Instant next);
    @Update("UPDATE generation_task SET status='SAVING',audio_url=#{url},last_error_code=NULL,updated_at=#{now},next_attempt_at=#{now} WHERE id=#{id} AND lease_token=#{token} AND status IN ('RUNNING','SUBMISSION_UNKNOWN')")
    void saving(@Param("id")String id,@Param("token")String token,@Param("url")String url,@Param("now")Instant now);
    @Update("UPDATE generation_task SET audio_url=#{url} WHERE id=#{id} AND lease_token=#{token} AND status='SAVING'")
    void refreshUrl(@Param("id")String id,@Param("token")String token,@Param("url")String url);
    @Update("UPDATE generation_task SET status=#{status},archive_attempts=#{attempts},last_error_code='AUDIO_ARCHIVE_FAILED',updated_at=#{now},next_attempt_at=#{next} WHERE id=#{id} AND lease_token=#{token} AND status='SAVING'")
    void archiveFailed(@Param("id")String id,@Param("token")String token,@Param("attempts")int attempts,@Param("status")String status,@Param("now")Instant now,@Param("next")Instant next);
    @Update("UPDATE generation_task SET status='SUCCEEDED',asset_id=#{asset},last_error_code=NULL,updated_at=#{now} WHERE id=#{id} AND lease_token=#{token} AND status='SAVING'")
    void succeeded(@Param("id")String id,@Param("token")String token,@Param("asset")String asset,@Param("now")Instant now);
    @Update("UPDATE generation_task SET status='FAILED',last_error_code=#{code},updated_at=#{now} WHERE id=#{id} AND lease_token=#{token} AND status IN ('SUBMITTING','RUNNING','SUBMISSION_UNKNOWN')")
    int failed(@Param("id")String id,@Param("token")String token,@Param("code")String code,@Param("now")Instant now);
    @Update("UPDATE generation_task SET status='SAVING',archive_attempts=0,last_error_code=NULL,next_attempt_at=#{now},updated_at=#{now} WHERE id=#{id} AND work_id=#{work} AND status='FAILED' AND last_error_code='AUDIO_ARCHIVE_FAILED' AND provider_task_id IS NOT NULL AND audio_url IS NOT NULL")
    int retry(@Param("work")String work,@Param("id")String id,@Param("now")Instant now);
    @Update("UPDATE generation_task SET status='FAILED',last_error_code='CHECKING_CLOSED',updated_at=#{now},lease_token=NULL,lease_until=NULL WHERE work_id=#{work} AND id=#{id} AND status='SUBMISSION_UNKNOWN' AND provider_task_id IS NULL AND updated_at<=#{cutoff} AND (lease_until IS NULL OR lease_until<=#{now})")
    int closeChecking(@Param("work")String work,@Param("id")String id,@Param("now")Instant now,@Param("cutoff")Instant cutoff);
    @Insert("INSERT INTO generation_attempt(id,task_id,phase,outcome,started_at) VALUES(#{id},#{task},#{phase},'STARTED',#{now})")
    void attempt(@Param("id")String id,@Param("task")String task,@Param("phase")String phase,@Param("now")Instant now);
    @Update("UPDATE generation_attempt SET outcome=#{outcome},error_code=#{code},ended_at=#{now} WHERE id=#{id}")
    void finish(@Param("id")String id,@Param("outcome")String outcome,@Param("code")String code,@Param("now")Instant now);
}
