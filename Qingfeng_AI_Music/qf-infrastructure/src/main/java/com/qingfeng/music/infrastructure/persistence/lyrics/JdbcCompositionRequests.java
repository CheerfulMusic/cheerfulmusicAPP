package com.qingfeng.music.infrastructure.persistence.lyrics;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import com.qingfeng.music.usage.UsageBudget;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.common.exception.DomainException;
public final class JdbcCompositionRequests implements CompositionRequests {
    private final JdbcTemplate jdbc;
    private final UsageBudget budget;
    private final TransactionTemplate transactions;
    public JdbcCompositionRequests(JdbcTemplate jdbc,UsageBudget budget,TransactionTemplate transactions){this.jdbc=jdbc;this.budget=budget;this.transactions=transactions;}
    public Optional<Entry> find(String work,String key){
        jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code='LYRICS_REQUEST_TIMEOUT',finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING' AND created_at<TIMESTAMPADD(SECOND,-180,CURRENT_TIMESTAMP)",work,key);
        return jdbc.query("SELECT status,revision_id,request_hash,error_code FROM lyrics_composition_request WHERE work_id=? AND request_key=?",(row,n)->new Entry(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),work,key).stream().findFirst();
    }
    /** 同步模型 HTTP 超时为90秒；3分钟后仍PENDING视为进程中断，迟到响应由完成时的状态检查拦截。 */
    public static void expireStale(org.springframework.jdbc.core.JdbcTemplate jdbc){
        jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code='LYRICS_REQUEST_TIMEOUT',finished_at=CURRENT_TIMESTAMP WHERE status='PENDING' AND created_at<TIMESTAMPADD(SECOND,-180,CURRENT_TIMESTAMP)");
    }
    public boolean claim(String work,String key){try{jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status,created_at) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP)",work,key);return true;}catch(DuplicateKeyException duplicate){return false;}}
    public boolean claim(String owner,String work,String key,String hash){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            budget.reserve(owner,work,key,UsageBudget.Kind.LYRICS);
            if(!claim(work,key))return false;
            jdbc.update("UPDATE lyrics_composition_request SET request_hash=? WHERE work_id=? AND request_key=?",hash,work,key);
            return true;
        }));
    }
    public void complete(String work,String key,String revision){
        if(jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED',revision_id=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",revision,work,key)!=1)
            throw new DomainException("LYRICS_REQUEST_FAILED","上次歌词编写已结束，可以重新生成；已有内容仍会保留。");
    }
    public void completeWithRevision(String work,String key,String revision,Runnable save){
        transactions.executeWithoutResult(tx->{complete(work,key,revision);save.run();});
    }
    public void failed(String work,String key){failed(work,key,"LYRICS_REQUEST_FAILED");}
    public void unknown(String work,String key,String code){
        jdbc.update("UPDATE lyrics_composition_request SET status='UNKNOWN',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",code,work,key);
    }
    public void failed(String work,String key,String code){jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",code,work,key);}
}
