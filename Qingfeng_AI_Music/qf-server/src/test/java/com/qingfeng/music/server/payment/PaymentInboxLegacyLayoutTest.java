package com.qingfeng.music.server.payment;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** H2 SQL compatibility contracts for the original core40 inbox, not MySQL or payment acceptance. */
class PaymentInboxLegacyLayoutTest {
    static final String PROVIDER="PAYPAL_SANDBOX",OWNER="legacy-owner";
    record Fixture(JdbcTemplate db,TransactionTemplate tx,PaymentInbox inbox,List<String> reads) {}
    Fixture fixture(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:legacy_inbox_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        var reads=new ArrayList<String>();
        var db=new JdbcTemplate(ds){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args){reads.add(sql);return super.queryForList(sql,args);}
            @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args){reads.add(sql);return super.query(sql,mapper,args);}
        };
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        // Construction precedes DDL: detection must be lazy, just as existing inbox contract fixtures.
        var inbox=new PaymentInbox(db,tx);
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL)");
        db.update("INSERT INTO creator(id,balance) VALUES(?,100)",OWNER);
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24) NOT NULL,event_id VARCHAR(128) NOT NULL,event_type VARCHAR(96) NOT NULL,payload TEXT NOT NULL,payload_hash VARCHAR(64) NOT NULL,status VARCHAR(20) NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TIMESTAMP(6) NOT NULL,lease_token VARCHAR(36),lease_until TIMESTAMP(6),last_error VARCHAR(80),created_at TIMESTAMP(6) NOT NULL,updated_at TIMESTAMP(6) NOT NULL,PRIMARY KEY(provider,event_id))");
        return new Fixture(db,tx,inbox,reads);
    }
    void accept(Fixture f,String id){f.inbox().acceptVerified(PROVIDER,id,"PAYMENT.CAPTURE.COMPLETED","{\"capture\":\"synthetic-legacy\"}");}
    PaymentInbox.Delivery claim(Fixture f){return f.inbox().claim(PROVIDER).orElseThrow();}
    String state(Fixture f){return f.db().queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider=?",String.class,PROVIDER);}
    long balance(Fixture f){return f.db().queryForObject("SELECT balance FROM creator WHERE id=?",Long.class,OWNER);}
    int count(Fixture f){return f.db().queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Integer.class);}
    void due(Fixture f){f.db().update("UPDATE payment_webhook_inbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");}
    void expire(Fixture f){f.db().update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");}
    PaymentInbox.Delivery scoped(PaymentInbox.Delivery d){return new PaymentInbox.Delivery(d.provider(),d.eventId(),d.type(),d.payload(),d.token(),d.attempt(),"acct_foreign","sandbox");}

    @Test void originalPointerIdempotencyProviderNamespaceAndOutboxIdentityArePreserved(){
        var f=fixture();var wakes=new ArrayList<String>();
        var inbox=new PaymentInbox(f.db(),f.tx(),(topic,key,payload)->{assertEquals("payment.inbox",topic);assertEquals(key,payload);wakes.add(key);});
        inbox.acceptVerified(PROVIDER,"evt_same","TEST.EVENT","{}");inbox.acceptVerified(PROVIDER,"evt_same","TEST.EVENT","{}");
        inbox.acceptVerified("AIRWALLEX","evt_same","TEST.EVENT","{}");
        assertEquals(2,count(f));assertEquals(2,wakes.size());
        assertEquals(UUID.nameUUIDFromBytes((PROVIDER+":evt_same").getBytes(StandardCharsets.UTF_8)).toString(),wakes.get(0));
        assertNotEquals(wakes.get(0),wakes.get(1));
        assertThrows(IllegalStateException.class,()->inbox.acceptVerified(PROVIDER,"evt_same","TEST.EVENT","{\"changed\":true}"));
        assertThrows(IllegalStateException.class,()->inbox.acceptVerified(PROVIDER,"evt_same","DIFFERENT.EVENT","{}"));
        assertEquals("{}",f.db().queryForObject("SELECT payload FROM payment_webhook_inbox WHERE provider=?",String.class,PROVIDER));
        assertEquals("TEST.EVENT",f.db().queryForObject("SELECT event_type FROM payment_webhook_inbox WHERE provider=?",String.class,PROVIDER));
    }
    @Test void originalInboxAndOutboxStillRollBackAsOneTransactionWithoutAuditTables(){
        var f=fixture();var failing=new PaymentInbox(f.db(),f.tx(),(topic,key,payload)->{throw new IllegalStateException("synthetic outbox failure");});
        assertThrows(IllegalStateException.class,()->failing.acceptVerified(PROVIDER,"evt_atomic","TEST.EVENT","{}"));
        assertEquals(0,count(f));assertEquals(100,balance(f));
        accept(f,"evt_atomic");assertEquals(1,count(f));
    }
    @Test void originalClaimAndCompleteMutateOnlyOnceAndKeepTheSixArgumentDeliveryContract(){
        var f=fixture();accept(f,"evt_complete");var d=claim(f);
        assertEquals(PaymentInbox.LEGACY_SCOPE,d.merchant());assertEquals(PaymentInbox.LEGACY_SCOPE,d.environment());assertEquals(1,d.attempt());
        assertTrue(f.inbox().claim(PROVIDER).isEmpty());
        f.inbox().complete(d,()->f.db().update("UPDATE creator SET balance=balance+100 WHERE id=?",OWNER));
        f.inbox().complete(d,()->fail("Completed original pointer must not apply twice"));
        accept(f,"evt_complete");assertEquals(200,balance(f));assertEquals("DONE",state(f));assertTrue(f.inbox().claim(PROVIDER).isEmpty());
    }
    @Test void legacyOwnedCompletionTakesOwnerBeforeInboxAndRetainsAtomicRollback(){
        var f=fixture();accept(f,"evt_owned");var d=claim(f);f.reads().clear();
        assertThrows(IllegalStateException.class,()->f.inbox().completeOwned(d,OWNER,()->{f.db().update("UPDATE creator SET balance=0 WHERE id=?",OWNER);throw new IllegalStateException("synthetic crash");},"synthetic-verified-evidence"));
        assertEquals("SELECT id FROM creator WHERE id=? FOR UPDATE",f.reads().get(0));
        int inboxIndex=-1;for(int i=0;i<f.reads().size();i++)if(f.reads().get(i).startsWith("SELECT status FROM payment_webhook_inbox")){inboxIndex=i;break;}
        assertTrue(inboxIndex>0,"Owner must be locked before the inbox row");
        assertEquals(100,balance(f));assertEquals("PROCESSING",state(f));
        assertThrows(IllegalStateException.class,()->f.inbox().completeOwned(d,"missing-owner",()->fail("Unresolved owner must never mutate")));
        f.inbox().completeOwned(d,List.of(OWNER),()->f.db().update("UPDATE creator SET balance=balance+100 WHERE id=?",OWNER));
        assertEquals(200,balance(f));assertEquals("DONE",state(f));
    }
    @Test void leaseLostDuringLegacyMutationRollsBackBothBusinessChangeAndCompletion(){
        var f=fixture();accept(f,"evt_expire_in_commit");var d=claim(f);
        assertThrows(IllegalStateException.class,()->f.inbox().complete(d,()->{f.db().update("UPDATE creator SET balance=0 WHERE id=?",OWNER);expire(f);}));
        assertEquals(100,balance(f));assertEquals("PROCESSING",state(f));
        f.inbox().renew(d);f.inbox().complete(d,()->{});assertEquals("DONE",state(f));
    }
    @Test void core40RetryPreservesOriginalPendingBackoffEvenBeyondTheNewAuditBudget(){
        var f=fixture();accept(f,"evt_retry");var created=f.db().queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class);
        for(int i=1;i<=10;i++){
            var d=claim(f);assertEquals(i,d.attempt());f.inbox().retry(d);
            assertEquals("PENDING",state(f));assertEquals("PROCESSING_FAILED",f.db().queryForObject("SELECT last_error FROM payment_webhook_inbox",String.class));
            assertNull(f.db().queryForObject("SELECT lease_token FROM payment_webhook_inbox",String.class));
            assertTrue(f.inbox().claim(PROVIDER).isEmpty());due(f);
        }
        assertEquals(created,f.db().queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class));
        var finalAttempt=claim(f);assertEquals(11,finalAttempt.attempt());f.inbox().complete(finalAttempt,()->{});assertEquals("DONE",state(f));
    }
    @Test void expiredAndSupersededLegacyLeasesCannotRenewRetryOrRunMutations(){
        var f=fixture();accept(f,"evt_lease");var old=claim(f);expire(f);
        f.inbox().complete(old,()->fail("Expired lease must not mutate"));assertThrows(IllegalStateException.class,()->f.inbox().renew(old));
        f.inbox().retry(old);assertEquals("PROCESSING",state(f));assertEquals(old.token(),f.db().queryForObject("SELECT lease_token FROM payment_webhook_inbox",String.class));
        var fresh=claim(f);assertNotEquals(old.token(),fresh.token());assertEquals(2,fresh.attempt());
        f.inbox().completeOwned(old,OWNER,()->fail("Superseded lease must not mutate"));f.inbox().retry(old);assertThrows(IllegalStateException.class,()->f.inbox().renew(old));
        f.inbox().renew(fresh);f.inbox().completeOwned(fresh,OWNER,()->f.db().update("UPDATE creator SET balance=balance+100 WHERE id=?",OWNER));
        assertEquals(200,balance(f));assertEquals("DONE",state(f));
    }
    @Test void newScopedWaitAndReviewedResumeOperationsFailClosedInAnOldSchema(){
        var f=fixture();accept(f,"evt_scoped");var d=claim(f);
        assertThrows(IllegalStateException.class,()->f.inbox().acceptVerified(PROVIDER,"acct_test","sandbox","evt_foreign","TEST.EVENT","{}"));
        assertThrows(IllegalStateException.class,()->f.inbox().acceptVerified(PROVIDER,PaymentInbox.LEGACY_SCOPE,PaymentInbox.LEGACY_SCOPE,"evt_explicit_scope","TEST.EVENT","{}"));
        assertThrows(IllegalStateException.class,()->f.inbox().claim(PROVIDER,"acct_test","sandbox"));
        assertThrows(IllegalStateException.class,()->f.inbox().waitForProvider(d,OWNER,()->fail("Pending entitlement must not downgrade to legacy"),"pending"));
        assertThrows(IllegalStateException.class,()->f.inbox().resumeReviewed(PROVIDER,"acct_test","sandbox",d.eventId(),d.type(),"0".repeat(64),"reviewer","Verified original pointer for review"));
        assertEquals(1,count(f));assertEquals("PROCESSING",state(f));assertEquals(100,balance(f));
    }
    @Test void scopedDeliveryCannotUseAnyLegacyCompletionLeaseOrRetryOperation(){
        var f=fixture();accept(f,"evt_foreign_delivery");var d=claim(f);var foreign=scoped(d);
        assertThrows(IllegalStateException.class,()->f.inbox().complete(foreign,()->fail("Foreign scope")));
        assertThrows(IllegalStateException.class,()->f.inbox().completeOwned(foreign,OWNER,()->fail("Foreign scope")));
        assertThrows(IllegalStateException.class,()->f.inbox().completeOwned(foreign,List.of(OWNER),()->fail("Foreign scope")));
        assertThrows(IllegalStateException.class,()->f.inbox().renew(foreign));assertThrows(IllegalStateException.class,()->f.inbox().retry(foreign));
        assertEquals(d.token(),f.db().queryForObject("SELECT lease_token FROM payment_webhook_inbox",String.class));assertEquals("PROCESSING",state(f));assertEquals(100,balance(f));
    }
    @Test void partialScopeAuditOrPrimaryKeyLayoutsRejectBeforeAcceptingOrClaimingAnyPointer(){
        var changes=List.of(
                List.of("ALTER TABLE payment_webhook_inbox ADD merchant_scope VARCHAR(128) DEFAULT 'LEGACY_UNKNOWN'"),
                List.of("ALTER TABLE payment_webhook_inbox ADD merchant_scope VARCHAR(128) DEFAULT 'LEGACY_UNKNOWN'","ALTER TABLE payment_webhook_inbox ADD env_scope VARCHAR(16) DEFAULT 'LEGACY_UNKNOWN'"),
                List.of("CREATE TABLE payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY)"),
                List.of("CREATE TABLE payment_inbox_review(id VARCHAR(36) PRIMARY KEY)"),
                List.of("ALTER TABLE payment_webhook_inbox DROP PRIMARY KEY","ALTER TABLE payment_webhook_inbox ADD PRIMARY KEY(event_id,provider)"));
        for(var ddl:changes){
            var f=fixture();for(String sql:ddl)f.db().execute(sql);
            assertThrows(IllegalStateException.class,()->accept(f,"evt_partial"),ddl.toString());
            assertThrows(IllegalStateException.class,()->f.inbox().claim(PROVIDER),ddl.toString());
            assertEquals(0,count(f));assertEquals(100,balance(f));
        }
    }
    @Test void layoutDetectionUsesOnlyTheCurrentSchemaAndIgnoresSimilarlyNamedForeignTables(){
        var f=fixture();f.db().execute("CREATE SCHEMA unrelated_scoped_schema");
        f.db().execute("CREATE TABLE unrelated_scoped_schema.payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        f.db().execute("CREATE TABLE unrelated_scoped_schema.payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY)");
        f.db().execute("CREATE TABLE unrelated_scoped_schema.payment_inbox_review(id VARCHAR(36) PRIMARY KEY)");
        accept(f,"evt_current_schema");var d=claim(f);f.inbox().completeOwned(d,OWNER,()->f.db().update("UPDATE creator SET balance=balance+100 WHERE id=?",OWNER));
        assertEquals("DONE",state(f));assertEquals(200,balance(f));assertEquals(0,f.db().queryForObject("SELECT COUNT(*) FROM unrelated_scoped_schema.payment_webhook_inbox",Integer.class));
    }
}
