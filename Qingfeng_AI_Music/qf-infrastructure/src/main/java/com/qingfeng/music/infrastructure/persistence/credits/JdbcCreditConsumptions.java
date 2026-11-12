package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditConsumptions;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.common.exception.DomainException;

/**
 * 消费流水落库；唯一键 (消费类型, 任务, 请求键) 保证重复调用不会重复记账。
 * 与积分账本写入处在同一事务，保证扣费与流水一致。
 */
public final class JdbcCreditConsumptions implements CreditConsumptions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public JdbcCreditConsumptions(JdbcTemplate jdbc, TransactionTemplate transactions) { this.jdbc = jdbc; this.transactions = transactions; }
    public void reserved(String owner, String work, String requestKey, String type, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        transactions.executeWithoutResult(tx -> {
            lockOwner(owner);
            try{jdbc.update("INSERT INTO credit_consumption(id,owner_id,consumption_type,work_id,request_key,amount,task_status,refunded,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), owner, type, work, requestKey, amount, STATUS_RESERVED, 0);}
            catch(org.springframework.dao.DuplicateKeyException duplicate){
                var rows=jdbc.queryForList("SELECT owner_id,amount FROM credit_consumption WHERE consumption_type=? AND work_id=? AND request_key=? FOR UPDATE",type,work,requestKey);
                if(rows.size()!=1||!owner.equals(rows.get(0).get("owner_id"))||amount!=((Number)rows.get(0).get("amount")).longValue())
                    throw new DomainException("CREDIT_REFERENCE_CONFLICT","消费请求归属或金额不匹配。");
            }
        });
    }
    public void settled(String owner, String work, String requestKey, String type, String status, boolean refunded) {
        transactions.executeWithoutResult(tx ->{
            lockOwner(owner);
            var rows=jdbc.queryForList("SELECT owner_id FROM credit_consumption WHERE consumption_type=? AND work_id=? AND request_key=? FOR UPDATE",type,work,requestKey);
            if(rows.isEmpty())return;
            if(!owner.equals(rows.get(0).get("owner_id")))throw new DomainException("CREDIT_REFERENCE_CONFLICT","消费请求归属不匹配。");
            jdbc.update("UPDATE credit_consumption SET task_status=?,refunded=?,updated_at=CURRENT_TIMESTAMP WHERE owner_id=? AND consumption_type=? AND work_id=? AND request_key=?",
                status, refunded ? 1 : 0,owner,type, work, requestKey);
        });
    }
    private void lockOwner(String owner){if(owner==null||jdbc.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner).isEmpty())throw new DomainException("CREDIT_OWNER_MISSING","无法确认消费账号。");}
}
