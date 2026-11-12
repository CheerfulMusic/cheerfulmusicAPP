package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentStateEventStreamTest {
    static AgentChatService.Snapshot snapshot(long seq,long epoch){return new AgentChatService.Snapshot(List.of(),null,null,null,null,false,null,"append",false,0,seq,epoch,false);}
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
