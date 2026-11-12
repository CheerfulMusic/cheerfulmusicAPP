package com.qingfeng.music.server.payment;

import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Financial review only. Call resolve from an independently authorized administrative boundary. */
@Service @Profile({"mysql","test"})
public class PaymentRiskService {
    public record RiskCase(String id,String provider,String resourceId,String eventType,String orderId,String ownerId,String status,String lastEventId,String decision,String reviewedBy,String reason,Instant updatedAt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public PaymentRiskService(JdbcTemplate db,TransactionTemplate tx){this.db=db;this.tx=tx;}
    public List<RiskCase> listOpen(int limit){
        return db.query("SELECT * FROM payment_risk_case WHERE status IN ('OPEN','REVIEW') ORDER BY created_at LIMIT ?",(rs,n)->new RiskCase(rs.getString("id"),rs.getString("provider"),rs.getString("resource_id"),rs.getString("event_type"),rs.getString("order_id"),rs.getString("owner_id"),rs.getString("status"),rs.getString("last_event_id"),rs.getString("decision"),rs.getString("reviewed_by"),rs.getString("review_reason"),rs.getTimestamp("updated_at").toInstant()),Math.max(1,Math.min(limit,200)));
    }
    /** Correlation must already have been verified against the provider's authoritative resource. */
    public void recordVerified(String provider,String resourceId,String eventType,String orderId,String eventId){
        tx.executeWithoutResult(t->{
            String owner=db.queryForObject("SELECT creator_id FROM payment_order WHERE id=? AND provider=?",String.class,orderId,provider);
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            var previous=db.queryForList("SELECT id,last_event_id,status FROM payment_risk_case WHERE provider=? AND resource_id=? AND order_id=? FOR UPDATE",provider,resourceId,orderId);
            if(previous.isEmpty()){
                db.update("INSERT INTO payment_risk_case(id,provider,resource_id,event_type,order_id,owner_id,status,last_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,'OPEN',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),provider,resourceId,eventType,orderId,owner,eventId);
            }else if(!eventId.equals(previous.get(0).get("last_event_id"))){
                // A new authoritative event reopens review; a duplicate event never undoes an administrator's decision.
                db.update("UPDATE payment_risk_case SET event_type=?,status='REVIEW',last_event_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",eventType,eventId,previous.get(0).get("id"));
            }
            refreshHold(owner);
        });
    }
    /** No balance adjustment. RELEASE means a human verified no unpaid entitlement remains; HOLD stays frozen. */
    public RiskCase resolveCase(String id,String expectedEventId,String decision,String reviewer,String reason){
        if(!Set.of("RELEASE","HOLD").contains(decision)||reviewer==null||reviewer.isBlank()||reviewer.length()>128||reason==null||reason.trim().length()<10||reason.length()>1000)
            throw new IllegalArgumentException("A named reviewer, decision and substantive audit reason are required");
        return tx.execute(t->{
            String owner=db.queryForObject("SELECT owner_id FROM payment_risk_case WHERE id=?",String.class,id);
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            var rows=db.queryForList("SELECT * FROM payment_risk_case WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty())throw new IllegalArgumentException("Unknown payment risk case");
            if(!Objects.equals(expectedEventId,rows.get(0).get("last_event_id")))throw new IllegalStateException("Payment risk event changed; review the current event");
            db.update("INSERT INTO payment_risk_review(id,case_id,expected_event_id,reviewer,decision,reason,created_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),id,expectedEventId,reviewer,decision,reason.trim());
            db.update("UPDATE payment_risk_case SET status=?,decision=?,reviewed_by=?,review_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?","RELEASE".equals(decision)?"CLOSED":"REVIEW",decision,reviewer,reason.trim(),id);
            refreshHold(owner);
            var row=db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",id);
            return new RiskCase(id,(String)row.get("provider"),(String)row.get("resource_id"),(String)row.get("event_type"),(String)row.get("order_id"),owner,(String)row.get("status"),(String)row.get("last_event_id"),decision,reviewer,reason.trim(),Instant.now());
        });
    }
    private void refreshHold(String owner){
        // Called only while holding the creator lock; closing one case cannot clear another case's hold.
        var active=db.query("SELECT id FROM payment_risk_case WHERE owner_id=? AND status IN ('OPEN','REVIEW') FOR UPDATE",(rs,n)->rs.getString(1),owner);
        db.update("UPDATE creator SET payment_risk_frozen=? WHERE id=?",active.isEmpty()?0:1,owner);
    }
}
