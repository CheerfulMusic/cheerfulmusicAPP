package com.qingfeng.music.server.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Executed H2 inbox-contract checks only; these are not MySQL/Flyway or supplier acceptance. */
class PaymentInboxScopeTest {
    JdbcTemplate db;TransactionTemplate tx;PaymentInbox inbox;
    static final String PROVIDER="STRIPE_SANDBOX",MERCHANT="acct_offline12345678",ENV="sandbox",OWNER="owner-offline";
    @BeforeEach void isolatedFixture(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:inbox_scope_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));inbox=new PaymentInbox(db,tx);
        db.execute("CREATE TABLE creator(id VARCHAR(128) PRIMARY KEY,balance BIGINT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128) DEFAULT 'LEGACY_UNKNOWN',env_scope VARCHAR(16) DEFAULT 'LEGACY_UNKNOWN',event_id VARCHAR(128),event_type VARCHAR(96),payload VARCHAR(30000),payload_hash VARCHAR(64),status VARCHAR(24),attempts INT,next_attempt_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,lease_token VARCHAR(36),lease_until TIMESTAMP,last_error VARCHAR(128),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        db.execute("CREATE TABLE payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),attempt INT,result VARCHAR(24),evidence_id VARCHAR(512),created_at TIMESTAMP)");
        db.execute("CREATE TABLE payment_inbox_review(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),reviewer VARCHAR(128),reason VARCHAR(1000),action VARCHAR(24),created_at TIMESTAMP)");
        db.update("INSERT INTO creator(id,balance) VALUES(?,100)",OWNER);
    }
    void accept(String id){inbox.acceptVerified(PROVIDER,MERCHANT,ENV,id,"refund.updated","{\"resource\":\"re_offline\"}");}
    PaymentInbox.Delivery claim(){return inbox.claim(PROVIDER,MERCHANT,ENV).orElseThrow();}
    String state(String id){return db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=?",String.class,PROVIDER,MERCHANT,ENV,id);}
    void due(String id){db.update("UPDATE payment_webhook_inbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE provider=? AND merchant_scope=? AND env_scope=? AND event_id=?",PROVIDER,MERCHANT,ENV,id);}
    long balance(){return db.queryForObject("SELECT balance FROM creator WHERE id=?",Long.class,OWNER);}
    int count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @Test void manualReviewAlertIsEmittedOnlyAfterTheQuarantineCommits(){
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(PaymentInbox.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try{
            accept("evt_manual_commit");var delivery=claim();
            tx.executeWithoutResult(status->{inbox.markManual(delivery,"TEST_REVIEW");status.setRollbackOnly();});
            assertTrue(appender.list.isEmpty());assertEquals("PROCESSING",state("evt_manual_commit"));
            inbox.markManual(delivery,"TEST_REVIEW");assertEquals(1,appender.list.size());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("payment_manual_review"));assertEquals("MANUAL",state("evt_manual_commit"));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void repeatedWorkerCrashesEventuallyQuarantineWithoutAnyFinancialMutation(){
        accept("evt_crash_loop");
        for(int n=1;n<=8;n++){
            var delivery=claim();assertEquals(n,delivery.attempt());
            db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE event_id='evt_crash_loop'");
        }
        assertTrue(inbox.claim(PROVIDER,MERCHANT,ENV).isEmpty());
        assertEquals("MANUAL",state("evt_crash_loop"));assertEquals(100,balance());
        assertEquals("LEASE_RECOVERY_EXHAUSTED",db.queryForObject("SELECT last_error FROM payment_webhook_inbox WHERE event_id='evt_crash_loop'",String.class));
        assertEquals(1,count("payment_inbox_attempt"));
    }
    @Test void identicalEventIdsRemainSeparateAcrossMerchantEnvironmentAndLegacy(){
        accept("evt_same");
        inbox.acceptVerified(PROVIDER,"acct_other12345678",ENV,"evt_same","refund.updated","{}");
        inbox.acceptVerified(PROVIDER,MERCHANT,"live","evt_same","refund.updated","{}");
        inbox.acceptVerified(PROVIDER,"evt_same","refund.updated","{}");
        assertEquals(4,count("payment_webhook_inbox"));
        var legacy=inbox.claim(PROVIDER).orElseThrow();assertEquals(PaymentInbox.LEGACY_SCOPE,legacy.merchant());
        inbox.complete(legacy,()->{});assertEquals("PENDING",state("evt_same"));
        var scoped=claim();assertEquals(MERCHANT,scoped.merchant());assertEquals(ENV,scoped.environment());
        inbox.completeOwned(scoped,OWNER,()->{},"verified-offline");assertEquals("DONE",state("evt_same"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE status='PENDING'",Integer.class));
    }
    @Test void duplicatesCannotReplacePointersAndOutboxFailureRollsBackAcceptance(){
        accept("evt_duplicate");accept("evt_duplicate");assertEquals(1,count("payment_webhook_inbox"));
        assertThrows(IllegalStateException.class,()->inbox.acceptVerified(PROVIDER,MERCHANT,ENV,"evt_duplicate","refund.updated","{\"different\":true}"));
        var failing=new PaymentInbox(db,tx,(topic,key,payload)->{throw new IllegalStateException("synthetic outbox failure");});
        assertThrows(IllegalStateException.class,()->failing.acceptVerified(PROVIDER,MERCHANT,ENV,"evt_atomic","refund.updated","{}"));
        assertEquals(1,count("payment_webhook_inbox"));
    }
    @Test void repeatedProviderPendingPreservesTechnicalBudgetAndRecordsEveryWait(){
        accept("evt_pending");var created=db.queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class);
        for(int i=0;i<10;i++){
            var d=claim();assertEquals(1,d.attempt());inbox.waitForProvider(d,OWNER,()->{},"refund-pending");
            assertEquals("PENDING",state("evt_pending"));assertEquals(0,db.queryForObject("SELECT attempts FROM payment_webhook_inbox",Integer.class));due("evt_pending");
        }
        assertEquals(created,db.queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class));
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='WAITING'",Integer.class));assertEquals(100,balance());
        assertEquals(0,count("payment_inbox_review"));
    }
    @Test void pendingAbsoluteSevenDayDeadlineBecomesManualWithoutGuessingFinancialLoss(){
        accept("evt_aged");db.update("UPDATE payment_webhook_inbox SET created_at=TIMESTAMPADD(DAY,-8,CURRENT_TIMESTAMP)");
        var created=db.queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class);accept("evt_aged");
        inbox.waitForProvider(claim(),OWNER,()->{},"unknown-final-funds");
        assertEquals("MANUAL",state("evt_aged"));assertEquals(100,balance());assertEquals(0,db.queryForObject("SELECT attempts FROM payment_webhook_inbox",Integer.class));
        assertEquals("PROVIDER_PENDING_EXPIRED",db.queryForObject("SELECT last_error FROM payment_webhook_inbox",String.class));
        assertEquals(created,db.queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class));assertTrue(inbox.claim(PROVIDER,MERCHANT,ENV).isEmpty());
        assertEquals("MANUAL",db.queryForObject("SELECT result FROM payment_inbox_attempt",String.class));
    }
    @Test void technicalFailuresStopAtEightAndAuditedResumeRequiresTheExactOriginalPointer() throws Exception {
        accept("evt_failed");
        for(int i=1;i<=8;i++){var d=claim();assertEquals(i,d.attempt());inbox.retry(d);due("evt_failed");}
        assertEquals("MANUAL",state("evt_failed"));assertTrue(inbox.claim(PROVIDER,MERCHANT,ENV).isEmpty());
        assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='FAILED'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='MANUAL'",Integer.class));
        var row=db.queryForMap("SELECT payload_hash,created_at FROM payment_webhook_inbox");String fingerprint=(String)row.get("payload_hash");
        assertThrows(IllegalArgumentException.class,()->inbox.resumeReviewed(PROVIDER,MERCHANT,ENV,"evt_failed","refund.updated",fingerprint,"reviewer","short"));
        assertThrows(IllegalStateException.class,()->inbox.resumeReviewed(PROVIDER,MERCHANT,ENV,"evt_failed","refund.updated","0".repeat(64),"reviewer","Verified original supplier event pointer"));
        assertEquals(0,count("payment_inbox_review"));assertEquals("MANUAL",state("evt_failed"));
        inbox.resumeReviewed(PROVIDER,MERCHANT,ENV,"evt_failed","refund.updated",fingerprint,"reviewer","Verified original supplier event pointer");
        assertEquals("PENDING",state("evt_failed"));assertEquals(0,db.queryForObject("SELECT attempts FROM payment_webhook_inbox",Integer.class));
        assertEquals(row.get("created_at"),db.queryForObject("SELECT created_at FROM payment_webhook_inbox",java.sql.Timestamp.class));
        assertEquals("RESUME",db.queryForObject("SELECT action FROM payment_inbox_review",String.class));assertEquals("reviewer",db.queryForObject("SELECT reviewer FROM payment_inbox_review",String.class));
    }
    @Test void expiredAndReplacedLeasesCannotApplyFinancialMutations(){
        accept("evt_lease");var stale=claim();
        db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        inbox.completeOwned(stale,OWNER,()->fail("Expired owner mutation"),"proof");assertThrows(IllegalStateException.class,()->inbox.renew(stale));
        inbox.retry(stale);assertEquals(0,count("payment_inbox_attempt"));
        var fresh=claim();assertNotEquals(stale.token(),fresh.token());
        inbox.waitForProvider(stale,OWNER,()->fail("Replaced owner mutation"),"pending");
        inbox.completeOwned(fresh,OWNER,()->db.update("UPDATE creator SET balance=balance-20 WHERE id=?",OWNER),"verified-refund");
        assertEquals(80,balance());assertEquals("DONE",state("evt_lease"));assertEquals(1,count("payment_inbox_attempt"));
    }
    @Test void mutationAndAuditFailureRollBackFinancialStateAndLeaseCompletion(){
        accept("evt_rollback");var d=claim();
        assertThrows(IllegalStateException.class,()->inbox.completeOwned(d,OWNER,()->{db.update("UPDATE creator SET balance=0 WHERE id=?",OWNER);throw new IllegalStateException("synthetic rollback");},"proof"));
        assertEquals(100,balance());assertEquals("PROCESSING",state("evt_rollback"));assertEquals(0,count("payment_inbox_attempt"));
        db.execute("ALTER TABLE payment_inbox_attempt ADD CONSTRAINT reject_done CHECK(result<>'DONE')");
        assertThrows(RuntimeException.class,()->inbox.completeOwned(d,OWNER,()->db.update("UPDATE creator SET balance=0 WHERE id=?",OWNER),"proof"));
        assertEquals(100,balance());assertEquals("PROCESSING",state("evt_rollback"));
    }
    @Test void pendingThenVerifiedSuccessMutatesOnlyOnceAcrossNewServiceInstances(){
        accept("evt_recovered");inbox.waitForProvider(claim(),OWNER,()->{},"pending");due("evt_recovered");
        inbox=new PaymentInbox(db,tx);var d=claim();inbox.completeOwned(d,OWNER,()->db.update("UPDATE creator SET balance=balance-20 WHERE id=?",OWNER),"authoritative-success");
        inbox.completeOwned(d,OWNER,()->fail("Completed delivery must not repeat"),"authoritative-success");accept("evt_recovered");
        assertEquals(80,balance());assertEquals("DONE",state("evt_recovered"));assertTrue(inbox.claim(PROVIDER,MERCHANT,ENV).isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='WAITING'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='DONE'",Integer.class));
    }
    @Test void simultaneousClaimsProduceOneActiveTokenAndOneFinancialMutation() throws Exception {
        accept("evt_concurrent");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var claims=new ArrayList<Future<Optional<PaymentInbox.Delivery>>>();
        try{
            for(int i=0;i<2;i++)claims.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));return inbox.claim(PROVIDER,MERCHANT,ENV);}));
            start.countDown();var deliveries=new ArrayList<PaymentInbox.Delivery>();for(var future:claims)future.get(10,TimeUnit.SECONDS).ifPresent(deliveries::add);
            assertEquals(1,deliveries.size());inbox.completeOwned(deliveries.get(0),OWNER,()->db.update("UPDATE creator SET balance=balance-20 WHERE id=?",OWNER),"verified-once");
            assertEquals(80,balance());assertEquals("DONE",state("evt_concurrent"));assertEquals(1,count("payment_inbox_attempt"));
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void unresolvedOwnerOrForeignScopeCannotApplyTheClaimedFinancialMutation(){
        accept("evt_owner");var d=claim();
        assertThrows(IllegalStateException.class,()->inbox.completeOwned(d,"missing-owner",()->fail("Unknown owner"),"proof"));
        var foreign=new PaymentInbox.Delivery(d.provider(),d.eventId(),d.type(),d.payload(),d.token(),d.attempt(),"acct_foreign12345678",d.environment());
        inbox.completeOwned(foreign,OWNER,()->fail("Foreign merchant"),"proof");assertEquals("PROCESSING",state("evt_owner"));assertEquals(100,balance());
        assertEquals(0,count("payment_inbox_attempt"));
    }
}
