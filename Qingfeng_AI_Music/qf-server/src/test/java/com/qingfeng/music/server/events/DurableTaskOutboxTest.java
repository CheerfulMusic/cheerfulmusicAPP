package com.qingfeng.music.server.events;

import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class DurableTaskOutboxTest {
    JdbcTemplate db; TransactionTemplate tx; JdbcDurableEvents outbox;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V37__durable_task_outbox.sql")).execute(ds);
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));outbox=new JdbcDurableEvents(db,tx);
    }
    @Test void eventRollsBackWithBusinessTransactionAndDeduplicatesConcurrentProducers()throws Exception{
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{outbox.publish("music.ready","rollback","work");throw new IllegalStateException();}));
        assertEquals(0,outbox.count("READY"));
        var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(8);
        try{var tasks=new ArrayList<Future<?>>();for(int i=0;i<8;i++)tasks.add(executor.submit(()->{try{start.await();outbox.publish("music.ready","shared","work");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
            start.countDown();for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
        assertEquals(1,outbox.count("READY"));
        assertThrows(DomainException.class,()->outbox.publish("music.ready","shared","different-work"));
    }
    @Test void crashAndExpiredLeaseCannotLetOldPublisherOverwriteNewOwner(){
        outbox.publish("music.ready","work","work");var first=outbox.claim(Duration.ofSeconds(60)).orElseThrow();
        db.update("UPDATE task_event_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        var second=outbox.claim(Duration.ofSeconds(60)).orElseThrow();
        outbox.delivered(first);assertEquals(1,outbox.count("SENDING"));
        outbox.delivered(second);outbox.failed(first);assertEquals(1,outbox.count("SENT"));
    }
    @Test void unavailableBrokerKeepsDurableWorkAndRecoveryDeliversBatch(){
        for(int i=0;i<3;i++)outbox.publish("music.ready","work"+i,"work"+i);
        new DurableTaskDispatcher(outbox,event->{throw new IllegalStateException("broker down");}).poll();
        assertEquals(3,outbox.count("READY"));assertEquals(0,outbox.count("SENT"));
        db.update("UPDATE task_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP");
        var count=new AtomicInteger();new DurableTaskDispatcher(outbox,event->count.incrementAndGet()).poll();
        assertEquals(3,count.get());assertEquals(3,outbox.count("SENT"));
    }
    @Test void repeatedDeliveryFailureStopsWithVisibleDeadState(){
        outbox.publish("music.ready","work","work");db.update("UPDATE task_event_outbox SET attempts=19");
        new DurableTaskDispatcher(outbox,event->{throw new IllegalStateException();}).poll();
        assertEquals(1,outbox.count("DEAD"));assertTrue(outbox.claim(Duration.ofSeconds(60)).isEmpty());
    }
}
