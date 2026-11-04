package com.qingfeng.music.infrastructure.persistence.cover;
import com.qingfeng.music.cover.api.CoverApi.*;
import com.qingfeng.music.cover.application.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCoverRepository implements CoverRepository {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 public JdbcCoverRepository(JdbcTemplate jdbc,TransactionTemplate tx){this.jdbc=jdbc;this.tx=tx;}
 private Asset asset(ResultSet r)throws SQLException{return new Asset(r.getString("id"),r.getString("origin"),r.getTimestamp("created_at").toInstant());}
 private Stored stored(ResultSet r,int n)throws SQLException{return new Stored(r.getString("work_id"),r.getString("storage_key"),r.getLong("byte_size"),asset(r));}
 private Job job(ResultSet r,int n)throws SQLException{return new Job(new Edit(r.getString("id"),r.getString("source_id"),r.getString("prompt"),r.getString("status"),r.getString("cover_id"),r.getString("error_code"),r.getTimestamp("created_at").toInstant()),r.getString("work_id"),r.getString("request_key"),r.getString("model"),r.getString("provider_task_id"),r.getString("result_url"),r.getString("lease_token"));}
 public List<Asset> list(String work){return jdbc.query("SELECT * FROM cover_asset WHERE work_id=? ORDER BY created_at DESC,id DESC LIMIT 100",(r,n)->asset(r),work);}
 public Optional<Stored> find(String work,String id){return jdbc.query("SELECT * FROM cover_asset WHERE work_id=? AND id=?",this::stored,work,id).stream().findFirst();}
 public Stored insert(String work,CoverFileStore.Stored file,String origin,String edit){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO cover_asset(id,work_id,storage_key,byte_size,origin,edit_task_id,created_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE edit_task_id=edit_task_id",id,work,file.key(),file.size(),origin,edit,Timestamp.from(Instant.now()));return edit==null?find(work,id).orElseThrow():jdbc.query("SELECT * FROM cover_asset WHERE edit_task_id=?",this::stored,edit).get(0);}
 public Selection selection(String work){return jdbc.query("SELECT cover_id,version FROM work_cover WHERE work_id=?",(r,n)->new Selection(r.getString(1),r.getLong(2)),work).stream().findFirst().orElse(new Selection(null,0));}
 public boolean select(String work,String id,long version){return Boolean.TRUE.equals(tx.execute(t->{jdbc.update("INSERT INTO work_cover(work_id,version) VALUES(?,0) ON DUPLICATE KEY UPDATE work_id=work_id",work);return jdbc.update("UPDATE work_cover SET cover_id=?,version=version+1 WHERE work_id=? AND version=?",id,work,version)==1;}));}
 public List<Edit> edits(String work){return jdbc.query("SELECT * FROM cover_edit_task WHERE work_id=? ORDER BY created_at DESC,id DESC LIMIT 20",this::job,work).stream().map(Job::edit).toList();}
 public Optional<Job> request(String work,String key){return jdbc.query("SELECT * FROM cover_edit_task WHERE work_id=? AND request_key=?",this::job,work,key).stream().findFirst();}
 public Job insertJob(String work,String source,String prompt,String key,String model){String id=UUID.randomUUID().toString();var now=Timestamp.from(Instant.now());jdbc.update("INSERT INTO cover_edit_task(id,work_id,source_id,prompt,request_key,model,status,created_at,updated_at,next_attempt_at) VALUES(?,?,?,?,?,?,'QUEUED',?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",id,work,source,prompt,key,model,now,now,now);return request(work,key).orElseThrow();}
 public Optional<Job> claim(){return tx.execute(t->{
  var now=Timestamp.from(Instant.now());var jobs=jdbc.query("SELECT * FROM cover_edit_task WHERE status IN ('QUEUED','SUBMITTING','RUNNING','SAVING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE",this::job,now,now);
  if(jobs.isEmpty())return Optional.empty();var job=jobs.get(0);String token=UUID.randomUUID().toString();
  jdbc.update("UPDATE cover_edit_task SET status=?,lease_token=?,lease_until=?,updated_at=? WHERE id=?",job.edit().status().equals("QUEUED")?"SUBMITTING":job.edit().status(),token,Timestamp.from(Instant.now().plusSeconds(180)),now,job.edit().id());
  return Optional.of(new Job(job.edit(),job.work(),job.requestKey(),job.model(),job.externalId(),job.resultUrl(),token));
 });}
 public void accepted(Job job,String external){jdbc.update("UPDATE cover_edit_task SET status='RUNNING',provider_task_id=?,updated_at=?,next_attempt_at=? WHERE id=? AND provider_task_id IS NULL AND status IN ('SUBMITTING','SUBMISSION_UNKNOWN')",external,Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(10)),job.edit().id());}
 public void result(Job job,String state,String url,String cover,String error){jdbc.update("UPDATE cover_edit_task SET status=?,result_url=COALESCE(?,result_url),cover_id=COALESCE(?,cover_id),error_code=?,updated_at=?,next_attempt_at=? WHERE id=? AND lease_token=? AND status IN ('SUBMITTING','RUNNING','SAVING')",state,url,cover,error,Timestamp.from(Instant.now()),Timestamp.from(Instant.now().plusSeconds(10)),job.edit().id(),job.token());}
 public void release(Job job){jdbc.update("UPDATE cover_edit_task SET lease_token=NULL,lease_until=NULL,next_attempt_at=CASE WHEN next_attempt_at<CURRENT_TIMESTAMP THEN ? ELSE next_attempt_at END WHERE id=? AND lease_token=?",Timestamp.from(Instant.now().plusSeconds(30)),job.edit().id(),job.token());}
}
