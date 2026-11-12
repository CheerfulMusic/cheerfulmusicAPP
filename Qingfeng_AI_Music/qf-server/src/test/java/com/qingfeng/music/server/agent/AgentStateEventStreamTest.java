package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentStateEventStreamTest {
    static AgentChatService.Snapshot snapshot(long seq,long epoch){return new AgentChatService.Snapshot(List.of(),null,null,null,null,false,null,"append",false,0,seq,epoch,false);}
    @Test void timedOutBlockedSendRetainsCapacityUntilItsWorkerActuallyExits()throws Exception{
        var blocked=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);
        var timeout=new AtomicReference<Runnable>();var blockedSend=new CountDownLatch(1);var unblock=new CountDownLatch(1);
        var completion=new CountDownLatch(1);var healthyPoll=new CountDownLatch(1);var sends=new AtomicInteger();
        doAnswer(call->{timeout.set(call.getArgument(0));return null;}).when(blocked).onTimeout(any());
        doAnswer(call->{
            if(sends.incrementAndGet()==2){blockedSend.countDown();if(!unblock.await(5,TimeUnit.SECONDS))throw new AssertionError("Blocked send was not released");}
            return null;
        }).when(blocked).send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(call->{completion.countDown();return null;}).when(blocked).complete();
        var emitters=new ArrayDeque<>(List.of(blocked,healthy,healthy));var reads=new AtomicInteger();
        var callbackThread=Executors.newSingleThreadExecutor();
        try(var stream=new AgentStateEventStream((o,c,s,e)->{if(c.equals("new")&&reads.incrementAndGet()>=2)healthyPoll.countDown();return snapshot(0,0);},1,1,250,emitters::removeFirst,true)){
            stream.open("owner","old",null,null);assertTrue(blockedSend.await(3,TimeUnit.SECONDS));
            // The timeout callback must not wait for Spring's write lock or free the still-busy worker.
            callbackThread.submit(timeout.get()).get(1,TimeUnit.SECONDS);
            assertEquals(1,stream.active());verify(blocked,never()).complete();
            assertEquals("AGENT_BUSY",assertThrows(DomainException.class,()->stream.open("other","new",null,null)).code());
            unblock.countDown();assertTrue(completion.await(3,TimeUnit.SECONDS));
            // retire removes capacity just after complete returns; wait using a bounded condition.
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(stream.active()!=0&&System.nanoTime()<until)Thread.onSpinWait();
            assertEquals(0,stream.active());stream.open("other","new",null,null);
            assertTrue(healthyPoll.await(3,TimeUnit.SECONDS));
            assertEquals(1,stream.active());
        }finally{unblock.countDown();callbackThread.shutdownNow();}
    }
    @Test void aTimedOutBlockedReadDoesNotStopAnotherAdmittedObserver()throws Exception{
        var slow=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);var timeout=new AtomicReference<Runnable>();
        var readEntered=new CountDownLatch(1);var releaseRead=new CountDownLatch(1);var healthyAfterTimeout=new CountDownLatch(1);
        var timedOut=new java.util.concurrent.atomic.AtomicBoolean();var slowReads=new AtomicInteger();
        doAnswer(call->{timeout.set(call.getArgument(0));return null;}).when(slow).onTimeout(any());
        var emitters=new ArrayDeque<>(List.of(slow,healthy));
        try(var stream=new AgentStateEventStream((o,c,s,e)->{
            if(c.equals("slow")&&slowReads.incrementAndGet()==2){
                readEntered.countDown();try{if(!releaseRead.await(5,TimeUnit.SECONDS))throw new AssertionError("Blocked read was not released");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }
            if(c.equals("healthy")&&timedOut.get())healthyAfterTimeout.countDown();return snapshot(0,0);
        },2,1,250,emitters::removeFirst,true)){
            stream.open("one","slow",null,null);stream.open("two","healthy",null,null);
            assertTrue(readEntered.await(3,TimeUnit.SECONDS));timeout.get().run();timedOut.set(true);
            assertEquals(2,stream.active());verify(slow,never()).complete();
            assertTrue(healthyAfterTimeout.await(3,TimeUnit.SECONDS));
            releaseRead.countDown();
        }finally{releaseRead.countDown();}
    }
    @Test void ownershipFailureReturnsNoStreamAndReleasesCapacity(){
        var emitter=mock(SseEmitter.class);
        try(var stream=new AgentStateEventStream((o,c,s,e)->{throw new DomainException("AGENT_CONVERSATION_NOT_FOUND","not owned");},1,1,1000,()->emitter,false)){
            assertEquals("AGENT_CONVERSATION_NOT_FOUND",assertThrows(DomainException.class,()->stream.open("owner","foreign",null,null)).code());
            assertEquals(0,stream.active());verify(emitter).complete();
        }
    }
    @Test void observerAdvancesDurableCursorAndKeepsExactOwner()throws Exception{
        List<List<Object>> reads=new ArrayList<>();var emitter=mock(SseEmitter.class);
        try(var stream=new AgentStateEventStream((o,c,s,e)->{reads.add(Arrays.asList(o,c,s,e));return snapshot(8,2);},2,1,1000,()->emitter,false)){
            stream.open("owner","conversation",4L,1L);stream.pollOnce();
            assertEquals(List.of("owner","conversation",8L,2L),reads.get(1));
            verify(emitter,times(2)).send(any(SseEmitter.SseEventBuilder.class));
            assertEquals(1,stream.active());
        }
    }
    @Test void ownerAndGlobalLimitsRejectBeforeAnyFurtherDatabaseRead(){
        var reads=new AtomicInteger();
        try(var stream=new AgentStateEventStream((o,c,s,e)->{reads.incrementAndGet();return snapshot(0,0);},2,1,1000,()->mock(SseEmitter.class),false)){
            stream.open("one","a",null,null);
            assertEquals("AGENT_BUSY",assertThrows(DomainException.class,()->stream.open("one","a",null,null)).code());
            stream.open("two","b",null,null);
            assertThrows(DomainException.class,()->stream.open("three","c",null,null));assertEquals(2,reads.get());
        }
    }
    @Test void brokenConnectionStopsObservationAndDoesNotRetryAnyOperation()throws Exception{
        var reads=new AtomicInteger();var emitter=mock(SseEmitter.class);
        doNothing().doThrow(new IOException("gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        try(var stream=new AgentStateEventStream((o,c,s,e)->{reads.incrementAndGet();return snapshot(0,0);},1,1,1000,()->emitter,false)){
            stream.open("owner","c",null,null);stream.pollOnce();stream.pollOnce();
            assertEquals(0,stream.active());assertEquals(2,reads.get());verify(emitter).complete();
        }
    }
    @Test void resetEpochReplacesObserverCursorAndShutdownPreventsReconnect(){
        var n=new AtomicInteger();List<Long> cursors=new ArrayList<>();
        var stream=new AgentStateEventStream((o,c,s,e)->{cursors.add(s);return n.getAndIncrement()==0?snapshot(40,2):snapshot(0,3);},1,1,1000,()->mock(SseEmitter.class),false);
        stream.open("owner","c",null,null);stream.pollOnce();stream.pollOnce();
        assertEquals(Arrays.asList(null,40L,0L),cursors);stream.close();assertEquals(0,stream.active());
        assertThrows(DomainException.class,()->stream.open("owner","c",null,null));
    }
}
