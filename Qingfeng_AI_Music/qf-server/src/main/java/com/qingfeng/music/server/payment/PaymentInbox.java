package com.qingfeng.music.server.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Verified provider pointers survive crashes; provider HTTP never holds a financial transaction. */
@Service @Profile({"mysql","test"})
public class PaymentInbox {
    public static final String LEGACY_SCOPE="LEGACY_UNKNOWN";
    public record Delivery(String provider,String eventId,String type,String payload,String token,int attempt,String merchant,String environment) {
        public Delivery(String provider,String eventId,String type,String payload,String token,int attempt){this(provider,eventId,type,payload,token,attempt,LEGACY_SCOPE,LEGACY_SCOPE);}
    }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final com.qingfeng.music.events.DurableEvents events;
    private enum Layout { LEGACY, SCOPED }
    // One service instance belongs to one fixed schema; never reuse it across a schema cutover.
    private volatile Layout layout;
    public PaymentInbox(JdbcTemplate db,TransactionTemplate tx){this(db,tx,(topic,key,payload)->{});}
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInbox(JdbcTemplate db,TransactionTemplate tx,com.qingfeng.music.events.DurableEvents events){this.db=db;this.tx=tx;this.events=events;}
    /** Compatibility callers may only touch explicitly unscoped historical deliveries. */
    public void acceptVerified(String provider,String eventId,String type,String payload){
        if(layout()==Layout.LEGACY){acceptLegacy(provider,eventId,type,payload);return;}
        acceptVerified(provider,LEGACY_SCOPE,LEGACY_SCOPE,eventId,type,payload);
    }
    public void acceptVerified(String provider,String merchant,String environment,String eventId,String type,String payload){
        requireScoped();
        identity(provider,merchant,environment,eventId);
        if(type==null||!type.matches("[A-Za-z0-9_.]{1,96}")||payload==null||payload.length()>30000)throw new IllegalArgumentException("Invalid payment event");
        String fingerprint=hash(payload);
        try{
            tx.executeWithoutResult(t->{
                db.update("INSERT INTO payment_webhook_inbox(provider,merchant_scope,env_scope,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",provider,merchant,environment,eventId,type,payload,fingerprint);
                String id=UUID.nameUUIDFromBytes((provider+"\0"+merchant+"\0"+environment+"\0"+eventId).getBytes(StandardCharsets.UTF_8)).toString();
                events.publish("payment.inbox",id,id);
            });
        }catch(DuplicateKeyException duplicate){
            var prior=db.queryForMap("SELECT event_type,payload_hash FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=?",provider,merchant,environment,eventId);
            if(!type.equals(prior.get("event_type"))||!fingerprint.equals(prior.get("payload_hash")))throw new IllegalStateException("Conflicting payment event identity");
        }
    }
    public Optional<Delivery> claim(String provider){return layout()==Layout.LEGACY?claimLegacy(provider):claim(provider,LEGACY_SCOPE,LEGACY_SCOPE);}
    public Optional<Delivery> claim(String provider,String merchant,String environment){
        requireScoped();
        identity(provider,merchant,environment,"claim");
        var ids=db.query("SELECT event_id FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP) ORDER BY next_attempt_at,created_at,event_id LIMIT 20",(rs,n)->rs.getString(1),provider,merchant,environment);
        for(String id:ids){
            String token=UUID.randomUUID().toString();
            int changed=db.update("UPDATE payment_webhook_inbox SET status='PROCESSING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP),attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)",token,provider,merchant,environment,id);
            if(changed==1)return Optional.of(db.queryForObject("SELECT event_type,payload,attempts FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=?",(rs,n)->new Delivery(provider,id,rs.getString(1),rs.getString(2),token,rs.getInt(3),merchant,environment),provider,merchant,environment,id,token));
        }
        return Optional.empty();
    }
    /** Legacy nonfinancial compatibility. Financial callers use completeOwned, taking owners first. */
    public void complete(Delivery delivery,Runnable mutation){
        if(layout()==Layout.LEGACY){completeLegacy(delivery,List.of(),mutation);return;}
        finish(delivery,List.of(),mutation,null,false,false);
    }
    public void completeOwned(Delivery delivery,String owner,Runnable mutation){completeOwned(delivery,owner,mutation,null);}
    public void completeOwned(Delivery delivery,String owner,Runnable mutation,String evidence){finish(delivery,List.of(requireOwner(owner)),mutation,evidence,false,true);}
    public void completeOwned(Delivery delivery,Collection<String> owners,Runnable mutation){
        if(owners==null||owners.isEmpty())throw new IllegalArgumentException("Payment owners required");
        finish(delivery,owners,mutation,null,false,true);
    }
    public void waitForProvider(Delivery delivery,String owner,Runnable mutation,String evidence){
        if(evidence==null||evidence.isBlank())throw new IllegalArgumentException("Verified pending evidence required");
        finish(delivery,List.of(requireOwner(owner)),mutation,evidence,true,true);
    }
    private void finish(Delivery delivery,Collection<String> owners,Runnable mutation,String evidence,boolean pending,boolean audit){
        if(mutation==null||evidence!=null&&evidence.length()>512)throw new IllegalArgumentException("Invalid payment resolution");
        if(layout()==Layout.LEGACY){
            if(pending)requireScoped();
            completeLegacy(delivery,owners,mutation);return;
        }
        tx.executeWithoutResult(status->{
            // All financial writers take owner rows before inbox, orders and entitlement rows.
            for(String owner:new TreeSet<>(owners))if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",requireOwner(owner)).size()!=1)throw new IllegalStateException("Payment owner unresolved");
            var claimed=db.queryForList("SELECT status,created_at<=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) AS waiting_expired FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token());
            if(claimed.size()!=1||!"PROCESSING".equals(claimed.get(0).get("status")))return;
            mutation.run();
            if(pending){
                Object expired=claimed.get(0).get("waiting_expired");boolean deadline=Boolean.TRUE.equals(expired)||(expired instanceof Number n&&n.intValue()==1);
                String next=deadline?"MANUAL":"PENDING";
                if(db.update("UPDATE payment_webhook_inbox SET status=?,attempts=GREATEST(0,attempts-1),lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP),last_error=?,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",next,deadline?"PROVIDER_PENDING_EXPIRED":"PROVIDER_PENDING",delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token())!=1)throw new IllegalStateException("Payment event lease expired before commit");
                audit(delivery,deadline?"MANUAL":"WAITING",evidence);
            }else{
                if(db.update("UPDATE payment_webhook_inbox SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token())!=1)throw new IllegalStateException("Payment event lease expired before commit");
                if(audit)audit(delivery,"DONE",evidence);
            }
        });
    }
    public void renew(Delivery delivery){
        if(layout()==Layout.LEGACY){
            requireLegacyDelivery(delivery);
            if(db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP) WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.eventId(),delivery.token())!=1)throw new IllegalStateException("Payment event lease changed");
            return;
        }
        if(db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP) WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token())!=1)throw new IllegalStateException("Payment event lease changed");
    }
    public void retry(Delivery delivery){
        if(layout()==Layout.LEGACY){
            requireLegacyDelivery(delivery);
            long delay=Math.min(3600,5L<<Math.min(9,delivery.attempt()));
            db.update("UPDATE payment_webhook_inbox SET status='PENDING',lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),last_error='PROCESSING_FAILED',updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delay,delivery.provider(),delivery.eventId(),delivery.token());
            return;
        }
        tx.executeWithoutResult(s->{
            boolean exhausted=delivery.attempt()>=8;long delay=Math.min(3600,5L<<Math.min(9,delivery.attempt()));
            int changed=db.update("UPDATE payment_webhook_inbox SET status=?,lease_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP),last_error='PROCESSING_FAILED',updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",exhausted?"MANUAL":"PENDING",delay,delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token());
            if(changed==1)audit(delivery,exhausted?"MANUAL":"FAILED",null);
        });
    }
    /** Quarantine a verified pointer without claiming that the provider or order is resolved. */
    public void markManual(Delivery delivery,String reason){
        requireScoped();
        if(reason==null||!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Bounded manual review reason required");
        tx.executeWithoutResult(s->{
            if(db.update("UPDATE payment_webhook_inbox SET status='MANUAL',lease_token=NULL,lease_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",reason,delivery.provider(),delivery.merchant(),delivery.environment(),delivery.eventId(),delivery.token())!=1)
                throw new IllegalStateException("Payment event lease expired before manual quarantine");
            audit(delivery,"MANUAL",reason);
        });
    }
    /** Verified legacy channels without an automatic processor keep an auditable immutable pointer. */
    public void acceptManualVerified(String provider,String eventId,String type,String payload,String reason){
        requireScoped();
        if(reason==null||!reason.matches("[A-Z0-9_]{1,80}"))throw new IllegalArgumentException("Bounded manual review reason required");
        // Its duplicate-key transaction must finish before the quarantine transaction starts.
        // A crash between them preserves a PENDING pointer for the provider retry; no legacy processor consumes it.
        acceptVerified(provider,LEGACY_SCOPE,LEGACY_SCOPE,eventId,type,payload);
        tx.executeWithoutResult(s->{
            int changed=db.update("UPDATE payment_webhook_inbox SET status='MANUAL',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND status='PENDING' AND attempts=0 AND lease_token IS NULL",reason,provider,LEGACY_SCOPE,LEGACY_SCOPE,eventId);
            if(changed==1)audit(new Delivery(provider,eventId,type,payload,"",0,LEGACY_SCOPE,LEGACY_SCOPE),"MANUAL",reason);
            else if(!"MANUAL".equals(db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=?",String.class,provider,LEGACY_SCOPE,LEGACY_SCOPE,eventId)))
                throw new IllegalStateException("Verified legacy event was already processed outside manual quarantine");
        });
    }
    /** Internal finance operation only: an explicit immutable pointer fingerprint and reason are mandatory. */
    public void resumeReviewed(String provider,String merchant,String environment,String eventId,String expectedType,String expectedPayloadHash,String reviewer,String reason){
        requireScoped();
        identity(provider,merchant,environment,eventId);
        if(reviewer==null||reviewer.isBlank()||reviewer.length()>128||reason==null||reason.strip().length()<10||reason.length()>1000||expectedPayloadHash==null||!expectedPayloadHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Audited payment review required");
        tx.executeWithoutResult(s->{
            var row=db.queryForMap("SELECT status,event_type,payload_hash FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? FOR UPDATE",provider,merchant,environment,eventId);
            if(!"MANUAL".equals(row.get("status"))||!Objects.equals(expectedType,row.get("event_type"))||!expectedPayloadHash.equals(row.get("payload_hash")))throw new IllegalStateException("Payment review identity or state changed");
            db.update("INSERT INTO payment_inbox_review(id,provider,merchant_scope,env_scope,event_id,reviewer,reason,action,created_at) VALUES(?,?,?,?,?,?,?,'RESUME',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),provider,merchant,environment,eventId,reviewer,reason);
            db.update("UPDATE payment_webhook_inbox SET status='PENDING',attempts=0,lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=? AND status='MANUAL'",provider,merchant,environment,eventId);
        });
    }
    private void audit(Delivery d,String result,String evidence){db.update("INSERT INTO payment_inbox_attempt(id,provider,merchant_scope,env_scope,event_id,attempt,result,evidence_id,created_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),d.provider(),d.merchant(),d.environment(),d.eventId(),Math.max(0,d.attempt()),result,evidence);}
    private void acceptLegacy(String provider,String eventId,String type,String payload){
        identity(provider,LEGACY_SCOPE,LEGACY_SCOPE,eventId);
        if(type==null||!type.matches("[A-Za-z0-9_.]{1,96}")||payload==null||payload.length()>30000)throw new IllegalArgumentException("Invalid payment event");
        String fingerprint=hash(payload);
        try{
            tx.executeWithoutResult(t->{
                db.update("INSERT INTO payment_webhook_inbox(provider,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",provider,eventId,type,payload,fingerprint);
                String id=UUID.nameUUIDFromBytes((provider+":"+eventId).getBytes(StandardCharsets.UTF_8)).toString();
                events.publish("payment.inbox",id,id);
            });
        }catch(DuplicateKeyException duplicate){
            var prior=db.queryForMap("SELECT event_type,payload_hash FROM payment_webhook_inbox WHERE provider=? AND event_id=?",provider,eventId);
            if(!type.equals(prior.get("event_type"))||!fingerprint.equals(prior.get("payload_hash")))throw new IllegalStateException("Conflicting payment event identity");
        }
    }
    private Optional<Delivery> claimLegacy(String provider){
        identity(provider,LEGACY_SCOPE,LEGACY_SCOPE,"claim");
        var ids=db.query("SELECT event_id FROM payment_webhook_inbox WHERE provider=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP) ORDER BY next_attempt_at,created_at,event_id LIMIT 20",(rs,n)->rs.getString(1),provider);
        for(String id:ids){
            String token=UUID.randomUUID().toString();
            int changed=db.update("UPDATE payment_webhook_inbox SET status='PROCESSING',lease_token=?,lease_until=TIMESTAMPADD(SECOND,180,CURRENT_TIMESTAMP),attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND status IN ('PENDING','PROCESSING') AND next_attempt_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<=CURRENT_TIMESTAMP)",token,provider,id);
            if(changed==1)return Optional.of(db.queryForObject("SELECT event_type,payload,attempts FROM payment_webhook_inbox WHERE provider=? AND event_id=? AND lease_token=?",(rs,n)->new Delivery(provider,id,rs.getString(1),rs.getString(2),token,rs.getInt(3)),provider,id,token));
        }
        return Optional.empty();
    }
    private void completeLegacy(Delivery delivery,Collection<String> owners,Runnable mutation){
        requireLegacyDelivery(delivery);
        if(mutation==null)throw new IllegalArgumentException("Invalid payment resolution");
        tx.executeWithoutResult(status->{
            for(String owner:new TreeSet<>(owners))if(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",requireOwner(owner)).size()!=1)throw new IllegalStateException("Payment owner unresolved");
            var claimed=db.query("SELECT status FROM payment_webhook_inbox WHERE provider=? AND event_id=? AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",(rs,n)->rs.getString(1),delivery.provider(),delivery.eventId(),delivery.token());
            if(claimed.size()!=1||!"PROCESSING".equals(claimed.get(0)))return;
            mutation.run();
            if(db.update("UPDATE payment_webhook_inbox SET status='DONE',lease_token=NULL,lease_until=NULL,last_error=NULL,updated_at=CURRENT_TIMESTAMP WHERE provider=? AND event_id=? AND lease_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP",delivery.provider(),delivery.eventId(),delivery.token())!=1)throw new IllegalStateException("Payment event lease expired before commit");
        });
    }
    private static void requireLegacyDelivery(Delivery d){
        if(d==null||!LEGACY_SCOPE.equals(d.merchant())||!LEGACY_SCOPE.equals(d.environment()))throw new IllegalStateException("Scoped payment delivery requires the complete scoped inbox layout");
    }
    private void requireScoped(){if(layout()!=Layout.SCOPED)throw new IllegalStateException("Scoped payment processing requires the complete scoped inbox layout");}
    private Layout layout(){
        Layout known=layout;if(known!=null)return known;
        synchronized(this){if(layout==null)layout=detectLayout();return layout;}
    }
    private Layout detectLayout(){
        String product=db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)c->c.getMetaData().getDatabaseProductName());
        String schema;
        if("H2".equals(product))schema="CURRENT_SCHEMA()";
        else if("MySQL".equals(product))schema="DATABASE()";
        else throw new IllegalStateException("Unsupported payment inbox database");
        var columns=new HashMap<String,Set<String>>();
        db.query("SELECT LOWER(table_name),LOWER(column_name) FROM information_schema.columns WHERE LOWER(table_schema)=LOWER("+schema+") AND LOWER(table_name) IN ('payment_webhook_inbox','payment_inbox_attempt','payment_inbox_review')",rs->{columns.computeIfAbsent(rs.getString(1),k->new HashSet<>()).add(rs.getString(2));});
        var inboxColumns=columns.getOrDefault("payment_webhook_inbox",Set.of());
        if(!inboxColumns.containsAll(Set.of("provider","event_id","event_type","payload","payload_hash","status","attempts","next_attempt_at","lease_token","lease_until","last_error","created_at","updated_at")))throw new IllegalStateException("Incomplete payment inbox layout");
        var primaryKey=db.query("SELECT LOWER(k.column_name) FROM information_schema.table_constraints t JOIN information_schema.key_column_usage k ON t.constraint_schema=k.constraint_schema AND t.table_name=k.table_name AND t.constraint_name=k.constraint_name WHERE LOWER(t.table_schema)=LOWER("+schema+") AND LOWER(t.table_name)='payment_webhook_inbox' AND t.constraint_type='PRIMARY KEY' ORDER BY k.ordinal_position",(rs,n)->rs.getString(1));
        boolean merchant=inboxColumns.contains("merchant_scope"),environment=inboxColumns.contains("env_scope");
        boolean attempt=columns.containsKey("payment_inbox_attempt"),review=columns.containsKey("payment_inbox_review");
        if(!merchant&&!environment&&!attempt&&!review&&primaryKey.equals(List.of("provider","event_id")))return Layout.LEGACY;
        if(merchant&&environment&&attempt&&review&&primaryKey.equals(List.of("provider","merchant_scope","env_scope","event_id"))
                &&columns.get("payment_inbox_attempt").containsAll(Set.of("id","provider","merchant_scope","env_scope","event_id","attempt","result","evidence_id","created_at"))
                &&columns.get("payment_inbox_review").containsAll(Set.of("id","provider","merchant_scope","env_scope","event_id","reviewer","reason","action","created_at")))return Layout.SCOPED;
        throw new IllegalStateException("Partial or incompatible payment inbox layout");
    }
    private static String requireOwner(String owner){if(owner==null||owner.isBlank()||owner.length()>128)throw new IllegalArgumentException("Payment owner required");return owner;}
    private static void identity(String provider,String merchant,String environment,String eventId){
        if(provider==null||!provider.matches("[A-Z_]{1,24}")||merchant==null||!merchant.matches("[A-Za-z0-9_.:-]{1,128}")||environment==null||!environment.matches("[A-Za-z0-9_.-]{1,16}")||eventId==null||!eventId.matches("[A-Za-z0-9_.-]{1,128}"))throw new IllegalArgumentException("Invalid payment identity");
    }
    private static String hash(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
