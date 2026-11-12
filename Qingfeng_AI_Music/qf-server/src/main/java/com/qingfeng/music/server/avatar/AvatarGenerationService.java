package com.qingfeng.music.server.avatar;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, account-scoped provider requests. SUBMITTING/UNKNOWN are never replayed. */
public final class AvatarGenerationService {
    public record View(String id,String status,boolean available) {}
    private record Task(String id,String owner,String key,String prompt,String expected,String status,String remote) {}
    private final JdbcTemplate db; private final TransactionTemplate tx; private final AvatarService avatars;
    public static final long AVATAR_COST = 5L;
    private final AvatarImageProvider provider; private final CreditsLedger credits; private final CreditsPricing pricing; private final int dailyLimit;
    public AvatarGenerationService(JdbcTemplate db,TransactionTemplate tx,AvatarService avatars,AvatarImageProvider provider,CreditsLedger credits,CreditsPricing pricing,int dailyLimit) {
        if(dailyLimit<1 || dailyLimit>100)throw new IllegalArgumentException("Invalid avatar generation daily limit");
        this.db=db; this.tx=tx; this.avatars=avatars; this.provider=provider; this.credits=Objects.requireNonNull(credits); this.pricing=Objects.requireNonNull(pricing); this.dailyLimit=dailyLimit;
    }
    public int dailyLimit(){return dailyLimit;}
    private List<Task> rows(String sql,Object... args) {
        return db.query(sql,(rs,n)->new Task(rs.getString("id"),rs.getString("creator_id"),rs.getString("request_key"),rs.getString("prompt"),rs.getString("expected_version"),rs.getString("status"),rs.getString("provider_task_id")),args);
    }
    public View latest(String owner) {
        var tasks=rows("SELECT * FROM avatar_generation_task WHERE creator_id=? ORDER BY created_at DESC,id DESC LIMIT 1",owner);
        return tasks.isEmpty()?view(null,"NONE"):view(tasks.get(0).id(),tasks.get(0).status());
    }
    private View view(String id,String status){return new View(id,status,pricing.enabled() && provider.configured());}
    private static String reference(String id){return "avatar:"+id;}
    public View start(String owner,String key,String prompt) {
        if(key==null || !key.matches("[0-9a-fA-F-]{36}") || prompt==null || prompt.isBlank() || prompt.length()>1000)
            throw new DomainException("INVALID_AVATAR_REQUEST","请描述不超过1000字的头像要求。");
        String direction=prompt.trim();
        Task task=tx.execute(s->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",String.class,owner).size()!=1) throw new DomainException("AVATAR_OWNER_MISSING","无法确认当前账号。");
            var found=rows("SELECT * FROM avatar_generation_task WHERE creator_id=? AND request_key=?",owner,key);
            if(!found.isEmpty()) {
                if(!Objects.equals(found.get(0).prompt(),direction)) throw new DomainException("REQUEST_KEY_CONFLICT","请求内容已变化，请重新发起。");
                return found.get(0);
            }
            if(!pricing.enabled() || !provider.configured()) throw new DomainException("AVATAR_AI_UNAVAILABLE","AI 头像生成暂不可用，你仍可以上传头像。");
            var pending=rows("SELECT * FROM avatar_generation_task WHERE creator_id=? AND status IN ('SUBMITTING','RUNNING','UNKNOWN')",owner);
            if(!pending.isEmpty()) throw new DomainException("AVATAR_TASK_PENDING","已有头像任务正在处理或待核实，请勿重复提交。");
            Integer count=db.queryForObject("SELECT COUNT(*) FROM avatar_generation_task WHERE creator_id=? AND created_at>=?",Integer.class,owner,Timestamp.from(Instant.now().minusSeconds(86400)));
            if(count!=null && count>=dailyLimit) throw new DomainException("AVATAR_DAILY_LIMIT","头像生成次数已达上限，请稍后再试。");
            String id=UUID.randomUUID().toString(); String expected=avatars.current(owner).version();
            // The task and debit commit together under the creator lock; request replay never charges again.
            credits.debit(owner,AVATAR_COST,reference(id));
            db.update("INSERT INTO avatar_generation_task (id,creator_id,request_key,prompt,expected_version,status,created_at,updated_at) VALUES (?,?,?,?,?,'SUBMITTING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,owner,key,direction,expected);
            return new Task(id,owner,key,direction,expected,"SUBMITTING",null);
        });
        if(task==null)throw new IllegalStateException("Avatar task transaction failed");
        // Only the request that created the row may submit; retries return existing task.
        // Reserve a one-time dispatch marker before crossing the external boundary.
        if(!"SUBMITTING".equals(task.status())) return view(task.id(),task.status());
        if(!pricing.enabled() || !provider.configured()) return view(task.id(),task.status());
        // Legacy sponsored tasks have no debit. Never dispatch an old or inconsistent task for free.
        int claimed=db.update("UPDATE avatar_generation_task SET lease_token=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND status='SUBMITTING' AND lease_token IS NULL AND EXISTS (SELECT 1 FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=? AND owner_id=? AND amount=?) AND NOT EXISTS (SELECT 1 FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?)",task.id(),task.id(),owner,reference(task.id()),owner,-AVATAR_COST,reference(task.id()));
        if(claimed!=1){
            // Missing or already refunded historical charges require review, never a new provider POST.
            db.update("UPDATE avatar_generation_task SET status='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND status='SUBMITTING' AND lease_token IS NULL AND (NOT EXISTS (SELECT 1 FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=? AND owner_id=? AND amount=?) OR EXISTS (SELECT 1 FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?))",task.id(),owner,reference(task.id()),owner,-AVATAR_COST,reference(task.id()));
            var actual=rows("SELECT * FROM avatar_generation_task WHERE id=? AND creator_id=?",task.id(),owner);
            if(actual.isEmpty())throw new IllegalStateException("Avatar task missing");
            return view(task.id(),actual.get(0).status());
        }
        try {
            String remote=provider.submit(direction);
            int saved=db.update("UPDATE avatar_generation_task SET provider_task_id=?,status='RUNNING',lease_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='SUBMITTING' AND lease_token=?",remote,task.id(),task.id());
            if(saved!=1)return view(task.id(),"UNKNOWN");
            return view(task.id(),"RUNNING");
        } catch(AvatarImageProvider.Rejected rejected) {
            finish(task.id(),task.id(),"SUBMITTING","FAILED");
            // A concurrent recovery may have changed the status while the provider responded.
            var actual=rows("SELECT * FROM avatar_generation_task WHERE id=? AND creator_id=?",task.id(),owner);
            if(actual.isEmpty()) throw new IllegalStateException("Avatar task missing");
            return view(task.id(),actual.get(0).status());
        } catch(RuntimeException failure) {
            db.update("UPDATE avatar_generation_task SET status='UNKNOWN',lease_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='SUBMITTING'",task.id());
            return view(task.id(),"UNKNOWN");
        }
    }
    /** One lease per running task. Provider query and safe image download can be retried; creation cannot. */
    public void runOnce() {
        db.update("UPDATE avatar_generation_task SET status='UNKNOWN',lease_token=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='SUBMITTING' AND updated_at < ?",Timestamp.from(Instant.now().minusSeconds(120)));
        db.update("UPDATE avatar_generation_task SET status='UNKNOWN',lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE status='RUNNING' AND created_at<? AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)",Timestamp.from(Instant.now().minusSeconds(3600)));
        var ids=db.queryForList("SELECT id FROM avatar_generation_task WHERE status='RUNNING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) AND (next_attempt_at IS NULL OR next_attempt_at<CURRENT_TIMESTAMP) ORDER BY updated_at LIMIT 10",String.class);
        for(String id:ids) process(id);
    }
    private void process(String id) {
        String token=UUID.randomUUID().toString();
        int claimed=db.update("UPDATE avatar_generation_task SET lease_token=?,lease_until=? WHERE id=? AND status='RUNNING' AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) AND (next_attempt_at IS NULL OR next_attempt_at<CURRENT_TIMESTAMP)",token,Timestamp.from(Instant.now().plusSeconds(120)),id);
        if(claimed!=1)return;
        try {
            var found=rows("SELECT * FROM avatar_generation_task WHERE id=? AND lease_token=?",id,token); if(found.isEmpty())return;
            Task task=found.get(0);
            if(id.equals(avatars.current(task.owner()).version())) { done(id,token,"SUCCEEDED"); return; }
            var result=provider.query(task.remote());
            if("FAILED".equals(result.status())) { done(id,token,"FAILED"); return; }
            if(!"SUCCEEDED".equals(result.status())) {
                db.update("UPDATE avatar_generation_task SET failures=0,next_attempt_at=? WHERE id=? AND status='RUNNING' AND lease_token=?",Timestamp.from(Instant.now().plusSeconds(15)),id,token);
                return;
            }
            try(InputStream image=provider.download(result.imageUrl())) {
                boolean applied=avatars.saveGenerated(task.owner(),task.expected(),image,task.id(),token).isPresent();
                done(id,token,applied||id.equals(avatars.current(task.owner()).version())?"SUCCEEDED":"SUPERSEDED");
            }
        } catch(AvatarService.GenerationLeaseLost ignored) {
            // A recovered or expired worker cannot install an image or settle the current task.
        } catch(DomainException failure) {
            if("INVALID_COVER_IMAGE".equals(failure.code())) done(id,token,"FAILED"); else retry(id,token);
        } catch(Exception failure) { retry(id,token); }
        finally { db.update("UPDATE avatar_generation_task SET lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING' AND lease_token=?",id,token); }
    }
    private void done(String id,String token,String status) { finish(id,token,"RUNNING",status); }
    /** Finalization follows creator -> task -> ledger lock order. Refund and terminal state commit together. */
    private void finish(String id,String token,String expectedStatus,String status) {
        var owners=db.queryForList("SELECT creator_id FROM avatar_generation_task WHERE id=?",String.class,id);
        if(owners.isEmpty())return;
        String owner=owners.get(0);
        tx.executeWithoutResult(t->{
            if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",String.class,owner).size()!=1) throw new IllegalStateException("Avatar owner missing");
            var locked=rows("SELECT * FROM avatar_generation_task WHERE id=? AND creator_id=? AND status=? AND lease_token=? FOR UPDATE",id,owner,expectedStatus,token);
            if(locked.isEmpty())return;
            if("FAILED".equals(status)||"SUPERSEDED".equals(status)) credits.refund(owner,reference(id));
            if(db.update("UPDATE avatar_generation_task SET status=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND creator_id=? AND status=? AND lease_token=?",status,id,owner,expectedStatus,token)!=1) throw new IllegalStateException("Avatar task changed during settlement");
        });
    }
    private void retry(String id,String token) {
        db.update("UPDATE avatar_generation_task SET status=CASE WHEN failures>=4 THEN 'UNKNOWN' ELSE 'RUNNING' END,failures=failures+1,next_attempt_at=?,lease_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING' AND lease_token=?",Timestamp.from(Instant.now().plusSeconds(30)),id,token);
    }
}
