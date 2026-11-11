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
            // 统一锁序：先锁 creator 行（X），再锁 credit_lock，避免与 reserve（creator→usage_lock→credit_lock）形成 AB-BA 死锁。
            lockCreator(owner);
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
        transactions.executeWithoutResult(tx->{
            // 统一锁序：先锁 creator 行（X），再锁 credit_lock。与 reserve/refund 保持一致，消除锁顺序反转。
            lockCreator(owner);
            lock();
            insert(type,owner,signed,reference);
        });
    }
    /** 以 creator 行的排他锁作为所有积分事务的第一把锁，建立全局一致的锁获取顺序。 */
    private void lockCreator(String owner){ if(owner!=null && !owner.isBlank()) jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE", owner); }
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
