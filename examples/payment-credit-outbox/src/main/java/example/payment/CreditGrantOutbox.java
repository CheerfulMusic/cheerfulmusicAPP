package example.payment;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

/** Reference implementation; NOT a component and NOT wired into production. */
public final class CreditGrantOutbox {
    private static final Logger log=LoggerFactory.getLogger(CreditGrantOutbox.class);
    public enum State { READY, PROCESSING, DONE, DEAD }
    public record Message(String orderId,String ownerId,long credits,String provider,String merchant,
                          String environment,String captureId,State state,int attempts) {}
    public record Claim(Message message,String token) {}
    /** Construct ONLY after signature + authoritative provider query + ownership verification. */
    public record VerifiedPayment(String orderId,String ownerId,long amountMinor,String currency,
                                  String provider,String merchant,String environment,String captureId) {}
    public interface Credits {
        /** MUST join this same DB transaction. Never HTTP, MQ, REQUIRES_NEW or raw balance SQL.
         * Production adapter must use the full fenced ledger AND bind native payment identity. */
        void grantAndBind(Message message,String idempotencyKey);
    }
    public interface CrashProbe {
        default void paymentCommitted(String orderId) {}
        default void claimed(Claim claim) {}
        default void creditedBeforeDone(String orderId) {}
    }
    private final JdbcTemplate db;
    private final TransactionTemplate required, independent;
    private final Credits credits;
    private final CrashProbe probe;
    private final int maxAttempts, initialDelaySeconds, leaseSeconds;

    public CreditGrantOutbox(JdbcTemplate db,PlatformTransactionManager manager,Credits credits,CrashProbe probe,
                             int maxAttempts,int initialDelaySeconds,int leaseSeconds) {
        if(maxAttempts<1||initialDelaySeconds<0||leaseSeconds<1)throw new IllegalArgumentException();
        this.db=db;this.credits=credits;this.probe=probe;this.maxAttempts=maxAttempts;
        this.initialDelaySeconds=initialDelaySeconds;this.leaseSeconds=leaseSeconds;
        required=new TransactionTemplate(manager);
        independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(20);
    }

    /** TX A: confirmed order + one immutable grant intent commit together; no credit issued here. */
    public void confirm(VerifiedPayment fact) {
        required.executeWithoutResult(tx->{
            if(db.queryForList("SELECT id FROM demo_owner WHERE id=? FOR UPDATE",fact.ownerId()).size()!=1)
                throw new IllegalStateException("OWNER_MISSING");
            var row=db.queryForMap("SELECT * FROM demo_payment_order WHERE id=? FOR UPDATE",fact.orderId());
            if(!Objects.equals(row.get("owner_id"),fact.ownerId())
                || ((Number)row.get("amount_minor")).longValue()!=fact.amountMinor()
                || !Objects.equals(row.get("currency"),fact.currency())
                || !Objects.equals(row.get("provider"),fact.provider())
                || !Objects.equals(row.get("merchant"),fact.merchant())
                || !Objects.equals(row.get("environment"),fact.environment())
                || fact.captureId()==null||fact.captureId().isBlank())throw new IllegalStateException("PAYMENT_FACT_MISMATCH");
            var existing=db.query("SELECT * FROM demo_credit_outbox WHERE order_id=? FOR UPDATE",(rs,n)->message(rs),fact.orderId());
            if(!existing.isEmpty()) {
                if(!existing.get(0).captureId().equals(fact.captureId()))throw new IllegalStateException("PAYMENT_IDENTITY_CONFLICT");
                return; // same order/same capture is replay; never reset DONE/DEAD to READY
            }
            if(!"PENDING".equals(row.get("status")))throw new IllegalStateException("ORDER_STATE_REVIEW_REQUIRED");
            db.update("UPDATE demo_payment_order SET status='PAYMENT_CONFIRMED' WHERE id=?",fact.orderId());
            db.update("""
                INSERT INTO demo_credit_outbox(order_id,owner_id,credits,provider,merchant,environment,capture_id,
                  state,attempts,next_attempt_at,created_at)
                VALUES(?,?,?,?,?,?,?,'READY',0,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)
                """,fact.orderId(),fact.ownerId(),row.get("credits"),fact.provider(),fact.merchant(),fact.environment(),fact.captureId(),initialDelaySeconds);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){
                    log.info("PAYMENT_COMMITTED_GRANT_PENDING order={}",fact.orderId());
                    probe.paymentCommitted(fact.orderId()); // breakpoint / kill process HERE, after actual commit
                }
            });
        });
    }

    /** Distributed lease acquisition: atomic SQL CAS, shared by every application instance. */
    public Optional<Claim> claim() {
        return independent.execute(tx->{
            var candidates=db.query("""
                SELECT * FROM demo_credit_outbox
                 WHERE (state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP)
                    OR (state='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP)
                 ORDER BY created_at,order_id LIMIT 20
                """,(rs,n)->message(rs));
            for(var message:candidates){
                String token=UUID.randomUUID().toString();
                if(message.attempts()>=maxAttempts){
                    int changed=db.update("""
                        UPDATE demo_credit_outbox SET state='DEAD',last_error='RETRY_EXHAUSTED',lease_token=NULL,lease_until=NULL
                         WHERE order_id=? AND attempts>=? AND
                          ((state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP) OR
                           (state='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP))
                        """,message.orderId(),maxAttempts);
                    if(changed==1)alert(message.orderId(),"RETRY_EXHAUSTED");
                    continue;
                }
                int won=db.update("""
                    UPDATE demo_credit_outbox SET state='PROCESSING',attempts=attempts+1,
                      lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP)
                     WHERE order_id=? AND attempts<? AND
                      ((state='READY' AND next_attempt_at<=CURRENT_TIMESTAMP) OR
                       (state='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP))
                    """,token,leaseSeconds,message.orderId(),maxAttempts);
                if(won==1)return Optional.of(new Claim(new Message(message.orderId(),message.ownerId(),message.credits(),
                    message.provider(),message.merchant(),message.environment(),message.captureId(),State.PROCESSING,message.attempts()+1),token));
            }
            return Optional.empty();
        });
    }

    /** TX B: credit ledger/account/entitlement/native identity and DONE are all-or-nothing. */
    public boolean deliver(Claim claim) {
        return Boolean.TRUE.equals(independent.execute(tx->{
            // Common lock order: owner -> order -> message -> ledger/entitlement.
            if(db.queryForList("SELECT id FROM demo_owner WHERE id=? FOR UPDATE",claim.message().ownerId()).size()!=1)
                throw new IllegalStateException("OWNER_MISSING");
            var order=db.queryForMap("SELECT status FROM demo_payment_order WHERE id=? FOR UPDATE",claim.message().orderId());
            var rows=db.queryForList("""
                SELECT order_id FROM demo_credit_outbox WHERE order_id=? AND state='PROCESSING'
                  AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE
                """,claim.message().orderId(),claim.token());
            if(rows.isEmpty())return false; // expired/reassigned worker cannot mutate financial state
            if(!"PAYMENT_CONFIRMED".equals(order.get("status")))throw new IllegalStateException("ORDER_STATE_MISMATCH");
            credits.grantAndBind(claim.message(),"payment:"+claim.message().orderId());
            probe.creditedBeforeDone(claim.message().orderId()); // crashing HERE rolls the ENTIRE TX B back
            int changed=db.update("""
                UPDATE demo_credit_outbox SET state='DONE',completed_at=CURRENT_TIMESTAMP,
                  lease_token=NULL,lease_until=NULL,last_error=NULL
                 WHERE order_id=? AND state='PROCESSING' AND lease_token=?
                """,claim.message().orderId(),claim.token());
            if(changed!=1)throw new IllegalStateException("LEASE_LOST");
            return true;
        }));
    }

    /** Called only AFTER TX B rolled back; failure bookkeeping must not be rolled back with credit. */
    public void failed(Claim claim,RuntimeException failure) {
        independent.executeWithoutResult(tx->{
            boolean terminal=claim.message().attempts()>=maxAttempts;
            int delay=Math.min(1800,5*(1<<Math.min(8,claim.message().attempts())))+java.util.concurrent.ThreadLocalRandom.current().nextInt(5);
            int changed=db.update("""
                UPDATE demo_credit_outbox SET state=?,last_error=?,
                  next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),lease_token=NULL,lease_until=NULL
                 WHERE order_id=? AND state='PROCESSING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP
                """,terminal?"DEAD":"READY",failure.getClass().getSimpleName(),delay,claim.message().orderId(),claim.token());
            if(changed==1&&terminal)alert(claim.message().orderId(),"RETRY_EXHAUSTED");
        });
    }
    private void alert(String id,String reason){
        db.update("INSERT INTO demo_credit_alert(order_id,reason,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",id,reason);
    }

    /** Register as a worker-only bean, enable scheduling explicitly; default reference is not active. */
    @Scheduled(fixedDelayString="${demo.credit-outbox.scan-ms:10000}")
    public void scan() {
        for(int i=0;i<20;i++){
            Optional<Claim> next;
            try{next=claim();}catch(RuntimeException e){log.error("CREDIT_OUTBOX_SCAN_FAILED type={}",e.getClass().getSimpleName());break;}
            if(next.isEmpty())break;
            var claimed=next.get();
            try{probe.claimed(claimed);deliver(claimed);}
            catch(RuntimeException e){
                log.warn("CREDIT_GRANT_RETRY order={} attempt={} type={}",claimed.message().orderId(),claimed.message().attempts(),e.getClass().getSimpleName());
                try{failed(claimed,e);}catch(RuntimeException bookkeeping){log.error("CREDIT_RETRY_RECORD_FAILED order={}",claimed.message().orderId());}
            }
        }
        // Durable alert remains open and is emitted again until operations acknowledge it.
        // Wire a metrics/log alert rule; do not mark sent merely because an external call was attempted.
        db.queryForList("SELECT order_id,reason FROM demo_credit_alert WHERE acknowledged_at IS NULL ORDER BY created_at LIMIT 100")
          .forEach(a->log.error("CREDIT_GRANT_DEAD order={} reason={}",a.get("order_id"),a.get("reason")));
    }
    private static Message message(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Message(rs.getString("order_id"),rs.getString("owner_id"),rs.getLong("credits"),rs.getString("provider"),
            rs.getString("merchant"),rs.getString("environment"),rs.getString("capture_id"),State.valueOf(rs.getString("state")),rs.getInt("attempts"));
    }
}
