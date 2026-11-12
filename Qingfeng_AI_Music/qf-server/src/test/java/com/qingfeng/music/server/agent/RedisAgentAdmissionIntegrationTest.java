package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.security.RedisApiRateGate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit loopback test instance only; never flushes Redis or touches production keys. */
@EnabledIfEnvironmentVariable(named="QF_REDIS_TEST_PORT",matches="16[0-9]{3}")
class RedisAgentAdmissionIntegrationTest {
    AgentAdmissionTest fixture;LettuceConnectionFactory connection;StringRedisTemplate redis;String namespace;
    @BeforeEach void setup(){
        fixture=new AgentAdmissionTest();fixture.setup();namespace="test"+UUID.randomUUID().toString().replace("-","");
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("QF_REDIS_TEST_PORT")));connection.afterPropertiesSet();connection.start();
        redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();assertEquals("PONG",connection.getConnection().ping());
    }
    @AfterEach void close(){if(redis!=null){var keys=redis.keys("{"+namespace+"}:agent:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}if(connection!=null)connection.destroy();}
    @Test void twoRedisClientsAtomicallyBoundRealConcurrentAdmissions()throws Exception{
        var durableLimits=fixture.limits(20,100,10_000_000);
        var limits=new AgentAdmission.Limits(3,1,100,10,10_000_000,1_000_000,1024,Duration.ofSeconds(60));
        var first=new RedisAgentAdmission(fixture.gate(durableLimits),redis,limits,namespace);
        var second=new RedisAgentAdmission(fixture.gate(durableLimits),new StringRedisTemplate(connection),limits,namespace);
        var pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);var accepted=new AtomicInteger();var tickets=new ConcurrentLinkedQueue<AgentAdmission.Ticket>();
        try{List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<12;i++){int n=i;jobs.add(pool.submit(()->{start.await();try{var ticket=(n%2==0?first:second).acquire("o"+n,"c"+n,fixture.key(),"你好",null);tickets.add(ticket);accepted.incrementAndGet();}catch(DomainException e){assertEquals("AGENT_BUSY",e.code());}return null;}));}start.countDown();for(var job:jobs)job.get(10,TimeUnit.SECONDS);
            assertEquals(3,accepted.get());assertEquals(3L,redis.opsForZSet().zCard("{"+namespace+"}:agent:active"));
            assertEquals(3,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Integer.class));
            for(var ticket:tickets){first.start(ticket);first.heartbeat(ticket);first.finish(ticket,true);}assertEquals(0L,redis.opsForZSet().zCard("{"+namespace+"}:agent:active"));
        }finally{pool.shutdownNow();}
    }
    @Test void redisRpmRemainsAfterSlotsReleaseAndDoesNotSpendRejectedDbBudget(){
        var durableLimits=fixture.limits(20,100,10_000_000);var limits=new AgentAdmission.Limits(2,1,2,2,10_000_000,1_000_000,1024,Duration.ofSeconds(60));
        var gate=new RedisAgentAdmission(fixture.gate(durableLimits),redis,limits,namespace);
        for(int i=0;i<2;i++){var ticket=gate.acquire("o"+i,"c"+i,fixture.key(),"你好",null);gate.start(ticket);gate.finish(ticket,true);}
        assertEquals("AGENT_BUSY",assertThrows(DomainException.class,()->gate.acquire("o2","c2",fixture.key(),"你好",null)).code());assertEquals(2,fixture.jdbc.queryForObject("SELECT COUNT(*) FROM agent_request_admission",Integer.class));
    }
    @Test void httpBucketsAreSharedAcrossInstances(){
        var first=new RedisApiRateGate(redis);var second=new RedisApiRateGate(new StringRedisTemplate(connection));String key="test:"+namespace;
        try{assertTrue(first.allow(key,2));assertTrue(second.allow(key,2));assertFalse(first.allow(key,2));}finally{var keys=redis.keys("{qingfeng-api-rate}:"+key+":*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
    }
}
