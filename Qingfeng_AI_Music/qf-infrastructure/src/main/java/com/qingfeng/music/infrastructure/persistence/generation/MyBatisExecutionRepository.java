package com.qingfeng.music.infrastructure.persistence.generation;

import com.qingfeng.music.generation.application.ExecutionRepository;
import java.time.Instant;
import java.util.*;

public final class MyBatisExecutionRepository implements ExecutionRepository {
    private final ExecutionMapper mapper;
    public MyBatisExecutionRepository(ExecutionMapper mapper) { this.mapper=mapper; }
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private org.springframework.transaction.support.TransactionTemplate transactions;
    private com.qingfeng.music.credits.CreditsLedger credits;
    public MyBatisExecutionRepository withCredits(org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.support.TransactionTemplate transactions, com.qingfeng.music.credits.CreditsLedger credits){this.jdbc=jdbc;this.transactions=transactions;this.credits=credits;return this;}
    public boolean lockProvider(String provider,String token,Instant now,Instant until) { mapper.ensureProvider(provider);return mapper.lock(provider,token,now,until)==1; }
    public void unlockProvider(String provider,String token) { mapper.unlock(provider,token); }
    public Optional<Execution> claim(String provider,String token,Instant now,Instant until) {
        for(String id:mapper.candidates(provider,now)) if(mapper.claim(id,token,now,until)==1) {
            var row=mapper.find(id);
            return Optional.of(new Execution(MyBatisTaskRepository.toTask(row),row.providerTaskId,row.audioUrl,row.submitAttempts,row.archiveAttempts,row.queryFailures,token));
        }
        return Optional.empty();
    }
    public void release(String task,String token) { mapper.release(task,token); }
    public boolean renew(String provider,String task,String token,Instant now,Instant until){return mapper.renewProvider(provider,token,now,until)==1&&mapper.renewTask(task,token,now,until)==1;}
    public boolean startSubmission(String task,String token,Instant now) { return mapper.start(task,token,now)==1; }
    public void accepted(String task,String external,Instant now,Instant next) { mapper.accepted(task,external,now,next); }
    public void unknown(String task,String token,String code,Instant now) { if(mapper.unknown(task,token,code,now)==1)mapper.interrupted(task,now); }
    public void running(String task,String token,int failures,String code,Instant now,Instant next) { mapper.running(task,token,failures,code,now,next); }
    public void review(String task,String token,String code,Instant now,Instant next){mapper.review(task,token,code,now,next);}
    public void saving(String task,String token,String url,Instant now) { mapper.saving(task,token,url,now); }
    public void refreshAudioUrl(String task,String token,String url){mapper.refreshUrl(task,token,url);}
    public void archiveFailed(String task,String token,int attempts,boolean terminal,Instant now,Instant next) { mapper.archiveFailed(task,token,attempts,terminal?"FAILED":"SAVING",now,next); }
    public void succeeded(String task,String token,String asset,Instant now) { mapper.succeeded(task,token,asset,now); }
    public void failed(String task,String token,String code,Instant now) {
        if(transactions==null){mapper.failed(task,token,code,now);return;}
        transactions.executeWithoutResult(tx->{
            if(mapper.failed(task,token,code,now)!=1)return;
            jdbc.query("SELECT r.owner_id,r.work_id,r.request_key FROM generation_task t JOIN model_usage_reservation r ON r.kind='MUSIC' AND r.work_id=t.work_id AND r.request_key=t.request_key WHERE t.id=?",rs->{credits.refund(rs.getString(1),"music:"+rs.getString(2)+":"+rs.getString(3));},task);
            // 只对确定未受理的请求释放每日占额；结果未知和已完成音乐的归档失败不能释放。
            if(code.equals("LOCAL_PREPARATION_FAILED")||code.equals("PROVIDER_REJECTED")||code.equals("PROVIDER_INPUT_REJECTED")||code.equals("PROVIDER_AUTH_FAILED")||code.equals("PROVIDER_BALANCE_LOW")||code.equals("PROVIDER_RATE_LIMITED")||code.equals("PROVIDER_ENDPOINT_UNAVAILABLE")||code.equals("PROVIDER_CONNECT_FAILED")||code.equals("PROVIDER_COOLDOWN"))
                jdbc.update("DELETE FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=(SELECT work_id FROM generation_task WHERE id=?) AND request_key=(SELECT request_key FROM generation_task WHERE id=?)",task,task);
        });
    }
    public boolean retryArchive(String work,String task,Instant now) { return mapper.retry(work,task,now)==1; }
    public boolean closeChecking(String work,String task,Instant now){return mapper.closeChecking(work,task,now,now.minusSeconds(180))==1;}
    public String attempt(String task,String phase,Instant now) { String id=UUID.randomUUID().toString();mapper.attempt(id,task,phase,now);return id; }
    public void finishAttempt(String id,String outcome,String code,Instant now) { mapper.finish(id,outcome,code,now); }
}
