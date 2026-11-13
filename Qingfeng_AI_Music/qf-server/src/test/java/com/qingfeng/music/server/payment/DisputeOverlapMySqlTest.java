package com.qingfeng.music.server.payment;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated InnoDB/Flyway tests. Synthetic funding facts; no provider calls. */
class DisputeOverlapMySqlTest {
    private static final String PROVIDER="STRIPE", MERCHANT="acct_overlap_fixture", ENV="sandbox";
    private static final Set<String> OWNERS=ConcurrentHashMap.newKeySet();
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static JdbcFencedCreditsLedger ledger;
    private record Paid(String owner,String order,String capture){}
    private static String required(String key){
        String value=System.getenv("P0_DISPUTE_OVERLAP_MYSQL_"+key);
        if(value==null||value.isBlank())throw new IllegalStateException("Explicit isolated overlap MySQL required: "+key);
        return value;
    }
    private static long n(String sql,Object...args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    private static String key(){return UUID.randomUUID().toString();}
    @BeforeAll static void migrateOnlyVerifiedFreshIsolatedDatabase() throws Exception {
        String database=required("DATABASE"),url=required("URL");
        if(!database.matches("p0_dispute_overlap_20261113r[0-9]+")||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+database+"(?:\\?.*)?"))
            throw new IllegalStateException("Dedicated new overlap schema and isolated port required");
        var source=new DriverManagerDataSource(url,required("USER"),required("PASSWORD"));
        db=new JdbcTemplate(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            try(var identity=statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE(),@@transaction_isolation")){
                assertTrue(identity.next());assertEquals("8.0.46",identity.getString(1));
                assertEquals("05c27d3c-dda3-11f1-aba0-84a938421519",identity.getString(2));assertEquals(required("UUID"),identity.getString(2));
                assertEquals("r:/data/",identity.getString(3).replace('\\','/').toLowerCase(Locale.ROOT));
                assertEquals(23316,identity.getInt(4));assertEquals("127.0.0.1",identity.getString(5));assertEquals(database,identity.getString(6));
                assertEquals("NONE",identity.getString(7));assertEquals("REPEATABLE-READ",identity.getString(8));
            }
            int grants=0;try(var rows=statement.executeQuery("SHOW GRANTS")){while(rows.next()){
                String grant=rows.getString(1);assertTrue(grant.startsWith("GRANT USAGE ON *.*")||grant.contains("ON `"+database+"`.*"));grants++;
            }}assertEquals(2,grants);
            try(var rows=statement.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")){while(rows.next())
                assertTrue(Set.of(database,"information_schema","performance_schema").contains(rows.getString(1)));}
        }
        assertEquals(0,n("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Do not reuse or clean old cohorts");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("43").load().migrate();
        assertEquals(43,n("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("44").load().migrate();
        assertEquals(44,n("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        assertEquals(789542731,n("SELECT checksum FROM flyway_schema_history WHERE version='44'"));
        assertEquals(1,db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP,evidence='SYNTHETIC_DISPUTE_OVERLAP' WHERE id=1 AND status='PREPARING'"));
        tx=new TransactionTemplate(new JdbcTransactionManager(source));restartLedger();
    }
    private static void restartLedger(){ledger=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);}
    private static Paid paid(){String owner=key();db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",owner);return paid(owner);}
    private static Paid paid(String owner){
        String order=key(),capture="pi_"+key();OWNERS.add(owner);
        tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_capture_id) VALUES(?,?,'starter-USD',9.90,'USD',100,'PAID',CURRENT_TIMESTAMP,'STRIPE_SANDBOX',?)",order,owner,capture);
            ledger.credit(owner,100,"payment:"+order);ledger.bindPayment(PROVIDER,MERCHANT,ENV,owner,order,capture,"USD");
        });return new Paid(owner,order,capture);
    }
    private static void refund(Paid p,String refund,long amount){ledger.paymentRefund(PROVIDER,MERCHANT,ENV,refund,p.owner(),p.order(),p.capture(),amount,"USD");}
    private static void dispute(Paid p,String dispute,long version,String state,Long loss){ledger.paymentDispute(PROVIDER,MERCHANT,ENV,dispute,p.owner(),p.order(),p.capture(),198,"USD",version,state,loss);}
    private static long held(Paid p){return n("SELECT held FROM p0_credit_batch WHERE reference=?","payment:"+p.order());}
    private static long adjusted(Paid p){return n("SELECT adjusted FROM p0_credit_batch WHERE reference=?","payment:"+p.order());}
    private static long reviews(Paid p){return n("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'LOST_OVERLAP:%'",p.order());}
    private static void unknown(Paid p,String dispute,long expectedHeld){
        var row=db.queryForMap("SELECT * FROM p0_credit_dispute WHERE external_id=?",dispute);
        assertEquals(p.order(),row.get("order_id"));assertEquals("UNKNOWN",row.get("state"));assertNull(row.get("loss_minor"));assertEquals(expectedHeld,held(p));
    }
    @AfterEach void everyOwnerKeepsAllThreeBalancesAndLimitsConsistent(){
        for(String owner:OWNERS){
            long journal=n("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",owner);
            assertEquals(journal,n("SELECT balance FROM credit_account WHERE owner_id=?",owner));
            assertEquals(journal,n("SELECT COALESCE(SUM(CAST(original AS DECIMAL(65,0))-consumed-adjusted),0) FROM p0_credit_batch WHERE owner_id=?",owner));
            assertEquals(0,n("SELECT COUNT(*) FROM p0_credit_batch WHERE owner_id=? AND (held<0 OR held>GREATEST(0,original-consumed-adjusted) OR adjusted>original)",owner));
        }
    }
    @Test void firstLostAfterPartialRefundPersistsReviewInsteadOfRollingBackTheOnlyHold(){
        Paid p=paid();refund(p,"re_"+key(),99);String dispute="du_"+key();
        dispute(p,dispute,1,"LOST",99L);
        unknown(p,dispute,90);assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));assertEquals(1,reviews(p));
        assertEquals(0,n("SELECT SUM(delta) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'LOST_OVERLAP:%'",p.order()));
        assertThrows(DomainException.class,()->ledger.debit(p.owner(),1,"debit:"+key()));
        assertEquals(0,n("SELECT COUNT(*) FROM p0_credit_allocation WHERE owner_id=?",p.owner()));
    }
    @Test void holdOnlyAffectsThisOrdersRemainingBatchAndLeavesAnotherRechargeSpendable(){
        Paid a=paid();ledger.debit(a.owner(),40,"spent:"+key());refund(a,"re_"+key(),99);
        Paid b=paid(a.owner());String dispute="du_"+key();dispute(a,dispute,1,"LOST",99L);
        unknown(a,dispute,50);assertEquals(0,held(b));assertEquals(150,ledger.balance(a.owner()));
        String spend="spend-other:"+key();ledger.debit(a.owner(),100,spend);
        assertEquals(100,n("SELECT amount FROM p0_credit_allocation WHERE debit_reference=? AND batch_reference=?",spend,"payment:"+b.order()));
        assertEquals(50,ledger.balance(a.owner()));assertThrows(DomainException.class,()->ledger.debit(a.owner(),1,"blocked:"+key()));
    }
    @Test void concurrentDuplicatesAndFreshLedgerKeepExactlyOneReviewAndOneFreeze() throws Exception {
        Paid p=paid();refund(p,"re_"+key(),99);String dispute="du_"+key();
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);
        try{var futures=new ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));dispute(p,dispute,1,"LOST",99L);return null;}));
            start.countDown();for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        restartLedger();dispute(p,dispute,1,"LOST",99L);
        unknown(p,dispute,90);assertEquals(1,reviews(p));assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact='FREEZE'",p.order()));
        assertEquals(10,adjusted(p));assertEquals(90,ledger.balance(p.owner()));
    }
    @Test void originalConsumptionFailureRestoresIntoTheHeldBatchAndWinningOnlyRestoresCurrentRights(){
        Paid p=paid();String original="debit:"+key();ledger.debit(p.owner(),80,original);refund(p,"re_"+key(),99);
        String dispute="du_"+key();dispute(p,dispute,1,"LOST",99L);unknown(p,dispute,10);
        ledger.refund(p.owner(),original);unknown(p,dispute,90);assertEquals(90,ledger.balance(p.owner()));
        dispute(p,dispute,2,"WON",null);assertEquals(0,held(p));assertEquals(10,adjusted(p));assertEquals(90,ledger.balance(p.owner()));
        ledger.debit(p.owner(),90,"after-win:"+key());assertEquals(0,ledger.balance(p.owner()));
        dispute(p,dispute,2,"WON",null);assertEquals(0,ledger.balance(p.owner()));
    }
    @Test void secondIndependentLossIsHeldForReviewWithoutRepeatingTheFirstLoss(){
        Paid p=paid();String first="du_"+key(),second="du_"+key();dispute(p,first,1,"LOST",99L);
        assertEquals(10,adjusted(p));assertEquals(0,held(p));dispute(p,second,1,"LOST",99L);
        unknown(p,second,90);assertEquals(99,n("SELECT loss_minor FROM p0_credit_dispute WHERE external_id=?",first));
        dispute(p,second,2,"WON",null);assertEquals(0,held(p));assertEquals(10,adjusted(p));assertEquals(90,ledger.balance(p.owner()));
    }
    @Test void changedCandidateLossAtTheSameVersionDoesNotSilentlyReplaceTheReviewFact(){
        Paid p=paid();refund(p,"re_"+key(),99);String dispute="du_"+key();dispute(p,dispute,1,"LOST",99L);
        assertEquals("conflicting dispute version",assertThrows(IllegalStateException.class,()->dispute(p,dispute,1,"LOST",198L)).getMessage());
        unknown(p,dispute,90);assertEquals(1,reviews(p));assertEquals(10,adjusted(p));
    }
    @Test void sameSnapshotCanBeRecheckedAfterExactRefundReturnRemovesTheOverlap(){
        Paid p=paid();String refund="re_"+key(),dispute="du_"+key();refund(p,refund,99);dispute(p,dispute,1,"LOST",99L);
        ledger.compensateRefundReturnedFunds(PROVIDER,MERCHANT,ENV,"txn_"+key(),refund,p.owner(),p.order(),p.capture(),99,"USD","failed","synthetic-exact-return-proof");
        assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));
        dispute(p,dispute,1,"LOST",99L);assertEquals(0,held(p));assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));
        assertEquals("LOST",db.queryForObject("SELECT state FROM p0_credit_dispute WHERE external_id=?",String.class,dispute));
        dispute(p,dispute,1,"LOST",99L);assertEquals(90,ledger.balance(p.owner()));assertEquals(1,reviews(p));
    }
    @Test void unknownWithoutAnOverlapObservationCannotBeChangedAtTheSameVersion(){
        Paid p=paid();String dispute="du_"+key();dispute(p,dispute,1,"UNKNOWN",null);
        assertThrows(IllegalStateException.class,()->dispute(p,dispute,1,"LOST",99L));unknown(p,dispute,100);assertEquals(0,reviews(p));
    }
    @Test void reviewIdentityCannotBeReusedAcrossOrdersOrUsers(){
        Paid a=paid(),b=paid(a.owner()),c=paid();refund(a,"re_"+key(),99);String dispute="du_"+key();dispute(a,dispute,1,"LOST",99L);
        assertThrows(IllegalStateException.class,()->dispute(b,dispute,2,"LOST",99L));
        assertThrows(IllegalStateException.class,()->dispute(c,dispute,2,"LOST",99L));
        unknown(a,dispute,90);assertEquals(0,held(b));assertEquals(0,held(c));assertEquals(100,ledger.balance(c.owner()));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_dispute WHERE external_id=?",dispute));
    }
    @Test void laterAmbiguousObservationNeverErasesAnAlreadyAppliedPermanentLoss(){
        Paid p=paid();refund(p,"re_"+key(),99);String dispute="du_"+key();
        ledger.paymentDispute(PROVIDER,MERCHANT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",1,"LOST",990L);
        assertEquals(100,adjusted(p));assertEquals(0,ledger.balance(p.owner()));
        ledger.paymentDispute(PROVIDER,MERCHANT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",2,"LOST",99L);
        assertEquals(990,n("SELECT loss_minor FROM p0_credit_dispute WHERE external_id=?",dispute));assertEquals(100,adjusted(p));
        assertEquals("REVIEW",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+p.order()));
        assertThrows(IllegalStateException.class,()->ledger.paymentDispute(PROVIDER,MERCHANT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",3,"WON",null));
        assertEquals(0,ledger.balance(p.owner()));assertEquals(1,reviews(p));
    }
    /** Keeps real SQL and two active financial transactions, pausing at their first fact insert.
     * Before the fix this point is reached AFTER the absent-key locking reads; afterwards
     * it is reached BEFORE the first insert/current-read. No lock or concurrency is mocked. */
    private static JdbcFencedCreditsLedger gatedLedger(Set<String> identities){
        var barrier=new CyclicBarrier(2);var seen=ConcurrentHashMap.<Thread>newKeySet();
        var gated=new JdbcTemplate(Objects.requireNonNull(db.getDataSource())){
            @Override public int update(String sql,Object...args){
                if((sql.startsWith("INSERT INTO p0_credit_refund")||sql.startsWith("INSERT INTO p0_credit_dispute")||sql.startsWith("INSERT INTO p0_credit_return"))
                    &&Arrays.stream(args).anyMatch(value->value instanceof String text&&identities.contains(text))
                    &&seen.add(Thread.currentThread())){
                    try{barrier.await(10,TimeUnit.SECONDS);}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("InnoDB lock barrier interrupted",interrupted);}
                    catch(BrokenBarrierException|TimeoutException failed){throw new IllegalStateException("InnoDB lock barrier not reached by both distinct identities",failed);}
                }
                return super.update(sql,args);
            }
        };
        return new JdbcFencedCreditsLedger(new JdbcCreditsLedger(gated,tx),gated,tx);
    }
    private static void pair(Runnable first,Runnable second) throws Exception {
        var workers=Executors.newFixedThreadPool(2);var start=new CyclicBarrier(2);
        try{
            var a=workers.submit(()->{start.await(10,TimeUnit.SECONDS);first.run();return null;});
            var b=workers.submit(()->{start.await(10,TimeUnit.SECONDS);second.run();return null;});
            a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test void differentOwnersSuccessAndFailedRefundsDoNotShareAMissingIdentityLock() throws Exception {
        Paid a=paid(),b=paid();String stem="pay09-refund-"+key(),success=stem+"-a",failed=stem+"-b";
        var concurrent=gatedLedger(Set.of(success,failed));
        pair(()->concurrent.paymentRefund(PROVIDER,MERCHANT,ENV,success,a.owner(),a.order(),a.capture(),198,"USD"),
            ()->concurrent.recordFailedRefund(PROVIDER,MERCHANT,ENV,failed,b.owner(),b.order(),b.capture(),198,"USD","failed","synthetic-final-failure"));
        assertEquals(80,ledger.balance(a.owner()));assertEquals(100,ledger.balance(b.owner()));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_refund WHERE external_id=?",success));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_refund_terminal WHERE refund_id=?",failed));
        assertEquals(0,n("SELECT COUNT(*) FROM p0_credit_refund WHERE external_id=?",failed));
    }
    @Test void differentOwnersRefundAndDisputeHaveIndependentNativeIdentities() throws Exception {
        Paid a=paid(),b=paid();String refund="pay09-refund-"+key(),dispute="pay09-dispute-"+key();
        var concurrent=gatedLedger(Set.of(refund,b.order()));
        pair(()->concurrent.paymentRefund(PROVIDER,MERCHANT,ENV,refund,a.owner(),a.order(),a.capture(),198,"USD"),
            ()->concurrent.paymentDispute(PROVIDER,MERCHANT,ENV,dispute,b.owner(),b.order(),b.capture(),198,"USD",1,"OPEN",null));
        assertEquals(80,ledger.balance(a.owner()));assertEquals(100,ledger.balance(b.owner()));assertEquals(100,held(b));assertEquals(0,held(a));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_refund WHERE external_id=?",refund));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_dispute WHERE external_id=?",dispute));
    }
    @Test void differentOwnersRefundReturnAndNewRefundRemainIndependent() throws Exception {
        Paid a=paid(),b=paid();String original="pay09-original-"+key(),fresh="pay09-fresh-"+key(),movement="txn_"+key();refund(a,original,198);
        var concurrent=gatedLedger(Set.of(original,fresh));
        pair(()->concurrent.compensateRefundReturnedFunds(PROVIDER,MERCHANT,ENV,movement,original,a.owner(),a.order(),a.capture(),198,"USD","failed","synthetic-exact-native-return"),
            ()->concurrent.paymentRefund(PROVIDER,MERCHANT,ENV,fresh,b.owner(),b.order(),b.capture(),198,"USD"));
        assertEquals(100,ledger.balance(a.owner()));assertEquals(80,ledger.balance(b.owner()));assertEquals(0,adjusted(a));assertEquals(20,adjusted(b));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_refund_return WHERE external_id=?",movement));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_refund WHERE external_id=?",fresh));
    }
    @Test void differentOwnersIndependentDisputesDoNotTakeOneAnotherMissingRowGap() throws Exception {
        Paid a=paid(),b=paid();String stem="pay09-independent-"+key(),first=stem+"-a",second=stem+"-b";
        var concurrent=gatedLedger(Set.of(first,second));
        pair(()->concurrent.paymentDispute(PROVIDER,MERCHANT,ENV,first,a.owner(),a.order(),a.capture(),198,"USD",1,"OPEN",null),
            ()->concurrent.paymentDispute(PROVIDER,MERCHANT,ENV,second,b.owner(),b.order(),b.capture(),198,"USD",1,"OPEN",null));
        assertEquals(100,held(a));assertEquals(100,held(b));assertEquals(100,ledger.balance(a.owner()));assertEquals(100,ledger.balance(b.owner()));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_dispute WHERE external_id=?",first));
        assertEquals(1,n("SELECT COUNT(*) FROM p0_credit_dispute WHERE external_id=?",second));
    }
}
