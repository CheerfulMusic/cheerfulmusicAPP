package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.credits.CreditConsumptions;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
            if (!jdbc.query("SELECT id FROM credit_consumption WHERE consumption_type=? AND work_id=? AND request_key=?", (rs, n) -> rs.getString(1), type, work, requestKey).isEmpty()) return;
            jdbc.update("INSERT INTO credit_consumption(id,owner_id,consumption_type,work_id,request_key,amount,task_status,refunded,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), owner, type, work, requestKey, amount, STATUS_RESERVED, 0);
        });
    }
    public void settled(String owner, String work, String requestKey, String type, String status, boolean refunded) {
        transactions.executeWithoutResult(tx ->
            jdbc.update("UPDATE credit_consumption SET task_status=?,refunded=?,updated_at=CURRENT_TIMESTAMP WHERE consumption_type=? AND work_id=? AND request_key=?",
                status, refunded ? 1 : 0, type, work, requestKey));
    }
}
