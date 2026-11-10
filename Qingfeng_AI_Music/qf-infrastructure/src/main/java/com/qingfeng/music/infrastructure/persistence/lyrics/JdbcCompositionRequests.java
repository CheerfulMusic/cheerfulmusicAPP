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
    public JdbcCompositionRequests(JdbcTemplate jdbc,UsageBudget budget,TransactionTemplate transactions,CreditsLedger credits,CreditConsumptions consumptions){
        this.jdbc=jdbc;this.budget=budget;this.transactions=transactions;this.credits=credits;this.consumptions=consumptions;}
    public Optional<Entry> find(String work,String key){
        expireStale();
        return jdbc.query("SELECT status,revision_id,request_hash,error_code FROM lyrics_composition_request WHERE work_id=? AND request_key=?",(row,n)->new Entry(row.getString(1),row.getString(2),row.getString(3),row.getString(4)),work,key).stream().findFirst();
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
        settle(work,key,CreditConsumptions.STATUS_SUCCEEDED,false);
    }
    public void completeWithRevision(String work,String key,String revision,Runnable save){
        transactions.executeWithoutResult(tx->{complete(work,key,revision);save.run();});
    }
    public void failed(String work,String key){failed(work,key,"LYRICS_REQUEST_FAILED");}
    /**
     * 请求失败：标记终态并退还预扣积分。
     * 退款与状态变更在同一事务内，退款失败会一起回滚 —— 请求仍是 PENDING，
     * 之后由超时清扫重试退款，绝不会出现「退款失败却显示已退」的中间态。
     */
    public void failed(String work,String key,String code){
        transactions.executeWithoutResult(tx->{
            if(jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code=?,finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",code,work,key)!=1)return;
            settle(work,key,CreditConsumptions.STATUS_FAILED,true);
        });
    }
    /** 超时未完成的请求视为失败：退还积分并保留幂等键，用户不会为没有歌词的结果付费。 */
    private void expireStale(){
        transactions.executeWithoutResult(tx->{
            var stale=jdbc.query("SELECT r.work_id,r.request_key,COALESCE(v.owner_id,w.owner_id) FROM lyrics_composition_request r JOIN music_work w ON w.id=r.work_id LEFT JOIN model_usage_reservation v ON v.kind=? AND v.work_id=r.work_id AND v.request_key=r.request_key WHERE r.status='PENDING' AND r.created_at<TIMESTAMPADD(SECOND,-180,CURRENT_TIMESTAMP)",(rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},KIND);
            for(var row:stale)expire(row[0],row[1],row[2]);
        });
    }
    private void expire(String work,String key,String owner){
        if(jdbc.update("UPDATE lyrics_composition_request SET status='FAILED',error_code='LYRICS_REQUEST_TIMEOUT',finished_at=CURRENT_TIMESTAMP WHERE work_id=? AND request_key=? AND status='PENDING'",work,key)!=1)return;
        settle(work,key,owner,CreditConsumptions.STATUS_FAILED,true);
    }
    /** 结算消费流水并退还预扣积分；退款与账本同事务，重复调用不会重复退款。 */
    private void settle(String work,String key,String status,boolean refunded){settle(work,key,chargedOwner(work,key),status,refunded);}
    private void settle(String work,String key,String owner,String status,boolean refunded){
        if(credits!=null&&refunded)credits.refund(owner,CreditReferences.of(KIND,work,key));
        if(consumptions!=null)consumptions.settled(owner,work,key,CreditConsumptions.TYPE_LYRICS,status,refunded);
    }
    /** 扣费时的归属来自额度预留行，作品归属变更不会把退款退给错误的人。 */
    private String chargedOwner(String work,String key){
        var owners=jdbc.query("SELECT owner_id FROM model_usage_reservation WHERE kind=? AND work_id=? AND request_key=?",(rs,n)->rs.getString(1),KIND,work,key).stream().findFirst();
        return owners.orElseGet(()->jdbc.query("SELECT owner_id FROM music_work WHERE id=?",(rs,n)->rs.getString(1),work).stream().findFirst().orElse(null));
    }
}
