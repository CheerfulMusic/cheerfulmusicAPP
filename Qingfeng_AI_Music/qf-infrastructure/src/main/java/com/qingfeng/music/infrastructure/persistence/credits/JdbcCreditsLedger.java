package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.common.exception.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 每个账号串行化；余额与不可变流水和订单/任务在同一事务内提交。 */
public final class JdbcCreditsLedger implements CreditsLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcCreditsLedger(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    public void credit(String owner, long amount, String reference) { change("CREDIT",owner,amount,reference); }
    public void debit(String owner, long amount, String reference) { if(amount<=0) throw new IllegalArgumentException("amount must be positive"); change("DEBIT",owner,-amount,reference); }
    public long balance(String owner) {
        var stored=jdbc.query("SELECT balance FROM credit_account WHERE owner_id=?",(rs,n)->rs.getLong(1),owner);
        if(!stored.isEmpty())return stored.get(0);
        Long value=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",Long.class,owner);
        return value==null?0:value;
    }
    public void refund(String owner, String reference) {
        transactions.executeWithoutResult(tx->{
            lockCreator(owner);
            var rows=jdbc.query("SELECT owner_id, amount FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=? FOR UPDATE",(rs,n)->new Entry(rs.getString(1),rs.getLong(2)),reference);
            if(rows.isEmpty()) return;
            Entry debit=rows.get(0);
            if(!debit.owner().equals(owner)) throw new DomainException("CREDIT_REFERENCE_CONFLICT","积分记录不匹配");
            insert("REFUND",owner,Math.negateExact(debit.amount()),reference);
        });
    }
    private void change(String type,String owner,long signed,String reference) {
        if(owner==null||owner.isBlank()||reference==null||reference.isBlank()||reference.length()>160||signed==0 || (type.equals("CREDIT") && signed<0) || (type.equals("DEBIT") && signed>0)) throw new IllegalArgumentException("invalid credit entry");
        transactions.executeWithoutResult(tx->{
            lockCreator(owner);
            insert(type,owner,signed,reference);
        });
    }
    private void lockCreator(String owner){
        if(owner==null||owner.isBlank()||jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty())
            throw new DomainException("CREDIT_OWNER_MISSING","账号不存在。");
    }
    private void insert(String type,String owner,long signed,String reference) {
        long available=account(owner);
        // Insert first: the unique key is the replay guard. No empty-range FOR UPDATE across different accounts.
        try{jdbc.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES(?,?,?,?,?)",type,reference,owner,signed,Timestamp.from(Instant.now()));}
        catch(org.springframework.dao.DuplicateKeyException duplicate){
            var existing=jdbc.query("SELECT owner_id,amount FROM credit_ledger WHERE entry_type=? AND reference_id=? FOR UPDATE",(rs,n)->new Entry(rs.getString(1),rs.getLong(2)),type,reference);
            if(existing.isEmpty()||!existing.get(0).equals(new Entry(owner,signed)))throw new DomainException("CREDIT_REFERENCE_CONFLICT","积分记录不匹配");
            return;
        }
        if(signed<0){
            Integer frozen=jdbc.queryForObject("SELECT payment_risk_frozen FROM creator WHERE id=? FOR UPDATE",Integer.class,owner);
            if(frozen!=null&&frozen!=0)throw new DomainException("PAYMENT_RISK_REVIEW","账号有待核查的支付退款或争议，付费创作暂时冻结，请联系支持。");
            if(available<Math.negateExact(signed))throw new DomainException("INSUFFICIENT_CREDITS","积分不足，请先充值。");
        }
        long next=Math.addExact(available,signed);
        if(jdbc.update("UPDATE credit_account SET balance=?,updated_at=CURRENT_TIMESTAMP WHERE owner_id=? AND balance=?",next,owner,available)!=1)
            throw new IllegalStateException("Account balance changed outside its owner lock");
    }
    private long account(String owner){
        var present=jdbc.query("SELECT owner_id FROM credit_account WHERE owner_id=?",(rs,n)->rs.getString(1),owner);
        if(present.isEmpty()){
            // A plain consistent read avoids INSERT...SELECT source gap locks between unrelated owners.
            // Every new writer first creates this account under the creator lock; legacy-only history is immutable.
            Long initial=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",Long.class,owner);
            try{jdbc.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,?,CURRENT_TIMESTAMP)",owner,initial==null?0:initial);}
            catch(org.springframework.dao.DuplicateKeyException alreadyInitialized){/* An older outer snapshot may not see the committed account; the locking read below does. */}
        }
        return jdbc.queryForObject("SELECT balance FROM credit_account WHERE owner_id=? FOR UPDATE",Long.class,owner);
    }
    private record Entry(String owner,long amount) {}
}
