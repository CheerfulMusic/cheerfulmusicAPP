package com.qingfeng.music.infrastructure.persistence.lyrics;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import com.qingfeng.music.credits.CreditConsumptions;
import com.qingfeng.music.credits.CreditReferences;
import com.qingfeng.music.credits.CreditsLedger;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import com.qingfeng.music.usage.UsageBudget;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.common.exception.DomainException;
public final class JdbcCompositionRequests implements CompositionRequests {
    private static final String KIND="LYRICS";
    private static final String SUPERSEDED="LYRICS_REQUEST_SUPERSEDED";
    private final JdbcTemplate jdbc;
    private final UsageBudget budget;
    private final TransactionTemplate transactions;
    private final CreditsLedger credits;
    private final CreditConsumptions consumptions;
    private final com.qingfeng.music.credits.LyricFreeAllowance lyricFreeAllowance;
    public JdbcCompositionRequests(JdbcTemplate jdbc,UsageBudget budget,TransactionTemplate transactions,CreditsLedger credits,CreditConsumptions consumptions){
        this(jdbc,budget,transactions,credits,consumptions,null);
    }
    public JdbcCompositionRequests(JdbcTemplate jdbc,UsageBudget budget,TransactionTemplate transactions,CreditsLedger credits,CreditConsumptions consumptions,
                                   com.qingfeng.music.credits.LyricFreeAllowance lyricFreeAllowance){
        this.jdbc=jdbc;this.budget=budget;this.transactions=transactions;this.credits=credits;this.consumptions=consumptions;this.lyricFreeAllowance=lyricFreeAllowance;}
    public Optional<Entry> find(String work,String key){
        expireStale(work,key);
        return jdbc.query("SELECT status,revision_id,request_hash,error_code FROM lyrics_composition_request WHERE work_id=? AND request_key=?",(row,n)->new Entry(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),work,key).stream().findFirst();
    }
    public boolean claim(String work,String key){try{jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status,created_at) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP)",work,key);return true;}catch(DuplicateKeyException duplicate){return false;}}
    public boolean claim(String owner,String work,String key,String hash){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            lockOwner(owner);
            // Claim must not acquire an empty request range before the common usage lock.
            // Otherwise another owner holds usage and waits to INSERT into that range.
            jdbc.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='LYRICS' FOR UPDATE",String.class);
            if (!jdbc.queryForList("""
                SELECT request_key FROM lyrics_composition_request
                WHERE work_id=? AND (request_key=? OR
                    (status='PENDING' AND created_at>TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP)))
                FOR UPDATE
                """, String.class, work, key, PENDING_TIMEOUT_SECONDS).isEmpty()) return false;
            budget.reserve(owner,work,key,UsageBudget.Kind.LYRICS);
            if (!claim(work,key)) throw new DomainException("LYRICS_REQUEST_PENDING","这次歌词请求已提交，请先查看原请求进度。");
            // Settle stale pending requests as unresolved before fencing their late results.
            var staleKeys = jdbc.queryForList("SELECT request_key FROM lyrics_composition_request WHERE work_id=? AND request_key<>? AND status='PENDING'",
                String.class,work,key);
            for (String staleKey : staleKeys) unknown(work,staleKey,"LYRICS_REQUEST_TIMEOUT");
            jdbc.update("""
                UPDATE lyrics_composition_request SET status='UNKNOWN',error_code=?,finished_at=COALESCE(finished_at,CURRENT_TIMESTAMP)
                WHERE work_id=? AND request_key<>? AND status='UNKNOWN' AND (error_code IS NULL OR error_code<>?)
                """,SUPERSEDED,work,key,SUPERSEDED);
            jdbc.update("UPDATE lyrics_composition_request SET request_hash=? WHERE work_id=? AND request_key=?",hash,work,key);
            return true;
        }));
    }
    public void complete(String work,String key,String revision){
        transactions.executeWithoutResult(tx->{
            String owner=chargedOwner(work,key);lockOwner(owner);
            if(jdbc.update("""
                UPDATE lyrics_composition_request SET status='SUCCEEDED',revision_id=?,finished_at=CURRENT_TIMESTAMP
                WHERE work_id=? AND request_key=? AND status IN ('PENDING','UNKNOWN')
                  AND (error_code IS NULL OR error_code<>?)
                  AND EXISTS (SELECT 1 FROM music_work WHERE id=? AND deleted_at IS NULL)
                """,revision,work,key,SUPERSEDED,work)!=1)
                throw new DomainException("LYRICS_REQUEST_FAILED","上次歌词编写已结束，可以重新生成；已有内容仍会保留。");
            settle(work,key,owner,CreditConsumptions.STATUS_SUCCEEDED,false);
        });
    }
    public void completeWithRevision(String work,String key,String revision,Runnable save){
        transactions.executeWithoutResult(tx->{lockOwner(chargedOwner(work,key));complete(work,key,revision);save.run();});
    }
    public void failed(String work,String key){failed(work,key,"LYRICS_REQUEST_FAILED");}
    public void unknown(String work,String key,String code){
        transactions.executeWithoutResult(tx->{
            String owner=chargedOwner(work,key);lockOwner(owner);
            if(jdbc.update("UPDATE lyrics_composition_request SET status='UNKNOWN',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",code,work,key)!=1)return;
            // A network timeout does not prove rejection. Preserve debit and allocation.
            settle(work,key,owner,"UNKNOWN",false);
        });
    }
    /**
     * 请求失败：标记终态并退还预扣积分。
     * 退款与状态变更在同一事务内提交或回滚。
     */
    public void failed(String work,String key,String code){
        if(java.util.Set.of("LYRICS_PROVIDER_TIMEOUT","LYRICS_PROVIDER_NETWORK_ERROR","LYRICS_REQUEST_INTERRUPTED","LYRICS_REQUEST_TIMEOUT").contains(code)){
            unknown(work,key,code);return;
        }
        transactions.executeWithoutResult(tx->{
            String owner=chargedOwner(work,key);lockOwner(owner);
            if(jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",code,work,key)!=1)return;
            settle(work,key,owner,CreditConsumptions.STATUS_FAILED,true);
        });
    }
    private void expireStale(String work,String key){
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) return;
        var stale = jdbc.queryForObject("""
            SELECT COUNT(*) FROM lyrics_composition_request WHERE work_id=? AND request_key=?
              AND status='PENDING' AND created_at<=TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP)
            """,Integer.class,work,key,PENDING_TIMEOUT_SECONDS);
        if (stale == null || stale == 0) return;
        unknown(work,key,"LYRICS_REQUEST_TIMEOUT");
    }
    /** 结算消费流水并退还预扣积分；退款与账本同事务，重复调用不会重复退款。 */
    private void settle(String work,String key,String status,boolean refunded){settle(work,key,chargedOwner(work,key),status,refunded);}
    private void settle(String work,String key,String owner,String status,boolean refunded){
        if(credits!=null&&refunded)credits.refund(owner,CreditReferences.of(KIND,work,key));
        if(lyricFreeAllowance!=null&&refunded)lyricFreeAllowance.release(work,key);
        if(consumptions!=null)consumptions.settled(owner,work,key,CreditConsumptions.TYPE_LYRICS,status,refunded);
    }
    /** 扣费时的归属来自额度预留行，作品归属变更不会把退款退给错误的人。 */
    private String chargedOwner(String work,String key){
        var owners=jdbc.query("SELECT owner_id FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?",(rs,n)->rs.getString(1),KIND,work,key).stream().findFirst();
        return owners.orElseGet(()->jdbc.query("SELECT owner_id FROM music_work WHERE id=?",(rs,n)->rs.getString(1),work).stream().findFirst().orElse(null));
    }
    private void lockOwner(String owner){
        if(owner==null||jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty())
            throw new DomainException("CREDIT_OWNER_MISSING","无法确认原始扣费账号。");
    }
}
