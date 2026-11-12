package com.qingfeng.music.server.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Verified provider events survive process crashes; queue notifications are optional wakeups only. */
@Service @Profile({"mysql","test"})
public class PaymentInbox {
    public record Delivery(String provider,String eventId,String type,String payload,String token,int attempt) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final com.qingfeng.music.events.DurableEvents events;
    public PaymentInbox(JdbcTemplate db,TransactionTemplate tx){this(db,tx,(topic,key,payload)->{});}
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInbox(JdbcTemplate db,TransactionTemplate tx,com.qingfeng.music.events.DurableEvents events){this.db=db;this.tx=tx;this.events=events;}
    public void acceptVerified(String provider,String eventId,String type,String payload){
        if(provider==null||!provider.matches("[A-Z_]{1,24}")||eventId==null||!eventId.matches("[A-Za-z0-9_.-]{1,128}")
                ||type==null||!type.matches("[A-Za-z0-9_.]{1,96}")||payload==null||payload.length()>30000)throw new IllegalArgumentException("Invalid payment event");
        String hash=hash(payload);
        try{
            tx.executeWithoutResult(t->{
                db.update("INSERT INTO payment_webhook_inbox(provider,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",provider,eventId,type,payload,hash);
                String id=UUID.nameUUIDFromBytes((provider+":"+eventId).getBytes(StandardCharsets.UTF_8)).toString();
                events.publish("payment.inbox",id,id);
            });
        }catch(DuplicateKeyException duplicate){
            var prior=db.queryForMap("SELECT event_type,payload_hash FROM payment_webhook_inbox WHERE provider=? AND event_id=?",provider,eventId);
            if(!type.equals(prior.get("event_type"))||!hash.equals(prior.get("payload_hash")))throw new IllegalStateException("Conflicting payment event identity");
        }
    }
    public Optional<Delivery> claim(String provider){
        // Conditional claim avoids holding a transaction or database connection over provider HTTP.
        var ids=db.query("SELECT event_id FROM payment_webhook_inbox WHERE provider=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP) ORDER BY next_attempt_at,created_at,event_id LIMIT 20",(rs,n)->rs.getString(1),provider);
        for(String id:ids){
            String token=UUID.randomUUID().toString();
            int changed=db.update("UPDATE payment_webhook_inbox SET status='PROCESSING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP),attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)",token,provider,id);
            if(changed==1)return Optional.of(db.queryForObject("SELECT event_type,payload,attempts FROM payment_webhook_inbox WHERE provider=? AND event_id=? AND lease_token=?",(rs,n)->new Delivery(provider,id,rs.getString(1),rs.getString(2),token,rs.getInt(3)),provider,id,token));
        }
        return Optional.empty();
    }
    public void complete(Delivery delivery,Runnable mutation){
        tx.executeWithoutResult(status->{
            var claimed=db.query("SELECT status FROM payment_webhook_inbox WHERE provider=? AND event_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",(rs,n)->rs.getString(1),delivery.provider(),delivery.eventId(),delivery.token());
            if(claimed.isEmpty()||!"PROCESSING".equals(claimed.get(0)))return;
            mutation.run();
            if(db.update("UPDATE payment_webhook_inbox SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.eventId(),delivery.token())!=1)
                throw new IllegalStateException("Payment event lease expired before commit");
        });
    }
    public void renew(Delivery delivery){
        if(db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP) WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.eventId(),delivery.token())!=1)
            throw new IllegalStateException("Payment event lease changed");
    }
    public void retry(Delivery delivery){
        long delay=Math.min(3600,5L<<Math.min(9,delivery.attempt()));
        db.update("UPDATE payment_webhook_inbox SET status='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),last_error='PROCESSING_FAILED',updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delay,delivery.provider(),delivery.eventId(),delivery.token());
    }
    private static String hash(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
