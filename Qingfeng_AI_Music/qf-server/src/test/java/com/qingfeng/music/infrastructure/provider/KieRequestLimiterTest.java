package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.server.MusicApplication;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=MusicApplication.class,properties="spring.datasource.url=jdbc:h2:mem:kie_limiter;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class KieRequestLimiterTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    String scope(){return "t"+UUID.randomUUID().toString().replace("-","");}
    KieRequestLimiter limiter(String scope,Duration wait){return new KieRequestLimiter(jdbc,new TransactionTemplate(manager),scope,1,1,wait);}

    @Test void adaptersAndRestartedInstancesShareTheSameAccountBudgetButReadsHaveTheirOwnBudget(){
        String account=scope();var first=limiter(account,Duration.ofSeconds(1));var restarted=limiter(account,Duration.ofSeconds(1));
        assertTrue(first.tryAcquire(KieRequestLimiter.Kind.CREATE).isZero());
        assertFalse(restarted.tryAcquire(KieRequestLimiter.Kind.CREATE).isZero());
        assertTrue(restarted.tryAcquire(KieRequestLimiter.Kind.READ).isZero());
        assertFalse(first.tryAcquire(KieRequestLimiter.Kind.READ).isZero());
        assertTrue(limiter(scope(),Duration.ofSeconds(1)).tryAcquire(KieRequestLimiter.Kind.CREATE).isZero());
    }

    @Test void competingInstancesCannotConsumeTheSamePermit()throws Exception{
        String account=scope();var first=limiter(account,Duration.ofSeconds(1));var second=limiter(account,Duration.ofSeconds(1));
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(12);
        try{
            var results=new java.util.ArrayList<Future<Duration>>();
            for(int i=0;i<12;i++){var current=i%2==0?first:second;results.add(executor.submit(()->{start.await();return current.tryAcquire(KieRequestLimiter.Kind.CREATE);}));}
            start.countDown();int acquired=0;
            for(var result:results)if(result.get(5,TimeUnit.SECONDS).isZero())acquired++;
            assertEquals(1,acquired);
        }finally{start.countDown();executor.shutdownNow();}
    }

    @Test void deniedChecksDoNotReserveFutureSlotsAndTimeoutDoesNotPretendARequestWasSent(){
        String account=scope();var limiter=limiter(account,Duration.ofMillis(25));limiter.beforeCreate();
        var before=jdbc.queryForObject("SELECT lease_until FROM generation_worker_lock WHERE provider=?",java.sql.Timestamp.class,"kie-rate:"+account+":CREATE");
        assertThrows(KieRequestLimiter.Unavailable.class,limiter::beforeCreate);
        var after=jdbc.queryForObject("SELECT lease_until FROM generation_worker_lock WHERE provider=?",java.sql.Timestamp.class,"kie-rate:"+account+":CREATE");
        assertEquals(before,after);
    }

    @Test void interruptedWaitDoesNotConsumeAPermitAndPreservesInterruptStatus()throws Exception{
        String account=scope();var limiter=limiter(account,Duration.ofSeconds(1));var preserved=new AtomicBoolean();
        var thread=new Thread(()->{Thread.currentThread().interrupt();try{limiter.beforeCreate();}catch(KieRequestLimiter.Unavailable expected){preserved.set(Thread.currentThread().isInterrupted());}});
        thread.start();thread.join(3000);assertFalse(thread.isAlive());assertTrue(preserved.get());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM generation_worker_lock WHERE provider=?",Integer.class,"kie-rate:"+account+":CREATE"));
    }

    @Test void configuredSpacingStaysBelowTheDocumentedWindowCeilings(){
        String account=scope();var limiter=new KieRequestLimiter(jdbc,new TransactionTemplate(manager),account,18,8,Duration.ofSeconds(1));
        assertSlotSpacing(limiter,account,KieRequestLimiter.Kind.CREATE,556);
        assertSlotSpacing(limiter,account,KieRequestLimiter.Kind.READ,126);
    }

    void assertSlotSpacing(KieRequestLimiter limiter,String account,KieRequestLimiter.Kind kind,long millis){
        var before=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",java.sql.Timestamp.class).toInstant();
        assertTrue(limiter.tryAcquire(kind).isZero());
        var next=jdbc.queryForObject("SELECT lease_until FROM generation_worker_lock WHERE provider=?",java.sql.Timestamp.class,"kie-rate:"+account+":"+kind.name()).toInstant();
        var after=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",java.sql.Timestamp.class).toInstant();
        assertFalse(next.isBefore(before.plusMillis(millis)));
        assertFalse(next.isAfter(after.plusMillis(millis)));
    }

    @Test void settingsCannotSilentlyExceedTheAccountLimits(){
        var tx=new TransactionTemplate(manager);
        assertThrows(IllegalArgumentException.class,()->new KieRequestLimiter(jdbc,tx,scope(),21,8,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new KieRequestLimiter(jdbc,tx,scope(),18,11,Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,()->new KieRequestLimiter(jdbc,tx,scope(),18,8,Duration.ZERO));
        assertThrows(IllegalArgumentException.class,()->new KieRequestLimiter(jdbc,tx,"../bad",18,8,Duration.ofSeconds(1)));
    }
}
