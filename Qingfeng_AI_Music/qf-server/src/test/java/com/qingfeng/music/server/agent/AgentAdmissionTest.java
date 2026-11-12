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
        jdbc.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");jdbc.execute("CREATE TABLE agent_conversation(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE agent_message(id VARCHAR(36) PRIMARY KEY,conversation_id VARCHAR(36),role VARCHAR(16),state VARCHAR(16))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V35__agent_admission.sql")).execute(source);
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
}
