package example.payment;

import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import example.payment.CreditGrantOutbox.*;

class CreditGrantOutboxTest {
    java.sql.Connection schemaConnection;
    JdbcTemplate db;DataSourceTransactionManager manager;CreditGrantOutbox service;
    @BeforeEach void setup()throws Exception{
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        schemaConnection=source.getConnection();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).populate(schemaConnection);
        db=new JdbcTemplate(source);manager=new DataSourceTransactionManager(source);
        db.execute("CREATE TABLE demo_ledger(reference VARCHAR(160) PRIMARY KEY,owner_id VARCHAR(36),amount BIGINT)");
        service=make(new CrashProbe(){},3,0);
    }
    @AfterEach void closeSchemaConnection()throws Exception{schemaConnection.close();}
    CreditGrantOutbox make(CrashProbe probe,int max,int delay){return new CreditGrantOutbox(db,manager,(msg,key)->{
        // Test double only; production uses the complete fenced ledger + native payment binding.
        var existing=db.queryForList("SELECT owner_id,amount FROM demo_ledger WHERE reference=?",key);
        if(existing.isEmpty())db.update("INSERT INTO demo_ledger VALUES(?,?,?)",key,msg.ownerId(),msg.credits());
        else {assertEquals(msg.ownerId(),existing.get(0).get("owner_id"));assertEquals(msg.credits(),((Number)existing.get(0).get("amount")).longValue());}
    },probe,max,delay,60);}
    VerifiedPayment fixture(String order,String owner,String capture){
        if(db.queryForList("SELECT id FROM demo_owner WHERE id=?",owner).isEmpty())db.update("INSERT INTO demo_owner VALUES(?)",owner);
        db.update("INSERT INTO demo_payment_order(id,owner_id,amount_minor,currency,credits,provider,merchant,environment,status) VALUES(?,?,990,'USD',100,'STRIPE','merchant','sandbox','PENDING')",order,owner);
        return new VerifiedPayment(order,owner,990,"USD","STRIPE","merchant","sandbox",capture);
    }
    String state(String order){return db.queryForObject("SELECT state FROM demo_credit_outbox WHERE order_id=?",String.class,order);}
    void expire(String order){db.update("UPDATE demo_credit_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE order_id=?",order);}
    @Test void paymentCommitSurvivesWorkerRestart(){
        var fact=fixture("a","owner","capture-a");service.confirm(fact);
        assertEquals("READY",state("a"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_ledger",Integer.class));
        var restarted=make(new CrashProbe(){},3,0);restarted.scan();
        assertEquals("DONE",state("a"));assertEquals(100L,db.queryForObject("SELECT SUM(amount) FROM demo_ledger",Long.class));
    }
    @Test void outerTransactionRollbackRemovesBothOrderChangeAndMessage(){
        var fact=fixture("a","owner","capture-a");
        new TransactionTemplate(manager).executeWithoutResult(tx->{service.confirm(fact);tx.setRollbackOnly();});
        assertEquals("PENDING",db.queryForObject("SELECT status FROM demo_payment_order WHERE id='a'",String.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_credit_outbox",Integer.class));
    }
    @Test void callbackReplayAndFiftyWorkersCreditOnce()throws Exception{
        var fact=fixture("a","owner","capture-a");service.confirm(fact);service.confirm(fact);
        var pool=Executors.newFixedThreadPool(10);
        try{
            var tasks=new ArrayList<Callable<Void>>();for(int i=0;i<50;i++)tasks.add(()->{service.claim().ifPresent(service::deliver);return null;});
            for(var result:pool.invokeAll(tasks))result.get();
        }finally{pool.shutdownNow();}
        assertEquals("DONE",state("a"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_ledger",Integer.class));
        service.confirm(fact);assertEquals("DONE",state("a"));
    }
    @Test void expiredWorkerIsFencedAndNewWorkerRecovers(){
        service.confirm(fixture("a","owner","capture-a"));var stale=service.claim().orElseThrow();expire("a");
        var next=service.claim().orElseThrow();assertNotEquals(stale.token(),next.token());
        assertFalse(service.deliver(stale));assertTrue(service.deliver(next));assertEquals("DONE",state("a"));
        service.failed(stale,new IllegalStateException());assertEquals("DONE",state("a"));
    }
    @Test void crashAfterLedgerBeforeDoneRollsBackAndRecovers(){
        service.confirm(fixture("a","owner","capture-a"));var claim=service.claim().orElseThrow();
        var crashing=make(new CrashProbe(){public void creditedBeforeDone(String id){throw new SimulatedProcessDeath();}},3,0);
        assertThrows(SimulatedProcessDeath.class,()->crashing.deliver(claim));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_ledger",Integer.class));
        assertEquals("PROCESSING",state("a"));expire("a");service.scan();assertEquals("DONE",state("a"));
    }
    static class SimulatedProcessDeath extends Error {}
    @Test void exceptionsBackoffAndDeadLetterAlertAreDurable(){
        service=make(new CrashProbe(){public void creditedBeforeDone(String id){throw new IllegalStateException("do not log secrets");}},1,0);
        service.confirm(fixture("a","owner","capture-a"));service.scan();
        assertEquals("DEAD",state("a"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_credit_alert",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_ledger",Integer.class));
        service.scan();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_credit_alert",Integer.class));
    }
    @Test void retryWaitAndThreeMinuteCompensationDelay(){
        service=make(new CrashProbe(){},3,180);service.confirm(fixture("a","owner","capture-a"));
        assertTrue(service.claim().isEmpty());db.update("UPDATE demo_credit_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE order_id='a'");
        var claim=service.claim().orElseThrow();service.failed(claim,new IllegalStateException());
        assertEquals("READY",state("a"));assertTrue(service.claim().isEmpty());
    }
    @Test void repeatedProcessDeathEventuallyAlerts(){
        service=make(new CrashProbe(){},1,0);service.confirm(fixture("a","owner","capture-a"));service.claim().orElseThrow();expire("a");
        assertTrue(service.claim().isEmpty());assertEquals("DEAD",state("a"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_credit_alert",Integer.class));
    }
    @Test void nativeCaptureCannotCreditTwoOrders(){
        var first=fixture("a","one","same-capture");var second=fixture("b","two","same-capture");service.confirm(first);
        assertThrows(RuntimeException.class,()->service.confirm(second));
        assertEquals("PENDING",db.queryForObject("SELECT status FROM demo_payment_order WHERE id='b'",String.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM demo_credit_outbox",Integer.class));
    }
    @Test void mismatchedPaymentDoesNotConfirmOrPublish(){
        fixture("a","owner","capture-a");
        assertThrows(IllegalStateException.class,()->service.confirm(new VerifiedPayment("a","owner",1,"USD","STRIPE","merchant","sandbox","capture-a")));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM demo_credit_outbox",Integer.class));
    }
}
