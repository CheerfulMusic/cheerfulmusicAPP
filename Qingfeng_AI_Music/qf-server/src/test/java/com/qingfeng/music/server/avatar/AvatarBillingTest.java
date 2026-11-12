package com.qingfeng.music.server.avatar;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC ledger and transaction boundaries; MySQL mode is restricted to an explicitly identified isolated container. */
class AvatarBillingTest {
    JdbcTemplate db; TransactionTemplate tx; CreditsLedger ledger; CreditsPricing pricing;
    AvatarService avatars; AvatarImageProvider provider; AvatarGenerationService service;
    @BeforeEach void setup() {
        var ds=isolatedDataSource();
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE creator (id VARCHAR(36) PRIMARY KEY,payment_risk_frozen INT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE credit_account (owner_id VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        db.execute("CREATE TABLE credit_ledger (entry_type VARCHAR(16) NOT NULL,reference_id VARCHAR(160) NOT NULL,owner_id VARCHAR(36) NOT NULL,amount BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,PRIMARY KEY(entry_type,reference_id))");
        db.execute("CREATE TABLE avatar_generation_task (id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) NOT NULL,request_key VARCHAR(36) NOT NULL,prompt VARCHAR(1000) NOT NULL,expected_version VARCHAR(36),status VARCHAR(24) NOT NULL,provider_task_id VARCHAR(255),lease_token VARCHAR(36),lease_until TIMESTAMP,next_attempt_at TIMESTAMP,failures INT NOT NULL DEFAULT 0,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(creator_id,request_key))");
        db.update("INSERT INTO creator(id) VALUES ('alice'),('bob')");
        ledger=new JdbcCreditsLedger(db,tx); pricing=mock(CreditsPricing.class); when(pricing.enabled()).thenReturn(true);
        avatars=mock(AvatarService.class); when(avatars.current(anyString())).thenReturn(new AvatarService.View(null,null));
        provider=mock(AvatarImageProvider.class); when(provider.configured()).thenReturn(true);
        service=new AvatarGenerationService(db,tx,avatars,provider,ledger,pricing,10);
    }
    private DriverManagerDataSource isolatedDataSource() {
        String expected=System.getenv("AVATAR_BILLING_MYSQL_UUID");
        if(expected==null || expected.isBlank()) return new DriverManagerDataSource("jdbc:h2:mem:avatar_billing_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        if(!expected.matches("[0-9a-f-]{36}"))throw new IllegalStateException("Invalid isolated server identity");
        String base="jdbc:mysql://127.0.0.1:23316/", suffix="?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false";
        var admin=new JdbcTemplate(new DriverManagerDataSource(base+"mysql"+suffix,"root","codex-avatar-only-2026"));
        String identity=admin.queryForObject("SELECT @@server_uuid",String.class);
        if(!expected.equals(identity))throw new IllegalStateException("Unexpected MySQL server");
        String schema="avatar_bill_"+UUID.randomUUID().toString().replace("-","");
        if(!schema.matches("avatar_bill_[0-9a-f]{32}"))throw new IllegalStateException("Invalid isolated schema");
        admin.execute("CREATE DATABASE `"+schema+"`");
        var ds=new DriverManagerDataSource(base+schema+suffix,"root","codex-avatar-only-2026");
        var check=new JdbcTemplate(ds);
        if(!schema.equals(check.queryForObject("SELECT DATABASE()",String.class)) || !expected.equals(check.queryForObject("SELECT @@server_uuid",String.class)))throw new IllegalStateException("Unexpected isolated database");
        return ds;
    }
    void fund(String owner,long amount) { ledger.credit(owner,amount,"seed:"+UUID.randomUUID()); }
    int count(String type) { return db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type=?",Integer.class,type); }

    @Test void successAndRepeatedRequestChargeExactlyFiveOnce() throws Exception {
        fund("alice",20); when(provider.submit(anyString())).thenReturn("remote");
        String key=UUID.randomUUID().toString(); var first=service.start("alice",key,"猫");
        assertEquals(15,ledger.balance("alice")); assertEquals(1,count("DEBIT"));
        assertEquals(first.id(),service.start("alice",key,"猫").id()); verify(provider,times(1)).submit("猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","https://example.test/result"));
        when(provider.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(avatars.saveIfVersion(eq("alice"),isNull(),any(),eq(first.id()))).thenReturn(Optional.of(new AvatarService.View("image",first.id())));
        service.runOnce(); service.runOnce();
        assertEquals("SUCCEEDED",service.latest("alice").status()); assertEquals(15,ledger.balance("alice")); assertEquals(0,count("REFUND"));
    }
    @Test void definitiveRejectionAndFinalFailureRefundOnce() {
        fund("alice",20); when(provider.submit(anyString())).thenThrow(new AvatarImageProvider.Rejected());
        String key=UUID.randomUUID().toString(); assertEquals("FAILED",service.start("alice",key,"猫").status());
        assertEquals(20,ledger.balance("alice")); assertEquals(1,count("DEBIT")); assertEquals(1,count("REFUND"));
        service.start("alice",key,"猫"); service.runOnce(); assertEquals(1,count("REFUND"));
        reset(provider); when(provider.configured()).thenReturn(true); when(provider.submit(anyString())).thenReturn("remote");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("FAILED",null));
        service.start("alice",UUID.randomUUID().toString(),"狗"); service.runOnce(); service.runOnce();
        assertEquals(20,ledger.balance("alice")); assertEquals(2,count("DEBIT")); assertEquals(2,count("REFUND"));
    }
    @Test void insufficientBalanceFrozenAccountAndDisabledCreditsNeverSubmit() {
        fund("alice",4); when(provider.submit(anyString())).thenReturn("remote");
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"猫"));
        assertEquals(4,ledger.balance("alice")); assertEquals(0,count("DEBIT"));
        fund("bob",20); db.update("UPDATE creator SET payment_risk_frozen=1 WHERE id='bob'");
        assertThrows(DomainException.class,()->service.start("bob",UUID.randomUUID().toString(),"猫"));
        assertEquals(20,ledger.balance("bob")); assertEquals(0,count("DEBIT"));
        when(pricing.enabled()).thenReturn(false);
        assertThrows(DomainException.class,()->service.start("alice",UUID.randomUUID().toString(),"猫"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM avatar_generation_task",Integer.class)); verify(provider,never()).submit(anyString());
    }
    @Test void unknownNeverResubmitsOrRefunds() {
        fund("alice",20); when(provider.submit(anyString())).thenThrow(new IllegalStateException("unknown outcome"));
        String key=UUID.randomUUID().toString(); assertEquals("UNKNOWN",service.start("alice",key,"猫").status());
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status()); service.runOnce();
        assertEquals(15,ledger.balance("alice")); assertEquals(1,count("DEBIT")); assertEquals(0,count("REFUND")); verify(provider,times(1)).submit("猫");
    }
    @Test void lateRejectionAfterUnknownNeverClaimsRefund() {
        fund("alice",20);
        when(provider.submit(anyString())).thenAnswer(invocation -> {
            db.update("UPDATE avatar_generation_task SET updated_at=? WHERE creator_id='alice'",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(180)));
            service.runOnce();
            throw new AvatarImageProvider.Rejected();
        });
        String key=UUID.randomUUID().toString();
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status());
        assertEquals(15,ledger.balance("alice")); assertEquals(1,count("DEBIT")); assertEquals(0,count("REFUND"));
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status()); verify(provider,times(1)).submit("猫");
    }
    @Test void preexistingFreeSubmittingTaskIsNeverDispatchedOrCharged() {
        fund("alice",20); String key=UUID.randomUUID().toString(), id=UUID.randomUUID().toString();
        db.update("INSERT INTO avatar_generation_task(id,creator_id,request_key,prompt,status,created_at,updated_at) VALUES(?,?,?,?,'SUBMITTING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,"alice",key,"猫");
        assertEquals("UNKNOWN",service.start("alice",key,"猫").status());
        assertEquals(20,ledger.balance("alice")); assertEquals(0,count("DEBIT")); assertEquals(0,count("REFUND")); verify(provider,never()).submit(anyString());
        db.update("UPDATE avatar_generation_task SET updated_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(180)),id);
        service.runOnce(); assertEquals("UNKNOWN",service.latest("alice").status());
        assertEquals(20,ledger.balance("alice")); verify(provider,never()).submit(anyString());
    }
    @Test void uploadDuringGenerationRefundsUndeliveredAvatar() throws Exception {
        fund("alice",20); when(avatars.current("alice")).thenReturn(new AvatarService.View("old","old-version"));
        when(provider.submit(anyString())).thenReturn("remote"); var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","https://example.test/result"));
        when(provider.download(anyString())).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(avatars.saveIfVersion(eq("alice"),eq("old-version"),any(),eq(task.id()))).thenReturn(Optional.empty());
        when(avatars.current("alice")).thenReturn(new AvatarService.View("new","new-version"));
        service.runOnce(); service.runOnce();
        assertEquals("SUPERSEDED",service.latest("alice").status()); assertEquals(20,ledger.balance("alice")); assertEquals(1,count("REFUND"));
    }
    @Test void debitFailureRollsBackTaskAndLedger() {
        fund("alice",20); CreditsLedger failing=new CreditsLedger() {
            public void credit(String owner,long amount,String reference){ledger.credit(owner,amount,reference);}
            public void debit(String owner,long amount,String reference){ledger.debit(owner,amount,reference);throw new IllegalStateException("after debit");}
            public void refund(String owner,String reference){ledger.refund(owner,reference);}
            public long balance(String owner){return ledger.balance(owner);}
        };
        var failingService=new AvatarGenerationService(db,tx,avatars,provider,failing,pricing,10);
        assertThrows(IllegalStateException.class,()->failingService.start("alice",UUID.randomUUID().toString(),"猫"));
        assertEquals(20,ledger.balance("alice")); assertEquals(0,count("DEBIT")); assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM avatar_generation_task",Integer.class));
        verify(provider,never()).submit(anyString());
    }
    @Test void refundFailureRollsBackTerminalStateAndCanRecoverOnce() {
        fund("alice",20); when(provider.submit(anyString())).thenReturn("remote");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("FAILED",null));
        CreditsLedger failing=new CreditsLedger() {
            public void credit(String owner,long amount,String reference){ledger.credit(owner,amount,reference);}
            public void debit(String owner,long amount,String reference){ledger.debit(owner,amount,reference);}
            public void refund(String owner,String reference){ledger.refund(owner,reference);throw new IllegalStateException("after refund");}
            public long balance(String owner){return ledger.balance(owner);}
        };
        var unstable=new AvatarGenerationService(db,tx,avatars,provider,failing,pricing,10);
        var task=unstable.start("alice",UUID.randomUUID().toString(),"猫"); unstable.runOnce();
        assertEquals("RUNNING",unstable.latest("alice").status()); assertEquals(15,ledger.balance("alice")); assertEquals(0,count("REFUND"));
        db.update("UPDATE avatar_generation_task SET next_attempt_at=NULL WHERE id=?",task.id());
        service.runOnce(); service.runOnce();
        assertEquals("FAILED",service.latest("alice").status()); assertEquals(20,ledger.balance("alice")); assertEquals(1,count("REFUND"));
    }
    @Test void concurrentSameRequestChargesAndSubmitsOnce() throws Exception {
        fund("alice",20); when(provider.submit(anyString())).thenReturn("remote"); String key=UUID.randomUUID().toString();
        var executor=Executors.newFixedThreadPool(2); var barrier=new CountDownLatch(1);
        try {
            var first=executor.submit(()->{barrier.await();return service.start("alice",key,"猫");});
            var second=executor.submit(()->{barrier.await();return service.start("alice",key,"猫");});
            barrier.countDown(); var a=first.get(10,TimeUnit.SECONDS); var b=second.get(10,TimeUnit.SECONDS);
            assertEquals(a.id(),b.id()); assertEquals(15,ledger.balance("alice")); assertEquals(1,count("DEBIT")); verify(provider,times(1)).submit("猫");
        } finally { executor.shutdownNow(); }
    }
}
