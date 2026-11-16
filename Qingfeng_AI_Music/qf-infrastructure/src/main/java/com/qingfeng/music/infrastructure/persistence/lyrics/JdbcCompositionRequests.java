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
        expireStale();
        return jdbc.query("SELECT status,revision_id,request_hash,error_code FROM lyrics_composition_request WHERE work_id=? AND request_key=?",(row,n)->new Entry(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),work,key).stream().findFirst();
    }
    public boolean claim(String work,String key){try{jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status,created_at) VALUES(?,?,'PENDING',CURRENT_TIMESTAMP)",work,key);return true;}catch(DuplicateKeyException duplicate){return false;}}
    public boolean claim(String owner,String work,String key,String hash){
        return Boolean.TRUE.equals(transactions.execute(tx->{
            lockOwner(owner);
            // Claim must not acquire an empty request range before the common usage lock.
            // Otherwise another owner holds usage and waits to INSERT into that range.
            jdbc.queryForObject("SELECT kind FROM model_usage_lock WHERE kind='LYRICS' FOR UPDATE",String.class);
            var uncertain=jdbc.query("SELECT request_key FROM lyrics_composition_request WHERE work_id=? AND status IN ('PENDING','UNKNOWN') FOR UPDATE",(rs,n)->rs.getString(1),work);
            if(!uncertain.isEmpty())return false; // A new key must not duplicate an unresolved provider submission.
            budget.reserve(owner,work,key,UsageBudget.Kind.LYRICS);
            if(!claim(work,key))return false;
            jdbc.update("UPDATE lyrics_composition_request SET request_hash=? WHERE work_id=? AND request_key=?",hash,work,key);
            return true;
        }));
    }
    public void complete(String work,String key,String revision){
        transactions.executeWithoutResult(tx->{
            String owner=chargedOwner(work,key);lockOwner(owner);
            if(jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED',revision_id=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status IN ('PENDING','UNKNOWN')",revision,work,key)!=1)
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
            // A network timeout does not prove rejection. Preserve debit, allocation and capacity.
            settle(work,key,owner,"UNKNOWN",false);
        });
    }
    /**
     * 请求失败：标记终态并退还预扣积分。
     * 退款与状态变更在同一事务内，退款失败会一起回滚 —— 请求仍是 PENDING，
     * 之后由超时清扫重试退款，绝不会出现「退款失败却显示已退」的中间态。
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
    /** Age alone cannot establish the provider's financial outcome. Keep unresolved rights reserved. */
    private void expireStale(){
        // A caller may already own another creator. Never sweep other owners inside that transaction.
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())return;
        var stale=jdbc.query("SELECT r.work_id,r.request_key,COALESCE(v.owner_id,w.owner_id) FROM lyrics_composition_request r JOIN music_work w ON w.id=r.work_id LEFT JOIN model_usage_reservation v ON v.kind=? AND v.work_id=r.work_id AND v.request_key=r.request_key WHERE r.status='PENDING' AND r.created_at<TIMESTAMPADD(SECOND,-180,CURRENT_TIMESTAMP)",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},KIND);
        for(var row:stale)transactions.executeWithoutResult(tx->{lockOwner(row[2]);expire(row[0],row[1],row[2]);});
    }
    private void expire(String work,String key,String owner){
        if(jdbc.update("UPDATE lyrics_composition_request SET status='UNKNOWN',error_code='LYRICS_REQUEST_TIMEOUT',finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",work,key)!=1)return;
        settle(work,key,owner,"UNKNOWN",false);
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
