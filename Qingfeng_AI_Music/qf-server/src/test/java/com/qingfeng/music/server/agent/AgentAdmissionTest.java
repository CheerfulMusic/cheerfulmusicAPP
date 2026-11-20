package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentAdmissionTest {
    JdbcDataSource source;JdbcTemplate jdbc;
    @BeforeEach void setup(){
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:admission_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");jdbc.execute("CREATE TABLE agent_conversation(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),history_epoch BIGINT DEFAULT 0 NOT NULL)");
        jdbc.execute("CREATE TABLE agent_message(id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(36),role VARCHAR(16),state VARCHAR(16))");
        jdbc.execute("ALTER TABLE agent_message ADD (kind VARCHAR(16), seq BIGINT, content_json TEXT, created_at TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V35__agent_admission.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V46__agent_chat_allowance.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V53__agent_admission_version.sql")).execute(source);
        for(int i=0;i<20;i++){jdbc.update("INSERT INTO creator(id) VALUES(?)","o"+i);jdbc.update("INSERT INTO agent_conversation(id,creator_id) VALUES(?,?)","c"+i,"o"+i);}
    }
    AgentAdmission.Limits limits(int concurrent,int rpm,long tokens){return new AgentAdmission.Limits(concurrent,1,rpm,rpm,tokens,tokens,1024,Duration.ofSeconds(60));}
    JdbcAgentAdmission gate(AgentAdmission.Limits limits){return new JdbcAgentAdmission(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),limits,ZoneOffset.UTC);}
    String key(){return UUID.randomUUID().toString();}
    @Test void independentInstancesCannotOvershootGlobalAdmission()throws Exception{
        var first=gate(limits(3,60,1_000_000));var second=gate(limits(3,60,1_000_000));var start=new CountDownLatch(1);var accepted=new AtomicInteger();var pool=Executors.newFixedThreadPool(12);
        try{List<Future<?>> futures=new ArrayList<>();for(int i=0;i<12;i++){int n=i;futures.add(pool.submit(()->{start.await();try{(n%2==0?first:second).acquire("o"+n,"c"+n,key(),"你好","zh-CN");accepted.incrementAndGet();}catch(DomainException denied){assertEquals("AGENT_BUSY",denied.code());}return null;}));}start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);assertEquals(3,accepted.get());assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Integer.class));}finally{pool.shutdownNow();}
    }
    @Test void retryReusesReservationAndChangedTextIsRejected(){
        var gate=gate(limits(8,60,1_000_000));String key=key();var ticket=gate.acquire("o0","c0",key,"你好","zh-CN");gate.start(ticket);
        assertEquals("AGENT_TURN_IN_PROGRESS",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key,"你好","zh-CN")).code());
        gate.finish(ticket,true);assertTrue(gate.acquire("o0","c0",key,"你好","zh-CN").replay());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Integer.class));
        assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key,"生成音乐","zh-CN")).code());
    }
    @Test void globalTokenBudgetAndRpmRemainAfterCompletion(){
        var gate=gate(limits(8,60,7248));var first=gate.acquire("o0","c0",key(),"你好",null);gate.start(first);gate.finish(first,true);
        assertEquals("AGENT_TOKEN_BUDGET_EXCEEDED",assertThrows(DomainException.class,()->gate.acquire("o1","c1",key(),"你好",null)).code());
        var rpmGate=gate(limits(8,1,1_000_000));assertEquals("AGENT_RATE_LIMITED",assertThrows(DomainException.class,()->rpmGate.acquire("o1","c1",key(),"你好",null)).code());
    }
    @Test void expiredRequestDoesNotReplayAndCannotRenewButReleasesCapacity(){
        var gate=gate(limits(1,60,1_000_000));String key=key();var first=gate.acquire("o0","c0",key,"你好",null);gate.start(first);
        jdbc.update("UPDATE agent_request_admission SET lease_until=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(20)),first.id());
        assertThrows(DomainException.class,()->gate.heartbeat(first));assertThrows(DomainException.class,()->gate.acquire("o0","c0",key,"你好",null));
        assertFalse(gate.acquire("o1","c1",key(),"你好",null).replay());
    }
    @Test void failedTurnIsTerminalOnRetry() {
        var gate=gate(limits(1,60,1_000_000));
        String key=key();
        var ticket=gate.acquire("o0","c0",key,"你好",null);
        gate.start(ticket); gate.finish(ticket,false);
        String turn=UUID.nameUUIDFromBytes(("c0:"+key).getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.update("INSERT INTO agent_message(id,conversation_id,role,state) VALUES(?,'c0','user','FAILED')",turn);
        assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,
            ()->gate.acquire("o0","c0",key,"你好",null)).code());
    }
    @Test void lostCompletionAcknowledgementRecoversOnlyCommittedTurn(){
        var gate=gate(limits(1,60,1_000_000));String key=key();var ticket=gate.acquire("o0","c0",key,"你好",null);gate.start(ticket);
        String turn=UUID.nameUUIDFromBytes(("c0:"+key).getBytes(StandardCharsets.UTF_8)).toString();jdbc.update("INSERT INTO agent_message(id,conversation_id,role,state) VALUES(?,'c0','user','DONE')",turn);
        assertTrue(gate.acquire("o0","c0",key,"你好",null).replay());
    }
    @Test void redisOutageNeverFallsBackToUnprotectedExecution(){
        var limits=limits(8,60,1_000_000);var redis=mock(StringRedisTemplate.class);when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(Object[].class))).thenThrow(new IllegalStateException("offline"));
        var guarded=new RedisAgentAdmission(gate(limits),redis,limits,"test");assertThrows(DomainException.class,()->guarded.acquire("o0","c0",key(),"你好",null));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Integer.class));
    }
    @Test void abandonedBeforeStartCanRetryWithoutSpendingTokens(){
        var gate=gate(limits(1,60,7248));String key=key();var first=gate.acquire("o0","c0",key,"你好",null);gate.abandon(first);assertFalse(gate.acquire("o0","c0",key,"你好",null).replay());
    }

    com.qingfeng.music.credits.CreditsLedger billing(){
        jdbc.execute("ALTER TABLE creator ADD payment_risk_frozen INT DEFAULT 0 NOT NULL");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V15__credits_ledger.sql")).execute(source);
        jdbc.execute("CREATE TABLE credit_account(owner_id VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL,updated_at TIMESTAMP)");
        var ledger=new com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)));
        ledger.credit("o0",3,"seed");return ledger;
    }
    JdbcAgentAdmission paidGate(com.qingfeng.music.credits.CreditsLedger ledger){
        return gate(new AgentAdmission.Limits(8,1,60,60,1000000,7248,1024,Duration.ofSeconds(60))).withCredits(ledger);
    }
    void useFree(AgentAdmission gate){var t=gate.acquire("o0","c0",key(),"你好",null);gate.start(t);gate.finish(t,true);}
    private com.qingfeng.music.agent.api.AgentTurnStore turnStore() {
        return new com.qingfeng.music.infrastructure.persistence.agent.JdbcAgentTurnStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @Test void archivedRequestCanReenterWithoutRepeatingItsEffects() {
        var gate = gate(limits(1, 60, 1_000_000));
        var store = turnStore();
        String key = key();
        var ticket = gate.acquire("o0", "c0", key, "原消息", null);
        assertTrue(gate.startTurn(ticket, "c0", () -> store.claim("o0","c0",key,"原消息")).fresh());
        gate.finish(ticket, false);
        jdbc.update("UPDATE agent_message SET role='system',kind='REQUEST',state='FAILED' WHERE conversation_id='c0'");
        jdbc.update("UPDATE agent_conversation SET history_epoch=history_epoch+1 WHERE id='c0'");
        var replay = gate.acquire("o0","c0",key,"原消息",null);
        assertTrue(replay.replay());
        assertFalse(gate.startTurn(replay,"c0",() -> store.claim("o0","c0",key,"原消息")).fresh());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
    }

    @Test void resetBetweenAdmissionAndWorkerClaimCancelsAndRefunds() throws Exception {
        var ledger = billing(); var gate = paidGate(ledger); useFree(gate);
        String key = key(); var store = turnStore();
        var admitted = new CountDownLatch(1); var reset = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                var ticket = gate.acquire("o0","c0",key,"生成",null,true);
                admitted.countDown();
                assertTrue(reset.await(5,TimeUnit.SECONDS));
                return assertThrows(DomainException.class,() -> gate.startTurn(ticket,"c0",
                        () -> store.claim("o0","c0",key,"生成"))).code();
            });
            assertTrue(admitted.await(5,TimeUnit.SECONDS));
            assertEquals(2,ledger.balance("o0"));
            jdbc.update("UPDATE agent_conversation SET history_epoch=history_epoch+1 WHERE id='c0'");
            reset.countDown();
            assertEquals("REQUEST_KEY_CONFLICT",result.get(5,TimeUnit.SECONDS));
            assertThrows(DomainException.class,() -> gate.acquire("o0","c0",key,"生成",null,true));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
            assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM agent_request_admission WHERE request_key=?",String.class,key));
            assertEquals(3,ledger.balance("o0"));
        } finally {reset.countDown(); worker.shutdownNow();}
    }

    @Test void twoRecoveryWorkersRefundAnExpiredReservationExactlyOnce() throws Exception {
        var ledger = billing(); var gate = paidGate(ledger); useFree(gate);
        var ticket = gate.acquire("o0","c0",key(),"继续",null,true);
        var cutoff = java.sql.Timestamp.from(Instant.now().minusSeconds(360));
        jdbc.update("UPDATE agent_request_admission SET created_at=?,lease_until=? WHERE id=?",cutoff,cutoff,ticket.id());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Callable<Void> sweep = () -> {start.await();gate.recoverExpired();return null;};
            var a = workers.submit(sweep); var b = workers.submit(sweep);
            start.countDown(); a.get(5,TimeUnit.SECONDS); b.get(5,TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertEquals("ABANDONED",jdbc.queryForObject("SELECT state FROM agent_request_admission WHERE id=?",String.class,ticket.id()));
        assertEquals(0,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
        assertEquals(3,ledger.balance("o0"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND'",Integer.class));
    }
    @Test void recoveryRestoresPrepaidTurnAndPreservesRequestsWithExecutionEvidence() {
        var ledger = billing(); var gate = paidGate(ledger); useFree(gate);
        var paid = gate.acquire("o0","c0",key(),"购买次数",null,true);
        gate.start(paid); gate.finish(paid,true);
        String key = key();
        var reserved = gate.acquire("o0","c0",key,"下一次",null,true);
        var expired = java.sql.Timestamp.from(Instant.now().minusSeconds(360));
        jdbc.update("UPDATE agent_request_admission SET created_at=?,lease_until=? WHERE id=?",expired,expired,reserved.id());
        String message = UUID.nameUUIDFromBytes(("c0:"+key).getBytes(StandardCharsets.UTF_8)).toString();
        jdbc.update("INSERT INTO agent_message(id,conversation_id,role,state) VALUES(?,'c0','user','RUNNING')",message);
        gate.recoverExpired();
        assertEquals("RESERVED",jdbc.queryForObject("SELECT state FROM agent_request_admission WHERE id=?",String.class,reserved.id()));
        assertEquals(3,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
        jdbc.update("DELETE FROM agent_message WHERE id=?",message);
        gate.recoverExpired(); gate.recoverExpired();
        assertEquals(4,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
        assertEquals(2,ledger.balance("o0"));
    }

    @Test void onlyIssuedContinuationResumesWithoutSpendingAnotherTurn() {
        var ledger=billing(); var gate=paidGate(ledger); useFree(gate);
        String continuation=key();

        jdbc.update("INSERT INTO agent_message(id,conversation_id,role,state,kind,seq,content_json) VALUES(?,?,?,?,?,?,?)",
            key(),"c0","assistant","DONE","TEXT",1,"{\"kind\":\"waiting\",\"continuationKey\":\""+continuation+"\"}");
        assertThrows(DomainException.class,()->gate.acquire("o0","c0",key(),AgentChatService.CONTINUE,null,true));
        var ticket=gate.acquire("o0","c0",continuation,AgentChatService.CONTINUE,null);
        gate.start(ticket); gate.finish(ticket,true);
        assertTrue(gate.acquire("o0","c0",continuation,AgentChatService.CONTINUE,null).replay());
        assertEquals(3,ledger.balance("o0"));
    }
    @Test void paidTurnRequiresConsentDebitsOnceAndReplaysWithoutAnotherCharge(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);String key=key();
        assertEquals("AGENT_CHAT_PAYMENT_REQUIRED",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key,"继续聊",null)).code());
        assertEquals(3,ledger.balance("o0"));var t=gate.acquire("o0","c0",key,"继续聊",null,true);assertEquals(2,ledger.balance("o0"));
        gate.start(t);gate.finish(t,true);assertTrue(gate.acquire("o0","c0",key,"继续聊",null).replay());assertEquals(2,ledger.balance("o0"));
    }
    @Test void rejectedBeforeExecutionRefundsOnceAndNeverReusesRefundedDebit(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);String key=key();var t=gate.acquire("o0","c0",key,"继续",null,true);
        gate.abandon(t);gate.abandon(t);assertEquals(3,ledger.balance("o0"));
        assertEquals("AGENT_REQUEST_ABANDONED",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key,"继续",null,true)).code());
        assertFalse(gate.acquire("o0","c0",key(),"新请求",null,true).replay());assertEquals(2,ledger.balance("o0"));
    }
    @Test void insufficientBalanceRollsBackAdmissionAndExactEditRemainsFree(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);ledger.debit("o0",3,"empty");
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Long.class);
        assertEquals("INSUFFICIENT_CREDITS",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key(),"继续",null,true)).code());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Long.class));
        var t=gate.acquire("o0","c0",key(),"把最后一句改成：再吃一遍，西山大桥的糯米饭",null);assertEquals(0,ledger.balance("o0"));
        assertEquals(0,jdbc.queryForObject("SELECT reserved_tokens FROM agent_request_admission WHERE id=?",Long.class,t.id()));
    }
    @Test void consentNeverBypassesGlobalCapacity(){
        var ledger=billing();var gate=gate(limits(8,60,7248)).withCredits(ledger);useFree(gate);
        assertEquals("AGENT_TOKEN_BUDGET_EXCEEDED",assertThrows(DomainException.class,()->gate.acquire("o0","c0",key(),"继续",null,true)).code());assertEquals(3,ledger.balance("o0"));
    }
    @Test void failedReservationRollsBackTheDebitInTheSameTransaction(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);
        jdbc.execute("ALTER TABLE agent_request_admission ADD CONSTRAINT test_reject_new CHECK(state<>'RESERVED')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->gate.acquire("o0","c0",key(),"继续",null,true));
        assertEquals(3,ledger.balance("o0"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT'",Integer.class));
    }
    @Test void concurrentDuplicatePaidRequestsDebitOnlyOnce() throws Exception {
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);String key=key();
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var accepted=new AtomicInteger();
        try{
            Callable<Void> attempt=()->{start.await();try{gate.acquire("o0","c0",key,"继续",null,true);accepted.incrementAndGet();}catch(DomainException error){assertEquals("AGENT_TURN_IN_PROGRESS",error.code());}return null;};
            var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            assertEquals(1,accepted.get());assertEquals(2,ledger.balance("o0"));
        }finally{pool.shutdownNow();}
    }

    @Test void oneCreditCoversFiveTurnsAndSixthBuysNextPack(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);
        for(int i=1;i<=6;i++){
            var t=gate.acquire("o0","c0",key(),"继续",null,true);gate.start(t);gate.finish(t,true);
            assertEquals(i<=5?2:1,ledger.balance("o0"));
        }
        assertEquals(4,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
    }
    @Test void remainingTurnsSurviveNewInstanceAndDayAndDoNotRequireCreditBalance(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);
        var t=gate.acquire("o0","c0",key(),"继续",null,true);gate.start(t);gate.finish(t,true);
        ledger.debit("o0",2,"spend-rest");
        jdbc.update("UPDATE agent_request_admission SET usage_day=?",java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).minusDays(1)));
        var restarted=paidGate(ledger);useFree(restarted);
        var next=restarted.acquire("o0","c0",key(),"继续",null,true);
        assertEquals(0,ledger.balance("o0"));assertEquals(3,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
        restarted.abandon(next);restarted.abandon(next);
        assertEquals(4,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
    }

    @Test void allowanceIsSharedAcrossConversationsAndExactEditDoesNotConsumeIt(){
        var ledger=billing();var gate=paidGate(ledger);useFree(gate);
        var first=gate.acquire("o0","c0",key(),"继续",null,true);gate.start(first);gate.finish(first,true);
        jdbc.update("INSERT INTO agent_conversation(id,creator_id) VALUES('other','o0')");
        var next=gate.acquire("o0","other",key(),"继续聊",null,true);gate.start(next);gate.finish(next,true);
        var edit=gate.acquire("o0","other",key(),"把最后一句改成：再吃一遍，西山大桥的糯米饭",null,true);
        assertEquals(2,ledger.balance("o0"));
        assertEquals(3,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
        gate.abandon(edit);
        assertEquals(3,jdbc.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id='o0'",Integer.class));
    }

}
