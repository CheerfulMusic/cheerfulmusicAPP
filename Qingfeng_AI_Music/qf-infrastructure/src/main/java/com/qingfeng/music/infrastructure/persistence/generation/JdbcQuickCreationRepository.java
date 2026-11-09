package com.qingfeng.music.infrastructure.persistence.generation;

import com.qingfeng.music.creation.QuickCreationRepository;
import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcQuickCreationRepository implements QuickCreationRepository {
    private final JdbcTemplate jdbc;private final TransactionTemplate transactions;
    private static final String SELECT="SELECT q.*,w.owner_id FROM quick_creation q JOIN music_work w ON w.id=q.work_id AND w.deleted_at IS NULL ";
    public JdbcQuickCreationRepository(JdbcTemplate jdbc,TransactionTemplate transactions){this.jdbc=jdbc;this.transactions=transactions;}
    public Entry create(Entry entry){return transactions.execute(tx->{
        // 与额度准入及访客迁移保持相同顺序：创建者锁在作品锁之前。
        if(jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",String.class,entry.owner()).isEmpty())throw new DomainException("CREATOR_SESSION_CHANGED","登录身份已改变，请刷新后继续。");
        // 仅锁一个作品做去重准入；事务在模型调用前结束。
        var versions=jdbc.queryForList("SELECT version FROM music_work WHERE id=? AND owner_id=? AND deleted_at IS NULL FOR UPDATE",Long.class,entry.workId(),entry.owner());
        if(versions.isEmpty())throw new DomainException("WORK_NOT_FOUND","作品不存在或你无权访问");
        var prior=query("WHERE q.work_id=? AND q.request_key=?",entry.workId(),entry.requestKey());
        if(!prior.isEmpty()){
            var saved=prior.get(0);
            if(saved.workVersion()!=entry.workVersion()||!saved.language().equals(entry.language()))throw new DomainException("REQUEST_KEY_CONFLICT","请求标识已用于其他创作内容。");
            return saved;
        }
        if(!query("WHERE w.owner_id=? AND q.work_id<>? AND q.status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC') LIMIT 1",entry.owner(),entry.workId()).isEmpty())
            throw new DomainException("PERSONAL_GENERATION_BUSY","你已有一键创作正在处理，请先查看原作品。");
        if(versions.get(0)!=entry.workVersion())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请载入当前内容后继续。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM quick_creation WHERE work_id=? AND status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC')",Integer.class,entry.workId())>0||
           jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=? AND status IN ('QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN')",Integer.class,entry.workId())>0)
            throw new DomainException("QUICK_CREATION_PENDING","这份作品正在制作，请先查看原任务进度。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM generation_task t JOIN music_work w ON w.id=t.work_id WHERE w.owner_id=? AND w.deleted_at IS NULL AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN')",Integer.class,entry.owner())>0)
            throw new DomainException("PERSONAL_GENERATION_BUSY","你已有音乐正在制作，请先查看原作品。");
        jdbc.update("INSERT INTO quick_creation(id,work_id,work_version,request_key,language,status,lyrics_key,music_key,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            entry.id(),entry.workId(),entry.workVersion(),entry.requestKey(),entry.language(),entry.lyricsKey(),entry.musicKey());
        return find(entry.workId(),entry.id()).orElseThrow();
    });}
    public Optional<Entry> find(String work,String id){return query("WHERE q.work_id=? AND q.id=?",work,id).stream().findFirst();}
    public Optional<Entry> latest(String work){return query("WHERE q.work_id=? ORDER BY q.created_at DESC,q.id DESC LIMIT 1",work).stream().findFirst();}
    public Optional<Entry> claim(String token){
        var due=query("WHERE q.status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC') AND q.next_attempt_at<=CURRENT_TIMESTAMP AND (q.lease_until IS NULL OR q.lease_until<CURRENT_TIMESTAMP) ORDER BY q.created_at,q.id LIMIT 16");
        for(var entry:due)if(jdbc.update("UPDATE quick_creation SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP) WHERE id=? AND status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC') AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",token,entry.id())==1)return find(entry.workId(),entry.id());
        return Optional.empty();
    }
    public boolean checkpoint(String id,String token,String status,String revision,String task,String error,int delay){
        return jdbc.update("UPDATE quick_creation SET status=?,revision_id=?,task_id=?,error_code=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=? AND lease_token=? AND EXISTS(SELECT 1 FROM music_work w WHERE w.id=quick_creation.work_id AND w.deleted_at IS NULL)",status,revision,task,error,delay,id,token)==1;
    }
    public void release(String id,String token){jdbc.update("UPDATE quick_creation SET lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?",id,token);}
    public boolean retry(String work,String id,String lyricsKey){return transactions.execute(tx->{
        jdbc.queryForList("SELECT version FROM music_work WHERE id=? AND deleted_at IS NULL FOR UPDATE",Long.class,work);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM quick_creation WHERE work_id=? AND id<>? AND status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC')",Integer.class,work,id)>0)return false;
        return jdbc.update("UPDATE quick_creation SET status=CASE WHEN revision_id IS NULL THEN 'QUEUED' ELSE 'LYRICS_READY' END,lyrics_key=CASE WHEN revision_id IS NULL THEN ? ELSE lyrics_key END,error_code=NULL,next_attempt_at=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE work_id=? AND id=? AND status='FAILED' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",lyricsKey,work,id)==1;
    });}
    private List<Entry> query(String suffix,Object...args){return jdbc.query(SELECT+suffix,(r,n)->new Entry(r.getString("id"),r.getString("owner_id"),r.getString("work_id"),r.getLong("work_version"),r.getString("request_key"),r.getString("language"),r.getString("status"),r.getString("lyrics_key"),r.getString("music_key"),r.getString("revision_id"),r.getString("task_id"),r.getString("error_code"),r.getString("lease_token"),r.getTimestamp("created_at").toInstant()),args);}
}
