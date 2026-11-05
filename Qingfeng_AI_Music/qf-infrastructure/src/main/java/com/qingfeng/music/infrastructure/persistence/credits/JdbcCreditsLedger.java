package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.common.exception.DomainException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 串行化账本操作，事务与订单、任务写入共同提交或回滚。 */
public final class JdbcCreditsLedger implements CreditsLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcCreditsLedger(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc=jdbc; this.transactions=transactions; }
    public void credit(String owner, long amount, String reference) { change("CREDIT",owner,amount,reference); }
    public void debit(String owner, long amount, String reference) { if(amount<=0) throw new IllegalArgumentException("amount must be positive"); change("DEBIT",owner,-amount,reference); }
    public long balance(String owner) { Long value=jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",Long.class,owner); return value==null?0:value; }
    public void refund(String owner, String reference) {
        transactions.executeWithoutResult(tx->{
            lock();
            var rows=jdbc.query("SELECT owner_id, amount FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?",(rs,n)->new Entry(rs.getString(1),rs.getLong(2)),reference);
            if(rows.isEmpty()) return;
            Entry debit=rows.get(0);
            if(!debit.owner().equals(owner)) throw new DomainException("CREDIT_REFERENCE_CONFLICT","积分记录不匹配");
            insert("REFUND",owner,Math.negateExact(debit.amount()),reference);
        });
    }
    private void change(String type,String owner,long signed,String reference) {
        if(owner==null||owner.isBlank()||reference==null||reference.isBlank()||reference.length()>160||signed==0 || (type.equals("CREDIT") && signed<0) || (type.equals("DEBIT") && signed>0)) throw new IllegalArgumentException("invalid credit entry");
        transactions.executeWithoutResult(tx->{ lock(); insert(type,owner,signed,reference); });
    }
    private void insert(String type,String owner,long signed,String reference) {
        var existing=jdbc.query("SELECT owner_id,amount FROM credit_ledger WHERE entry_type=? AND reference_id=?",(rs,n)->new Entry(rs.getString(1),rs.getLong(2)),type,reference);
        if(!existing.isEmpty()) {
            if(!existing.get(0).equals(new Entry(owner,signed))) throw new DomainException("CREDIT_REFERENCE_CONFLICT","积分记录不匹配");
            return;
        }
        if(signed<0 && balance(owner)<Math.negateExact(signed)) throw new DomainException("INSUFFICIENT_CREDITS","积分不足，请先充值。");
        jdbc.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES(?,?,?,?,?)",type,reference,owner,signed,Timestamp.from(Instant.now()));
    }
    private void lock(){jdbc.queryForObject("SELECT id FROM credit_lock WHERE id=1 FOR UPDATE",Integer.class);}
    private record Entry(String owner,long amount) {}
}
