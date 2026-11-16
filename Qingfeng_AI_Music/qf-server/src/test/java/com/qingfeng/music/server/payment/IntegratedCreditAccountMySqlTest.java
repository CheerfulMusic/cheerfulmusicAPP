package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.LocalFinancialCohort;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.FirstLoginCredits;
import com.qingfeng.music.credits.ReferralRewards;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditConsumptions;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcRewriteCharges;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcDailyQuota;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.infrastructure.persistence.lyrics.JdbcCompositionRequests;
import com.qingfeng.music.usage.UsageBudget;
import java.time.Clock;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real canonical core V1-V40 plus integration V41/InnoDB contract, synthetic local facts only. */
class IntegratedCreditAccountMySqlTest {
    private static final String ACCOUNT="acct_refund_offline",ENV="sandbox",PROVIDER="STRIPE";
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static JdbcFencedCreditsLedger ledger;
    private static final Set<String> OWNERS=ConcurrentHashMap.newKeySet();
    private static Paid legacy;
    private static String legacyRefund;
    private record Paid(String owner,String order,String capture,String merchant){}
    private static String unique(){return UUID.randomUUID().toString();}
    private static String newOwner(){String owner=unique();db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",owner);OWNERS.add(owner);return owner;}
    @AfterEach void allTrackedOwnersKeepJournalStoredBalanceAndBatchProjectionTogether(){
        for(String owner:OWNERS){
            long book=number("SELECT COALESCE(SUM(amount),0) FROM credit_ledger WHERE owner_id=?",owner);
            long account=number("SELECT COALESCE(SUM(balance),0) FROM credit_account WHERE owner_id=?",owner);
            long rights=number("SELECT COALESCE(SUM(CAST(original AS DECIMAL(65,0))-consumed-adjusted),0) FROM p0_credit_batch WHERE owner_id=?",owner);
            assertEquals(book,account,"stored balance/journal mismatch");assertEquals(book,rights,"batch/journal mismatch");
        }
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Explicit isolated refund environment required: "+key);return value;}
    private static long number(String sql,Object...args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}

    @BeforeAll static void isolatedActualMigration() throws Exception {
        String database=required("P0_INTEGRATED_CREDITS_MYSQL_DATABASE"),url=required("P0_INTEGRATED_CREDITS_MYSQL_URL");
        if(!database.matches("p0_integrated_credits_20261112r[0-9]+")||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+database+"(?:\\?.*)?"))
            throw new IllegalStateException("Only a new explicitly isolated refund schema is permitted");
        var source=new DriverManagerDataSource(url,required("P0_INTEGRATED_CREDITS_MYSQL_USER"),required("P0_INTEGRATED_CREDITS_MYSQL_PASSWORD"));
        db=new JdbcTemplate(source);
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            try(var identity=statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE(),@@transaction_isolation")){
                assertTrue(identity.next());assertEquals("8.0.46",identity.getString(1));
                LocalFinancialCohort.assertServer(required("P0_INTEGRATED_CREDITS_MYSQL_UUID"),identity.getString(2),identity.getString(3));
                assertEquals(23316,identity.getInt(4));assertEquals("127.0.0.1",identity.getString(5));assertEquals(database,identity.getString(6));
                assertEquals("NONE",identity.getString(7));assertEquals("REPEATABLE-READ",identity.getString(8));
            }
            int grants=0;try(var rows=statement.executeQuery("SHOW GRANTS")){while(rows.next()){
                String grant=rows.getString(1);assertTrue(grant.startsWith("GRANT USAGE ON *.*")||grant.contains("ON `"+database+"`.*"));grants++;
            }}assertEquals(2,grants);
            try(var rows=statement.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")){while(rows.next())
                assertTrue(Set.of(database,"information_schema","performance_schema").contains(rows.getString(1)));}
        }
        assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Never clear or reuse an earlier cohort");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("44").load().migrate();
        assertEquals(44,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        assertEquals("V42__shared_download_entitlement.sql",db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='42'",String.class));assertEquals(-500011050,number("SELECT checksum FROM flyway_schema_history WHERE version='42'"));
        assertEquals(1,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_download_entitlement'"));
        tx=new TransactionTemplate(new JdbcTransactionManager(source));
        // Synthetic pre-integration history, not a production import or reconstructed FIFO allocation.
        String owner=unique(),order=unique(),capture="pi_"+unique();legacy=new Paid(owner,order,capture,ACCOUNT);legacyRefund="re_"+unique();
        db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",owner);
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_capture_id) VALUES(?,?, 'starter-USD',9.90,'USD',100,'PAID',CURRENT_TIMESTAMP,'STRIPE_SANDBOX',?)",order,owner,capture);
        db.update("INSERT INTO credit_ledger VALUES('CREDIT',?,?,100,CURRENT_TIMESTAMP)","payment:"+order,owner);
        db.update("INSERT INTO credit_ledger VALUES('PAYMENT_ADJUST',?,?,-20,CURRENT_TIMESTAMP)","legacy-adjust:"+unique(),owner);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("45").load().migrate();
        assertEquals(45,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        assertEquals("V45__credit_entitlement_integration.sql",db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='45'",String.class));assertEquals(-2125061473,number("SELECT checksum FROM flyway_schema_history WHERE version='45'"));
        assertEquals(1,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_download_entitlement'"));
        assertEquals(3,number("SELECT protocol FROM p0_credit_writer_policy WHERE id=1"));
        db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,status,created_at) VALUES(?,?,'PAYMENT',?,100,0,20,0,'ACTIVE',CURRENT_TIMESTAMP)","payment:"+order,owner,order);
        db.update("INSERT INTO p0_credit_payment_binding VALUES(?,?,?,?,?,?)",order,PROVIDER,ACCOUNT,ENV,owner,capture);
        db.update("INSERT INTO p0_credit_refund VALUES(?,?,?,?,?,?,?,?,?)",PROVIDER,ACCOUNT,ENV,legacyRefund,owner,order,capture,198,"USD");
        db.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,80,CURRENT_TIMESTAMP)",owner);
        assertEquals(80,number("SELECT SUM(amount) FROM credit_ledger"));assertEquals(80,number("SELECT SUM(original-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(1,db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP,evidence='SYNTHETIC_INTEGRATED_MAPPING_20261112' WHERE id=1 AND status='PREPARING'"));
        ledger=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);OWNERS.add(owner);
        assertEquals(80,ledger.balance(owner));
    }
    private static Paid paid(){return paid(newOwner(),ACCOUNT,100);}
    private static Paid paid(String owner,String account,long credits){
        String order=unique(),capture="pi_"+unique();
        tx.executeWithoutResult(status->{
            if(number("SELECT COUNT(*) FROM creator WHERE id=?",owner)==0)db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",owner);
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);OWNERS.add(owner);
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_capture_id) VALUES(?,?, 'starter-USD',?,'USD',?,'PAID',CURRENT_TIMESTAMP,'STRIPE_SANDBOX',?)",order,owner,new BigDecimal("9.90"),credits,capture);
            ledger.credit(owner,credits,"payment:"+order);ledger.bindPayment(PROVIDER,account,ENV,owner,order,capture,"USD");
        });return new Paid(owner,order,capture,account);
    }
    private static void success(Paid p,String refund,long amount){ledger.paymentRefund(PROVIDER,p.merchant(),ENV,refund,p.owner(),p.order(),p.capture(),amount,"USD");}
    private static void failed(Paid p,String refund,long amount,String status){ledger.recordFailedRefund(PROVIDER,p.merchant(),ENV,refund,p.owner(),p.order(),p.capture(),amount,"USD",status,"synthetic-authoritative-refund:"+refund);}
    private static void returned(Paid p,String refund,long amount,String movement){ledger.compensateRefundReturnedFunds(PROVIDER,p.merchant(),ENV,movement,refund,p.owner(),p.order(),p.capture(),amount,"USD","failed","synthetic-complete-return:"+movement);}
    private static void dispute(Paid p,String dispute,String state,Long loss){ledger.paymentDispute(PROVIDER,p.merchant(),ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",1,state,loss);}
    private static void disputeReturn(Paid p,String dispute,String movement){ledger.compensateReturnedFunds(PROVIDER,p.merchant(),ENV,movement,dispute,p.owner(),p.order(),p.capture(),990,"USD","synthetic-complete-dispute-return");}
    private static long adjusted(Paid p){return number("SELECT adjusted FROM p0_credit_batch WHERE reference=?","payment:"+p.order());}
    private static long held(Paid p){return number("SELECT held FROM p0_credit_batch WHERE reference=?","payment:"+p.order());}
    private static long returnRows(Paid p){return number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND_RETURN'",p.owner());}
    private static void concurrent(List<Runnable> actions) throws Exception {
        var pool=Executors.newFixedThreadPool(actions.size());var barrier=new CyclicBarrier(actions.size());
        try{var futures=new ArrayList<Future<?>>();for(Runnable action:actions)futures.add(pool.submit(()->{barrier.await(10,TimeUnit.SECONDS);action.run();return null;}));
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void mappedLegacySuccessSurvivesV41AndRestoresWithoutRewritingOriginal(){
        returned(legacy,legacyRefund,198,"txn_"+unique());assertEquals(100,ledger.balance(legacy.owner()));assertEquals(0,adjusted(legacy));
        assertEquals(198,number("SELECT amount_minor FROM p0_credit_refund WHERE external_id=?",legacyRefund));
        assertThrows(DataAccessException.class,()->db.update("UPDATE p0_credit_refund SET amount_minor=1 WHERE external_id=?",legacyRefund));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM p0_credit_refund WHERE external_id=?",legacyRefund));
    }
    @Test void v41ProtocolThreeRejectsOldWritersAndStaleSession(){
        assertEquals(3,number("SELECT protocol FROM p0_credit_writer_policy WHERE id=1"));
        assertNotEquals("P0_CREDIT_WRITER_V1",db.queryForObject("SELECT epoch FROM p0_credit_writer_policy WHERE id=1",String.class));
        long before=ledger.balance(legacy.owner());
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{
            var policy=db.queryForMap("SELECT epoch,protocol FROM p0_credit_writer_policy WHERE id=1 FOR UPDATE");
            // Exact V33 protocol acceptance condition, executed against the upgraded real policy.
            if(((Number)policy.get("protocol")).intValue()!=1)throw new IllegalStateException("Unsupported credit writer protocol");
            fail("A V33 writer must never pass the V41 writer gate");
        }));
        assertThrows(DataAccessException.class,()->tx.executeWithoutResult(status->{
            db.execute("SET @qf_credit_writer_epoch='P0_CREDIT_WRITER_V1'");
            try{db.update("INSERT INTO credit_ledger VALUES('CREDIT',?,?,1,CURRENT_TIMESTAMP)","stale-writer:"+unique(),legacy.owner());}
            finally{db.execute("SET @qf_credit_writer_epoch=NULL");}
        }));
        assertEquals(before,ledger.balance(legacy.owner()));
        assertThrows(DataAccessException.class,()->db.update("UPDATE p0_credit_writer_policy SET protocol=1 WHERE id=1"));
    }
    @Test void successfulThenFailedRefundRestoresOnceAndRejectsStaleSuccess(){
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();success(p,refund,198);assertEquals(80,ledger.balance(p.owner()));
        assertThrows(IllegalStateException.class,()->failed(p,refund,198,"failed"));assertEquals(80,ledger.balance(p.owner()));
        returned(p,refund,198,movement);returned(p,refund,198,movement);failed(p,refund,198,"failed");success(p,refund,198);
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(1,returnRows(p));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",p.order()));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
        assertEquals(20,number("SELECT SUM(amount) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND_RETURN'",p.owner()));
        assertEquals(20,number("SELECT SUM(delta) FROM p0_credit_financial_audit WHERE order_id=? AND fact='REFUND_FUNDS_RETURN'",p.order()));
        assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='PAYMENT_RETURN'",p.owner()));
    }
    @Test void failedBeforeSuccessHasTerminalIdentityAndNeverMintsOrLaterDebitsRights(){
        var p=paid();String refund="re_"+unique();failed(p,refund,198,"failed");failed(p,refund,198,"canceled");success(p,refund,198);
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(0,returnRows(p));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",p.order()));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_terminal WHERE order_id=?",p.order()));
        assertThrows(IllegalStateException.class,()->success(p,refund,199));
    }
    @Test void returnedBeforeLocalSuccessPersistsFactAndSuppressesBothStaleAndRestartedSuccess(){
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();returned(p,refund,198,movement);success(p,refund,198);
        new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx).paymentRefund(PROVIDER,p.merchant(),ENV,refund,p.owner(),p.order(),p.capture(),198,"USD");
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,returnRows(p));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",p.order()));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
    }
    @Test void canceledReturnUsesSameCompleteProofContract(){
        var p=paid();String refund="re_"+unique();success(p,refund,198);
        ledger.compensateRefundReturnedFunds(PROVIDER,p.merchant(),ENV,"txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"USD","canceled","synthetic-canceled-refund-return");
        assertEquals(100,ledger.balance(p.owner()));assertEquals(1,returnRows(p));
    }
    @Test void alreadyConsumedFullRefundCreatesDebtAndReturnOnlyRestoresRemainingRights(){
        var p=paid();String refund="re_"+unique();ledger.debit(p.owner(),80,"spent:"+unique());success(p,refund,990);
        assertEquals(-80,ledger.balance(p.owner()));assertThrows(RuntimeException.class,()->ledger.debit(p.owner(),1,"debt-blocked:"+unique()));
        returned(p,refund,990,"txn_"+unique());assertEquals(20,ledger.balance(p.owner()));assertEquals(0,adjusted(p));
        assertEquals(80,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+p.order()));ledger.debit(p.owner(),20,"remaining:"+unique());assertEquals(0,ledger.balance(p.owner()));
    }
    @Test void cumulativeCeilingIsRecomputedInsteadOfReturningEachRoundedRefund(){
        var p=paid();String one="re_"+unique(),two="re_"+unique();success(p,one,1);success(p,two,1);assertEquals(99,ledger.balance(p.owner()));
        returned(p,one,1,"txn_"+unique());assertEquals(99,ledger.balance(p.owner()));assertEquals(1,adjusted(p));assertEquals(0,returnRows(p));
        returned(p,two,1,"txn_"+unique());assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(1,returnRows(p));
    }
    @Test void anotherSuccessfulPartialRefundRemainsPermanentlyAdjusted(){
        var p=paid();String one="re_"+unique(),two="re_"+unique();success(p,one,198);success(p,two,99);assertEquals(70,ledger.balance(p.owner()));
        returned(p,one,198,"txn_"+unique());assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));assertEquals(297,number("SELECT SUM(amount_minor) FROM p0_credit_refund WHERE order_id=?",p.order()));
    }
    @Test void aNewFullRefundAfterACompleteReturnUsesNetFactsInsteadOfRawLifetimeRefundSum(){
        var p=paid();String first="re_"+unique(),second="re_"+unique();success(p,first,990);returned(p,first,990,"txn_"+unique());
        success(p,second,990);assertEquals(0,ledger.balance(p.owner()));assertEquals(100,adjusted(p));
        assertEquals(1980,number("SELECT SUM(amount_minor) FROM p0_credit_refund WHERE order_id=?",p.order()));
        returned(p,second,990,"txn_"+unique());assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));
    }
    @Test void refundReturnRestoresOrderFreezeAndLeavesOtherBatchesSpendable(){
        var p=paid();String refund="re_"+unique();dispute(p,"du_"+unique(),"OPEN",null);assertEquals(100,held(p));
        success(p,refund,198);assertEquals(80,held(p));returned(p,refund,198,"txn_"+unique());
        assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));assertThrows(RuntimeException.class,()->ledger.debit(p.owner(),1,"frozen:"+unique()));
        ledger.credit(p.owner(),5,"gift:"+unique());ledger.debit(p.owner(),5,"other-batch:"+unique());assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));
    }
    @Test void verifiedFullDisputeLossSurvivesRefundReturnWithoutDoubleRecovery(){
        var p=paid();String refund="re_"+unique(),dispute="du_"+unique();success(p,refund,198);dispute(p,dispute,"LOST",990L);
        returned(p,refund,198,"txn_"+unique());assertEquals(0,ledger.balance(p.owner()));assertEquals(100,adjusted(p));assertEquals(0,returnRows(p));
        assertEquals("LOST",db.queryForObject("SELECT state FROM p0_credit_dispute WHERE external_id=?",String.class,dispute));
        disputeReturn(p,dispute,"txn_"+unique());assertEquals(100,ledger.balance(p.owner()));assertEquals(0,returnRows(p));
        assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='PAYMENT_RETURN'",p.owner()));
    }
    @Test void oneNativeReturnCannotBeUsedByAnotherRefundOrderOrUser(){
        var p=paid();var sameOwner=paid(p.owner(),ACCOUNT,100);var other=paid();String original="re_"+unique(),second="re_"+unique(),third="re_"+unique(),movement="txn_"+unique();
        success(p,original,198);success(sameOwner,second,198);success(other,third,198);returned(p,original,198,movement);
        assertThrows(IllegalStateException.class,()->returned(sameOwner,second,198,movement));assertThrows(IllegalStateException.class,()->returned(other,third,198,movement));
        assertThrows(IllegalStateException.class,()->returned(p,original,198,"txn_"+unique()));
        assertEquals(180,ledger.balance(p.owner()));assertEquals(80,ledger.balance(other.owner()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_terminal WHERE order_id=?",other.order()));
    }
    @Test void terminalRefundIdentityCannotBeReusedAcrossOrdersAndUsers(){
        var p=paid();var sameOwner=paid(p.owner(),ACCOUNT,100);var other=paid();String refund="re_"+unique();failed(p,refund,198,"failed");
        assertThrows(IllegalStateException.class,()->failed(sameOwner,refund,198,"failed"));assertThrows(IllegalStateException.class,()->success(other,refund,198));
        assertEquals(200,ledger.balance(p.owner()));assertEquals(100,ledger.balance(other.owner()));
    }
    @Test void refundReturnAndDisputeReturnNativeIdentityAreMutuallyExclusiveInBothDirections(){
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();success(p,refund,198);returned(p,refund,198,movement);
        var disputed=paid();String dispute="du_"+unique();dispute(disputed,dispute,"LOST",990L);
        assertThrows(IllegalStateException.class,()->disputeReturn(disputed,dispute,movement));assertEquals(100,adjusted(disputed));
        String otherMovement="txn_"+unique();disputeReturn(disputed,dispute,otherMovement);
        var another=paid();String anotherRefund="re_"+unique();success(another,anotherRefund,198);
        assertThrows(IllegalStateException.class,()->returned(another,anotherRefund,198,otherMovement));assertEquals(80,ledger.balance(another.owner()));
    }
    @Test void missingProofAndIdentityAmountCurrencyMerchantMismatchRollBackEverything(){
        var p=paid();String refund="re_"+unique();success(p,refund,198);
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,ACCOUNT,ENV,"txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"USD","failed",""));
        assertThrows(RuntimeException.class,()->returned(p,refund,197,"txn_"+unique()));
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,ACCOUNT,ENV,"txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"GBP","failed","synthetic"));
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,"wrong",ENV,"txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"USD","failed","synthetic"));
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,ACCOUNT,"live","txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"USD","failed","synthetic"));
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,ACCOUNT,ENV,"txn_"+unique(),refund,p.owner(),p.order(),"wrong",198,"USD","failed","synthetic"));
        assertThrows(RuntimeException.class,()->ledger.compensateRefundReturnedFunds(PROVIDER,ACCOUNT,ENV,"txn_"+unique(),refund,p.owner(),p.order(),p.capture(),198,"USD","pending","synthetic"));
        assertEquals(80,ledger.balance(p.owner()));assertEquals(20,adjusted(p));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_terminal WHERE order_id=?",p.order()));
    }
    @Test void outerTransactionFailureRollsBackIdentityFactsBalanceAndBatchTogether(){
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();success(p,refund,198);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{returned(p,refund,198,movement);throw new IllegalStateException("synthetic after-return failure");}));
        assertEquals(80,ledger.balance(p.owner()));assertEquals(20,adjusted(p));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_terminal WHERE order_id=?",p.order()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
        returned(p,refund,198,movement);assertEquals(100,ledger.balance(p.owner()));assertEquals(1,returnRows(p));
    }
    @Test void duplicateReturnAndStaleSuccessConcurrentTransactionsRecoverExactlyOnce() throws Exception {
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();success(p,refund,198);var actions=new ArrayList<Runnable>();
        for(int i=0;i<8;i++){actions.add(()->returned(p,refund,198,movement));actions.add(()->success(p,refund,198));}concurrent(actions);
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(1,returnRows(p));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
    }
    @Test void returnAndIndependentNewRefundSerializeToTheSameNetTarget() throws Exception {
        var p=paid();String refund="re_"+unique(),other="re_"+unique();success(p,refund,198);
        concurrent(List.of(()->returned(p,refund,198,"txn_"+unique()),()->success(p,other,99)));
        assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));assertEquals(1,returnRows(p));
    }
    @Test void concurrentReturnsPreserveCumulativeRoundingBoundary() throws Exception {
        var p=paid();String one="re_"+unique(),two="re_"+unique();success(p,one,1);success(p,two,1);
        concurrent(List.of(()->returned(p,one,1,"txn_"+unique()),()->returned(p,two,1,"txn_"+unique())));
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(1,returnRows(p));assertEquals(2,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
    }
    @Test void concurrentRefundAndDisputeCompeteForOneNativeIdentity() throws Exception {
        var p=paid();var q=paid();String refund="re_"+unique(),dispute="du_"+unique(),movement="txn_"+unique();success(p,refund,198);dispute(q,dispute,"LOST",990L);
        var successes=new java.util.concurrent.atomic.AtomicInteger();var conflicts=new java.util.concurrent.atomic.AtomicInteger();
        concurrent(List.of(()->{try{returned(p,refund,198,movement);successes.incrementAndGet();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("identity"));conflicts.incrementAndGet();}},
            ()->{try{disputeReturn(q,dispute,movement);successes.incrementAndGet();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("identity"));conflicts.incrementAndGet();}}));
        assertEquals(1,successes.get());assertEquals(1,conflicts.get());
        assertEquals(1,number("SELECT (SELECT COUNT(*) FROM p0_credit_refund_return WHERE external_id=?)+(SELECT COUNT(*) FROM p0_credit_return WHERE external_id=?)",movement,movement));
        long refundWon=number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE external_id=?",movement);
        assertEquals(80+refundWon*20+(1-refundWon)*100,ledger.balance(p.owner())+ledger.balance(q.owner()));
    }
    @Test void factsAreAppendOnlyAndTheSameNativeIdIsScopedByMerchant(){
        var p=paid();String refund="re_"+unique(),movement="txn_"+unique();success(p,refund,198);returned(p,refund,198,movement);
        assertThrows(DataAccessException.class,()->db.update("UPDATE p0_credit_refund_return SET amount_minor=1 WHERE external_id=?",movement));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM p0_credit_refund_return WHERE external_id=?",movement));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM p0_credit_refund_terminal WHERE refund_id=?",refund));
        var other=paid(unique(),"acct_other_offline",100);String otherRefund="re_"+unique();success(other,otherRefund,198);returned(other,otherRefund,198,movement);
        assertEquals(100,ledger.balance(other.owner()));assertEquals(2,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE external_id=?",movement));
    }
    @Test void largeCreditMultiplicationUsesExactIntegerArithmetic(){
        var p=paid(unique(),ACCOUNT,Long.MAX_VALUE);String refund="re_"+unique();success(p,refund,198);
        long target=java.math.BigInteger.valueOf(Long.MAX_VALUE).add(java.math.BigInteger.valueOf(4)).divide(java.math.BigInteger.valueOf(5)).longValueExact();
        assertEquals(target,adjusted(p));assertEquals(Long.MAX_VALUE-target,ledger.balance(p.owner()));
        returned(p,refund,198,"txn_"+unique());assertEquals(Long.MAX_VALUE,ledger.balance(p.owner()));assertEquals(0,adjusted(p));
    }
    @Test void acceptedWonCannotBeReopenedByOlderEqualOrNewerLocalVersionsOrAnotherIdentity(){
        var p=paid();String dispute="du_"+unique();
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",2,"WON",null);
        for(String state:List.of("LOST","UNDER_REVIEW","OPEN","UNKNOWN"))for(long version:new long[]{1,2,3}){
            Long loss="LOST".equals(state)?990L:null;
            var failure=assertThrows(IllegalStateException.class,()->ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",version,state,loss));
            assertEquals("WON_DISPUTE_REQUIRES_MANUAL_REVIEW",failure.getMessage());
            assertEquals(100,ledger.balance(p.owner()));assertEquals(0,adjusted(p));assertEquals(0,held(p));
            assertEquals("WON",db.queryForObject("SELECT state FROM p0_credit_dispute WHERE external_id=?",String.class,dispute));
            assertEquals(2,number("SELECT version FROM p0_credit_dispute WHERE external_id=?",dispute));
        }
        var other=paid();
        var conflict=assertThrows(IllegalStateException.class,()->ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,dispute,other.owner(),other.order(),other.capture(),990,"USD",4,"WON",null));
        assertEquals("global dispute identity conflict",conflict.getMessage());
        assertEquals(100,ledger.balance(p.owner()));assertEquals(100,ledger.balance(other.owner()));
        assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_dispute WHERE order_id=?",other.order()));
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",4,"WON",null);
        assertEquals(100,ledger.balance(p.owner()));assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type IN ('PAYMENT_ADJUST','PAYMENT_RETURN')",p.owner()));
    }

    @Test void oldGlobalLockAndAnotherOwnerCannotBlockAnActiveProtocolThreeWriter() throws Exception {
        String a=newOwner(),b=newOwner();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->tx.executeWithoutResult(s->{db.queryForList("SELECT id FROM credit_lock WHERE id=1 FOR UPDATE");ledger.credit(a,70,"independent:"+a);held.countDown();await(release);}));
            assertTrue(held.await(10,TimeUnit.SECONDS));
            pool.submit(()->ledger.credit(b,80,"independent:"+b)).get(5,TimeUnit.SECONDS);
            assertEquals(80,ledger.balance(b));release.countDown();first.get(10,TimeUnit.SECONDS);assertEquals(70,ledger.balance(a));
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("test coordination timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}

    @Test void concurrentIdenticalCreditIsAppendedOnceAcrossAllThreeRepresentations() throws Exception {
        String owner=newOwner(),reference="replayed:"+unique();var calls=new ArrayList<Runnable>();for(int i=0;i<12;i++)calls.add(()->ledger.credit(owner,100,reference));concurrent(calls);
        assertEquals(100,ledger.balance(owner));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE reference_id=?",reference));assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_batch WHERE reference=?",reference));
    }

    @Test void invitationCanWaitForInviterWithoutHoldingAGlobalFinancialLock() throws Exception {
        String inviter=newOwner(),invitee=newOwner();db.update("INSERT INTO referral_pending(invitee_id,inviter_id,created_at) VALUES(?,?,CURRENT_TIMESTAMP)",invitee,inviter);
        var constructor=Class.forName("com.qingfeng.music.server.web.app.JdbcReferralRewards").getDeclaredConstructor(JdbcTemplate.class,CreditsLedger.class);constructor.setAccessible(true);
        var actual=(ReferralRewards)constructor.newInstance(db,ledger);var inviteeCredited=new CountDownLatch(1);var inviterHeld=new CountDownLatch(1);var releaseInviter=new CountDownLatch(1);
        var login=new FirstLoginCredits(ledger,owner->{inviteeCredited.countDown();actual.rewardForFirstLogin(owner);});var pool=Executors.newFixedThreadPool(2);
        try{
            var first=pool.submit(()->tx.executeWithoutResult(s->{db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",inviter);inviterHeld.countDown();await(inviteeCredited);ledger.credit(inviter,FirstLoginCredits.AMOUNT,"first-login:"+inviter);releaseInviter.countDown();}));
            assertTrue(inviterHeld.await(10,TimeUnit.SECONDS));var second=pool.submit(()->tx.executeWithoutResult(s->login.grant(invitee)));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);assertEquals(50,FirstLoginCredits.AMOUNT);assertEquals(70,ledger.balance(inviter));assertEquals(50,ledger.balance(invitee));assertEquals(0,releaseInviter.getCount());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void currentOwnerBalanceSurvivesAnOlderRepeatableReadSnapshot() throws Exception {
        String owner=newOwner();var pool=Executors.newSingleThreadExecutor();
        try{tx.executeWithoutResult(s->{assertEquals(0,number("SELECT COUNT(*) FROM credit_account WHERE owner_id=?",owner));
            try{pool.submit(()->ledger.credit(owner,70,"snapshot:"+owner)).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
            ledger.debit(owner,20,"snapshot-spend:"+owner);
        });}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(50,ledger.balance(owner));
    }

    @Test void currentRefundReturnFactsSurviveAnOlderRepeatableReadSnapshot() throws Exception {
        var p=paid();String refund="re_"+unique();success(p,refund,198);var pool=Executors.newSingleThreadExecutor();
        try{tx.executeWithoutResult(s->{assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund_return WHERE order_id=?",p.order()));
            try{pool.submit(()->returned(p,refund,198,"txn_"+unique())).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
            success(p,"re_"+unique(),99);
        });}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));
    }

    @Test void oldSnapshotCanReplayAnAlreadyCommittedCreditAndUseItsRealBatch() throws Exception {
        String owner=newOwner(),reference="old-snapshot-replay:"+unique();var pool=Executors.newSingleThreadExecutor();
        try{tx.executeWithoutResult(s->{assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_batch WHERE owner_id=?",owner));
            try{pool.submit(()->ledger.credit(owner,70,reference)).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}
            ledger.credit(owner,70,reference);ledger.debit(owner,20,"replayed-spend:"+unique());
        });}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}assertEquals(50,ledger.balance(owner));
    }

    @Test void orderHoldSkipsOnlyItsOwnBatchEvenWhenLegacyAccountRiskFlagIsSet(){
        var a=paid();var b=paid(a.owner(),ACCOUNT,100);dispute(a,"du_"+unique(),"OPEN",null);
        db.update("UPDATE creator SET payment_risk_frozen=1 WHERE id=?",a.owner());ledger.debit(a.owner(),100,"other-order:"+unique());
        assertEquals(100,ledger.balance(a.owner()));assertEquals(100,held(a));assertEquals(0,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+a.order()));assertEquals(100,number("SELECT consumed FROM p0_credit_batch WHERE reference=?","payment:"+b.order()));
        assertThrows(RuntimeException.class,()->ledger.debit(a.owner(),1,"frozen:"+unique()));
    }

    @Test void fifoFailureRefundRestoresTheOriginalBatchAllocationsOnce(){
        String owner=newOwner(),a="gift:"+unique(),b="gift:"+unique(),spend="fifo:"+unique();ledger.credit(owner,60,a);ledger.credit(owner,40,b);
        db.update("UPDATE p0_credit_batch SET created_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP) WHERE reference=?",a);
        ledger.debit(owner,80,spend);assertEquals(60,number("SELECT amount FROM p0_credit_allocation WHERE debit_reference=? AND batch_reference=?",spend,a));assertEquals(20,number("SELECT amount FROM p0_credit_allocation WHERE debit_reference=? AND batch_reference=?",spend,b));
        ledger.refund(owner,spend);ledger.refund(owner,spend);assertEquals(100,ledger.balance(owner));assertEquals(0,number("SELECT SUM(consumed) FROM p0_credit_batch WHERE owner_id=?",owner));assertEquals(2,number("SELECT COUNT(*) FROM p0_credit_allocation WHERE debit_reference=? AND restored=1",spend));
    }

    @Test void verifiedPartialLossFreezesThenAdjustsOnlyActualPrincipalAndWonNeverDeducts(){
        var p=paid();String d="du_"+unique();ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,d,p.owner(),p.order(),p.capture(),198,"USD",1,"OPEN",null);assertEquals(100,held(p));assertEquals(0,adjusted(p));
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,d,p.owner(),p.order(),p.capture(),198,"USD",2,"UNDER_REVIEW",null);assertEquals(100,held(p));
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,d,p.owner(),p.order(),p.capture(),198,"USD",3,"LOST",99L);assertEquals(90,ledger.balance(p.owner()));assertEquals(10,adjusted(p));assertEquals(0,held(p));
        var q=paid();String won="du_"+unique();dispute(q,won,"OPEN",null);ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,won,q.owner(),q.order(),q.capture(),990,"USD",2,"WON",null);assertEquals(100,ledger.balance(q.owner()));assertEquals(0,adjusted(q));assertEquals(0,held(q));
    }

    @Test void debtBlocksConsumptionUntilNewRechargeAndGiftRestorePositiveAvailableRights(){
        var p=paid();ledger.debit(p.owner(),80,"debt-spend:"+unique());success(p,"re_"+unique(),990);assertEquals(-80,ledger.balance(p.owner()));
        assertThrows(RuntimeException.class,()->ledger.debit(p.owner(),1,"negative:"+unique()));paid(p.owner(),ACCOUNT,50);assertEquals(-30,ledger.balance(p.owner()));assertThrows(RuntimeException.class,()->ledger.debit(p.owner(),1,"still-negative:"+unique()));
        ledger.credit(p.owner(),40,"gift:"+unique());assertEquals(10,ledger.balance(p.owner()));ledger.debit(p.owner(),10,"recovered:"+unique());assertEquals(0,ledger.balance(p.owner()));
    }

    @Test void sameOwnerDifferentOrdersRefundAndConsumptionRemainAtomic() throws Exception {
        var p=paid();paid(p.owner(),ACCOUNT,100);concurrent(List.of(()->success(p,"re_"+unique(),198),()->ledger.debit(p.owner(),50,"sameowner:"+unique())));
        assertEquals(130,ledger.balance(p.owner()));assertEquals(20,adjusted(p));
    }

    @Test void multipleOpenCasesFreezeOnceAndOnlyTheLastWonReleasesCurrentRemainingRights(){
        var p=paid();ledger.debit(p.owner(),80,"two-cases:"+unique());String a="du_"+unique(),b="du_"+unique();dispute(p,a,"OPEN",null);dispute(p,b,"OPEN",null);dispute(p,b,"OPEN",null);
        assertEquals(20,held(p));assertEquals(20,ledger.balance(p.owner()));ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,a,p.owner(),p.order(),p.capture(),990,"USD",2,"WON",null);assertEquals(20,held(p));
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,b,p.owner(),p.order(),p.capture(),990,"USD",2,"WON",null);assertEquals(0,held(p));assertEquals(0,adjusted(p));assertEquals(20,ledger.balance(p.owner()));
    }

    @Test void excessRefundRollsBackAndAmbiguousLossPersistsAnOrderScopedReviewWithoutGuessing(){
        var p=paid();success(p,"re_"+unique(),900);String excessive="re_"+unique();assertThrows(IllegalStateException.class,()->success(p,excessive,91));assertEquals(9,ledger.balance(p.owner()));assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_refund WHERE external_id=?",excessive));
        var q=paid();success(q,"re_"+unique(),99);String conflict="du_"+unique();
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,conflict,q.owner(),q.order(),q.capture(),198,"USD",1,"LOST",99L);
        assertEquals(90,ledger.balance(q.owner()));assertEquals(10,adjusted(q));assertEquals(90,held(q));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_dispute WHERE external_id=? AND state='UNKNOWN' AND loss_minor IS NULL",conflict));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'LOST_OVERLAP:%'",q.order()));
        assertThrows(DomainException.class,()->ledger.debit(q.owner(),1,"overlap-blocked:"+unique()));
        ledger.paymentDispute(PROVIDER,ACCOUNT,ENV,conflict,q.owner(),q.order(),q.capture(),198,"USD",1,"LOST",99L);
        assertEquals(90,held(q));assertEquals(90,ledger.balance(q.owner()));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'LOST_OVERLAP:%'",q.order()));
    }

    @Test void crossOwnerRefundSuccessAndTerminalCompeteForOneImmutableNativeBinding() throws Exception {
        var a=paid();var b=paid();String id="re_"+unique();var good=new java.util.concurrent.atomic.AtomicInteger();var bad=new java.util.concurrent.atomic.AtomicInteger();
        concurrent(List.of(()->{try{success(a,id,198);good.incrementAndGet();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("identity"));bad.incrementAndGet();}},()->{try{failed(b,id,198,"failed");good.incrementAndGet();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("identity"));bad.incrementAndGet();}}));
        assertEquals(1,good.get());assertEquals(1,bad.get());assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_native_identity WHERE kind='REFUND_BINDING' AND resource_id=?",id));assertEquals(100,ledger.balance(b.owner()));assertTrue(Set.of(80L,100L).contains(ledger.balance(a.owner())));
    }

    @Test void crossOwnerDisputeCannotFreezeTwoOrdersUnderOneNativeIdentity() throws Exception {
        var a=paid();var b=paid();String id="du_"+unique();var good=new java.util.concurrent.atomic.AtomicInteger();var bad=new java.util.concurrent.atomic.AtomicInteger();
        concurrent(List.of(()->{try{dispute(a,id,"OPEN",null);good.incrementAndGet();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("identity"));bad.incrementAndGet();}},()->{try{dispute(b,id,"OPEN",null);good.incrementAndGet();}catch(IllegalStateException e){assertTrue(e.getMessage().contains("identity"));bad.incrementAndGet();}}));
        assertEquals(1,good.get());assertEquals(1,bad.get());assertEquals(100,held(a)+held(b));assertEquals(200,ledger.balance(a.owner())+ledger.balance(b.owner()));
    }

    @Test void bareLegacyWriterCannotLeaveACacheOnlyOrJournalOnlyMutation(){
        var p=paid();var old=new JdbcCreditsLedger(db,tx);assertThrows(DataAccessException.class,()->old.credit(p.owner(),30,"raw-old:"+unique()));assertEquals(100,ledger.balance(p.owner()));
    }

    @Test void lyricsUnknownRetainsOriginalDebitAndCapacityAndLateSuccessCompletesWithoutRecharging(){
        String owner=newOwner(),work=work(owner),key=unique();ledger.credit(owner,20,"lyrics-fund:"+owner);var budget=budget();var requests=new JdbcCompositionRequests(db,budget,tx,ledger,new JdbcCreditConsumptions(db,tx));
        assertTrue(requests.claim(owner,work,key,"synthetic-request"));assertEquals(15,ledger.balance(owner));requests.unknown(work,key,"LYRICS_PROVIDER_TIMEOUT");
        assertEquals("UNKNOWN",requests.find(work,key).orElseThrow().status());assertEquals(15,ledger.balance(owner));assertEquals(1,budget.allowance(owner,UsageBudget.Kind.LYRICS).active());
        db.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",work,key);
        assertEquals(1,budget.allowance(owner,UsageBudget.Kind.LYRICS).active());assertFalse(requests.claim(owner,work,unique(),"different-request"));requests.complete(work,key,unique());
        assertEquals("SUCCEEDED",requests.find(work,key).orElseThrow().status());assertEquals(15,ledger.balance(owner));assertEquals(0,budget.allowance(owner,UsageBudget.Kind.LYRICS).active());assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'",owner));
    }
    private static String work(String owner){String id=unique();db.update("INSERT INTO creator_session(token_hash,creator_id,expires_at) VALUES(?,?,TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP))",unique(),owner);db.update("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at) VALUES(?,?,'offline','birthday','fiction','message','pop','synthetic',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,owner);return id;}
    private static JdbcUsageBudget budget(){var limits=new EnumMap<UsageBudget.Kind,JdbcUsageBudget.Limits>(UsageBudget.Kind.class);for(var kind:UsageBudget.Kind.values())limits.put(kind,new JdbcUsageBudget.Limits(-1,-1,10,100));CreditsPricing pricing=new CreditsPricing(){public boolean enabled(){return true;}public long cost(Kind kind){return 5;}public long freePerDay(Kind kind){return 0;}};return new JdbcUsageBudget(db,tx,limits,Clock.systemUTC(),ZoneOffset.UTC).withCredits(ledger,new JdbcCreditConsumptions(db,tx),pricing);}

    @Test void rewriteUnknownPreservesChargeBlocksResubmissionAndAcceptsOneLateSuccess(){
        String owner=newOwner(),key=unique();ledger.credit(owner,10,"rewrite-fund:"+owner);var charges=rewriteCharges();var calls=new java.util.concurrent.atomic.AtomicInteger();
        InspirationRewriter writer=new InspirationRewriter(){public boolean isConfigured(){return true;}public String rewrite(String text,String language){calls.incrementAndGet();throw new DomainException("REWRITE_PROVIDER_RESULT_UNKNOWN","offline timeout");}};
        var service=new InspirationRewriteService(writer,true,3,charges);assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN",assertThrows(DomainException.class,()->service.rewrite(owner,"fiction","en",key)).code());
        assertEquals(9,ledger.balance(owner));assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?",String.class,owner,key));
        assertEquals("REWRITE_REQUEST_PENDING",assertThrows(DomainException.class,()->service.rewrite(owner,"fiction","en",key)).code());assertEquals("REWRITE_GENERATION_BUSY",assertThrows(DomainException.class,()->service.rewrite(owner,"fiction","en",unique())).code());assertEquals(1,calls.get());
        charges.fail(owner,key);assertEquals(9,ledger.balance(owner));charges.succeed(owner,key,"late valid result");charges.succeed(owner,key,"late valid result");assertEquals("late valid result",service.rewrite(owner,"fiction","en",key));assertEquals(1,calls.get());assertEquals(9,ledger.balance(owner));assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'",owner));
        assertThrows(DomainException.class,()->charges.succeed(owner,key,"conflicting result"));
    }

    @Test void rewriteStaleNeverSubmittedReservationRestoresItsOriginalAllocation(){
        String owner=newOwner(),key=unique();ledger.credit(owner,10,"rewrite-safe:"+owner);var charges=rewriteCharges();charges.reserve(owner,key);assertEquals(9,ledger.balance(owner));
        db.update("UPDATE rewrite_consumption SET created_at=TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP) WHERE creator_id=? AND request_key=?",owner,key);var next=charges.reserve(owner,unique());assertTrue(next.fresh());
        assertEquals(9,ledger.balance(owner));assertEquals("FAILED",db.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?",String.class,owner,key));assertEquals(1,number("SELECT restored FROM p0_credit_allocation WHERE debit_reference=?","rewrite:"+owner+":"+key));
    }

    @Test void rewriteStaleSubmittedReservationNeverRefundsAndNewKeyCannotRun(){
        String owner=newOwner(),key=unique();ledger.credit(owner,10,"rewrite-submitted:"+owner);var charges=rewriteCharges();charges.reserve(owner,key);charges.submitted(owner,key);
        db.update("UPDATE rewrite_consumption SET created_at=TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP) WHERE creator_id=? AND request_key=?",owner,key);
        assertEquals("REWRITE_GENERATION_BUSY",assertThrows(DomainException.class,()->charges.reserve(owner,unique())).code());assertEquals("UNKNOWN",db.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?",String.class,owner,key));assertEquals(9,ledger.balance(owner));assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'",owner));
        charges.succeed(owner,key,"recovered result");assertEquals(9,ledger.balance(owner));
    }

    @Test void rewriteExplicitProviderRejectionRestoresCreditsOnceAndCannotLaterMintAResult(){
        String owner=newOwner(),key=unique();ledger.credit(owner,10,"rewrite-reject:"+owner);var charges=rewriteCharges();InspirationRewriter writer=new InspirationRewriter(){public boolean isConfigured(){return true;}public String rewrite(String t,String l){throw new DomainException("REWRITE_PROVIDER_UNAVAILABLE","explicit rejection");}};
        var service=new InspirationRewriteService(writer,true,3,charges);assertThrows(DomainException.class,()->service.rewrite(owner,"fiction","en",key));charges.fail(owner,key);assertEquals(10,ledger.balance(owner));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'",owner));assertThrows(DomainException.class,()->charges.succeed(owner,key,"unrelated success"));
    }
    @Test void captureReviewIsOrderScopedDurableAndSurvivesRefundAndConsumptionRecovery(){
        var a=paid();var b=paid(a.owner(),ACCOUNT,100);String debit="before-review:"+unique();
        // Fix FIFO order explicitly for this synthetic fixture, independent of UUID tie breaks.
        db.update("UPDATE p0_credit_batch SET created_at=TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP) WHERE reference=?","payment:"+a.order());
        ledger.debit(a.owner(),80,debit);
        ledger.holdPaymentForReview(PROVIDER,ACCOUNT,ENV,a.owner(),a.order(),a.capture(),"USD","synthetic-capture-reversal");
        ledger.holdPaymentForReview(PROVIDER,ACCOUNT,ENV,a.owner(),a.order(),a.capture(),"USD","synthetic-capture-reversal");
        assertEquals(20,held(a));assertEquals(0,held(b));assertEquals(120,ledger.balance(a.owner()));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact='CAPTURE_REVIEW'",a.order()));
        ledger.debit(a.owner(),100,"other-order:"+unique());assertEquals(20,ledger.balance(a.owner()));
        assertThrows(RuntimeException.class,()->ledger.debit(a.owner(),1,"blocked-review:"+unique()));
        ledger.refund(a.owner(),debit);assertEquals(100,held(a));assertEquals(100,ledger.balance(a.owner()));
        success(a,"re_"+unique(),198);assertEquals(80,held(a));assertEquals(80,ledger.balance(a.owner()));
        var restarted=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        assertThrows(RuntimeException.class,()->restarted.debit(a.owner(),1,"restart-review:"+unique()));
        assertEquals("REVIEW",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+a.order()));
        assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_dispute WHERE order_id=?",a.order()));
    }
    @Test void activeLedgerConcurrentOwnersLyricsClaimAndDefiniteRefundDoNotReverseUsageAndRequestLocks() throws Exception {
        var calls=new ArrayList<Runnable>();var tested=new ArrayList<String>();var requests=new JdbcCompositionRequests(db,budget(),tx,ledger,new JdbcCreditConsumptions(db,tx));
        for(int i=0;i<8;i++){String owner=newOwner(),work=work(owner),key=unique();tested.add(owner);ledger.credit(owner,20,"lyrics-concurrent:"+owner);calls.add(()->{assertTrue(requests.claim(owner,work,key,"independent-offline-request"));requests.failed(work,key,"LYRICS_PROVIDER_UNAVAILABLE");});}
        concurrent(calls);
        for(String owner:tested){assertEquals(20,ledger.balance(owner));assertEquals(1,number("SELECT COUNT(*) FROM credit_consumption WHERE owner_id=? AND task_status='FAILED' AND refunded=1",owner));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='REFUND'",owner));}
    }

    @Test void activeLedgerRewriteReservationAndEveryStatusWriterShareAdmissionOrderAcrossOwners() throws Exception {
        var calls=new ArrayList<Runnable>();var tested=new ArrayList<String>();var providerCalls=new java.util.concurrent.atomic.AtomicInteger();InspirationRewriter provider=new InspirationRewriter(){public boolean isConfigured(){return true;}public String rewrite(String text,String language){providerCalls.incrementAndGet();return "independent valid result";}};
        var service=new InspirationRewriteService(provider,true,12,rewriteCharges());
        for(int i=0;i<10;i++){String owner=newOwner();tested.add(owner);ledger.credit(owner,20,"rewrite-concurrent:"+owner);calls.add(()->{for(int n=0;n<6;n++)assertEquals("independent valid result",service.rewrite(owner,"fiction","en",unique()));});}
        concurrent(calls);assertEquals(60,providerCalls.get());
        for(String owner:tested){assertEquals(14,ledger.balance(owner));assertEquals(6,number("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=? AND status='SUCCEEDED' AND refunded=0",owner));assertEquals(6,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='DEBIT'",owner));}
    }
    private static JdbcRewriteCharges rewriteCharges(){CreditsPricing pricing=new CreditsPricing(){public boolean enabled(){return true;}public long cost(Kind kind){return 1;}public long freePerDay(Kind kind){return 0;}};return new JdbcRewriteCharges(db,tx,ledger,pricing,new JdbcDailyQuota(db,Clock.systemUTC(),ZoneOffset.UTC),100,1);}

    private static final String PAYPAL_MERCHANT="MERCHANT123",REPORT_PROOF="a".repeat(64);
    private static Paid paypalPaid(){
        String owner=newOwner(),id=unique(),capture="CAP"+unique().replace("-","").substring(0,20),remote="ORD"+unique().replace("-","").substring(0,20);
        tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);
            db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_link_id,provider_capture_id) VALUES(?,?,'starter-USD',9.90,'USD',100,'PAID',CURRENT_TIMESTAMP,'PAYPAL_SANDBOX',?,?)",id,owner,remote,capture);
            ledger.credit(owner,100,"payment:"+id);ledger.bindPayment("PAYPAL",PAYPAL_MERCHANT,ENV,owner,id,capture,"USD");
        });return new Paid(owner,id,capture,PAYPAL_MERCHANT);
    }
    private static PaymentRiskService paypalRisks(){return new PaymentRiskService(db,tx,ledger);}
    private static Map<String,String> paypalReview(Paid p){
        var risks=paypalRisks();risks.recordVerified("PAYPAL_SANDBOX",p.capture(),"PAYMENT.CAPTURE.STATUS_RISK",p.order(),"ev-original");
        ledger.holdPaymentForReview("PAYPAL",p.merchant(),ENV,p.owner(),p.order(),p.capture(),"USD","synthetic-provider-risk");
        var row=db.queryForMap("SELECT id,last_event_id FROM payment_risk_case WHERE order_id=?",p.order());
        return Map.of((String)row.get("id"),(String)row.get("last_event_id"));
    }
    private static void closePaypalCases(Map<String,String> cases){cases.forEach((id,event)->paypalRisks().resolveCase(id,event,"RELEASE","isolated-proof-fixture","Synthetic complete native reporting proof for isolated transaction test"));}
    private static void paypalRelease(Paid p,Map<String,String> cases,long refunds,long losses){ledger.releasePaymentReview("PAYPAL",p.merchant(),ENV,p.owner(),p.order(),p.capture(),"USD",refunds,losses,cases,REPORT_PROOF);}

    @Test void paypalCompleteReviewReleaseIsAuditedOrderScopedIdempotentAndNeverMintsCredits(){
        var p=paypalPaid();var other=paid(p.owner(),ACCOUNT,100);var cases=paypalReview(p);assertEquals(100,held(p));assertEquals(0,held(other));
        closePaypalCases(cases);assertEquals(100,held(p),"manual review closure alone must not clear the entitlement hold");
        paypalRelease(p,cases,0,0);paypalRelease(p,cases,0,0);
        assertEquals("ACTIVE",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+p.order()));
        assertEquals(0,held(p));assertEquals(0,held(other));assertEquals(200,ledger.balance(p.owner()));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'REVIEW_RELEASE:%'",p.order()));
        assertEquals(0,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type IN ('PAYMENT_ADJUST','PAYMENT_RETURN','REFUND_RETURN')",p.owner()));
    }
    @Test void paypalReviewCannotBeClearedByOpenDisputeWrongScopeStaleEventOrMissingCases(){
        var p=paypalPaid();var cases=paypalReview(p);
        assertThrows(IllegalStateException.class,()->paypalRelease(p,cases,0,0));closePaypalCases(cases);
        assertThrows(IllegalStateException.class,()->paypalRelease(p,Map.of(),0,0));
        assertThrows(IllegalStateException.class,()->ledger.releasePaymentReview("PAYPAL","OTHER1234",ENV,p.owner(),p.order(),p.capture(),"USD",0,0,cases,REPORT_PROOF));
        ledger.paymentDispute("PAYPAL",p.merchant(),ENV,"PP-D-"+unique(),p.owner(),p.order(),p.capture(),330,"USD",1,"OPEN",null);
        assertThrows(IllegalStateException.class,()->paypalRelease(p,cases,0,0));assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));
        assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'REVIEW_RELEASE:%'",p.order()));
    }
    @Test void paypalReleaseRacingNewRiskEventCannotLeaveNewAuthoritativeRiskUnheld() throws Exception {
        for(int attempt=0;attempt<5;attempt++){
            var p=paypalPaid();var cases=paypalReview(p);closePaypalCases(cases);var releaseRejected=new java.util.concurrent.atomic.AtomicBoolean();
            concurrent(List.of(()->{try{paypalRelease(p,cases,0,0);}catch(IllegalStateException stale){assertTrue(stale.getMessage().startsWith("PAYMENT_REVIEW_"));releaseRejected.set(true);}},()->tx.executeWithoutResult(t->{
                db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",p.owner());
                paypalRisks().recordVerified("PAYPAL_SANDBOX",p.capture(),"PAYMENT.CAPTURE.REVERSED",p.order(),"ev-new");
                ledger.holdPaymentForReview("PAYPAL",p.merchant(),ENV,p.owner(),p.order(),p.capture(),"USD","new-authoritative-risk");
            })));
            assertEquals("REVIEW",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+p.order()));
            assertEquals("ev-new",db.queryForObject("SELECT last_event_id FROM payment_risk_case WHERE order_id=?",String.class,p.order()));
            assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));
            assertEquals(releaseRejected.get()?0:1,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'REVIEW_RELEASE:%'",p.order()));
        }
    }
    @Test void paypalRefundCommitWhileOldProofWaitsForOwnerLockRejectsStaleRelease() throws Exception {
        var p=paypalPaid();var cases=paypalReview(p);closePaypalCases(cases);
        var ownerLocked=new CountDownLatch(1);var releaseAttempted=new CountDownLatch(1);var rejected=new java.util.concurrent.atomic.AtomicBoolean();
        concurrent(List.of(()->tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",p.owner());ownerLocked.countDown();
            try{assertTrue(releaseAttempted.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            ledger.paymentRefund("PAYPAL",p.merchant(),ENV,"REF"+unique().replace("-","").substring(0,20),p.owner(),p.order(),p.capture(),198,"USD");
        }),()->{
            try{assertTrue(ownerLocked.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            releaseAttempted.countDown();var stale=assertThrows(IllegalStateException.class,()->paypalRelease(p,cases,0,0));assertEquals("PAYMENT_REVIEW_FINANCIAL_FACT_CHANGED",stale.getMessage());rejected.set(true);
        }));
        assertTrue(rejected.get());assertEquals(80,ledger.balance(p.owner()));assertEquals(80,held(p));assertEquals(20,adjusted(p));
        paypalRelease(p,cases,198,0);assertEquals(80,ledger.balance(p.owner()));assertEquals(0,held(p));
        assertEquals(1,number("SELECT COUNT(*) FROM p0_credit_refund WHERE order_id=?",p.order()));
    }
    private static void nativePaypalLoss(Paid p,String dispute,long loss){
        db.update("INSERT INTO p0_stripe_dispute_proof(provider,merchant,environment,dispute_id,order_id,capture_id,owner_id,loss_minor,withdrawal_id,charge_balance_id,settlement_currency,settlement_principal_minor,evidence_sha256,created_at) VALUES('PAYPAL',?,?,?,?,?,?,?,?,?,'USD',990,?,CURRENT_TIMESTAMP)",p.merchant(),ENV,dispute,p.order(),p.capture(),p.owner(),loss,"WITH"+unique().replace("-","").substring(0,20),p.capture(),REPORT_PROOF);
    }
    private static void settlePaypalLoss(Paid p,String dispute,long version,long loss,String proof){ledger.settlePayPalReportedLoss(p.merchant(),ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",version,loss,proof);}
    @Test void paypalLateReportingFillsSameVersionUnknownPrincipalOnlyWithExactPersistedNativeProof(){
        var p=paypalPaid();String dispute="PP-D-"+unique();ledger.paymentDispute("PAYPAL",p.merchant(),ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",7,"LOST",null);
        assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));
        assertThrows(IllegalStateException.class,()->settlePaypalLoss(p,dispute,7,330,REPORT_PROOF));
        assertThrows(IllegalStateException.class,()->ledger.paymentDispute("PAYPAL",p.merchant(),ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",7,"LOST",330L),"generic same-version transition must remain blocked");
        nativePaypalLoss(p,dispute,330);
        assertThrows(IllegalStateException.class,()->settlePaypalLoss(p,dispute,7,330,"b".repeat(64)));
        assertThrows(IllegalStateException.class,()->settlePaypalLoss(p,dispute,6,330,REPORT_PROOF));
        assertThrows(IllegalStateException.class,()->ledger.settlePayPalReportedLoss("OTHER1234",ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",7,330,REPORT_PROOF));
        var other=paypalPaid();assertThrows(IllegalStateException.class,()->settlePaypalLoss(other,dispute,7,330,REPORT_PROOF));
        settlePaypalLoss(p,dispute,7,330,REPORT_PROOF);settlePaypalLoss(p,dispute,7,330,REPORT_PROOF);
        assertEquals(66,ledger.balance(p.owner()));assertEquals(34,adjusted(p));assertEquals(0,held(p));assertEquals(100,ledger.balance(other.owner()));
        assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='PAYMENT_ADJUST'",p.owner()));
        assertEquals(330,number("SELECT loss_minor FROM p0_credit_dispute WHERE external_id=?",dispute));assertEquals(7,number("SELECT version FROM p0_credit_dispute WHERE external_id=?",dispute));
    }
    @Test void paypalLateSameVersionReportingIsConcurrentIdempotentAndCannotBypassRefundOverlap() throws Exception {
        var p=paypalPaid();String dispute="PP-D-"+unique();ledger.paymentDispute("PAYPAL",p.merchant(),ENV,dispute,p.owner(),p.order(),p.capture(),990,"USD",7,"LOST",null);nativePaypalLoss(p,dispute,330);
        concurrent(List.of(()->settlePaypalLoss(p,dispute,7,330,REPORT_PROOF),()->settlePaypalLoss(p,dispute,7,330,REPORT_PROOF)));
        assertEquals(66,ledger.balance(p.owner()));assertEquals(34,adjusted(p));assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='PAYMENT_ADJUST'",p.owner()));
        var q=paypalPaid();String conflicting="PP-D-"+unique();ledger.paymentRefund("PAYPAL",q.merchant(),ENV,"REF"+unique().replace("-","").substring(0,20),q.owner(),q.order(),q.capture(),198,"USD");
        ledger.paymentDispute("PAYPAL",q.merchant(),ENV,conflicting,q.owner(),q.order(),q.capture(),990,"USD",7,"LOST",null);nativePaypalLoss(q,conflicting,330);
        assertThrows(IllegalStateException.class,()->settlePaypalLoss(q,conflicting,7,330,REPORT_PROOF));assertEquals(80,ledger.balance(q.owner()));assertEquals(20,adjusted(q));assertEquals(80,held(q));
        assertEquals(1,number("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='PAYMENT_ADJUST'",q.owner()));
    }
    @Test void humanHoldCommittedWhileReportReleaseWaitsForOwnerLockCannotBeAutomaticallyOverridden() throws Exception {
        var p=paypalPaid();var cases=paypalReview(p);closePaypalCases(cases);var entry=cases.entrySet().iterator().next();
        var ownerLocked=new CountDownLatch(1);var reportReturned=new CountDownLatch(1);
        concurrent(List.of(()->tx.executeWithoutResult(t->{
            db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",p.owner());ownerLocked.countDown();
            try{assertTrue(reportReturned.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            paypalRisks().resolveCase(entry.getKey(),entry.getValue(),"HOLD","human-reviewer","Manual decision must take precedence over a previously fetched reporting proof");
        }),()->{
            try{assertTrue(ownerLocked.await(10,TimeUnit.SECONDS));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            reportReturned.countDown();var rejected=assertThrows(IllegalStateException.class,()->paypalRelease(p,cases,0,0));assertEquals("PAYMENT_REVIEW_CASE_NOT_RESOLVED",rejected.getMessage());
        }));
        assertEquals(100,held(p));assertEquals(100,ledger.balance(p.owner()));assertEquals("REVIEW",db.queryForObject("SELECT status FROM p0_credit_batch WHERE reference=?",String.class,"payment:"+p.order()));
        assertEquals("HOLD",db.queryForObject("SELECT decision FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));assertEquals("human-reviewer",db.queryForObject("SELECT reviewed_by FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));
        assertEquals(0,number("SELECT COUNT(*) FROM p0_credit_financial_audit WHERE order_id=? AND fact LIKE 'REVIEW_RELEASE:%'",p.order()));
    }
}
