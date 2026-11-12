package com.qingfeng.music.server.events;

import com.qingfeng.music.events.DurableEvents;
import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DurableTaskDispatcherAsyncTest {
    @Test void scheduledTriggerReturnsWhileTransportBlocksAndCoalescesFurtherTriggers()throws Exception{
        var outbox=mock(JdbcDurableEvents.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var delivered=new CountDownLatch(1);var calls=new AtomicInteger();
        var claimed=new JdbcDurableEvents.Claimed(new DurableEvents.Event("event","music.ready","key","task"),"token",1);
        when(outbox.claim(any())).thenReturn(Optional.of(claimed),Optional.empty());doAnswer(call->{delivered.countDown();return null;}).when(outbox).delivered(claimed);
        try(var dispatcher=new DurableTaskDispatcher(outbox,event->{calls.incrementAndGet();entered.countDown();release.await(3,TimeUnit.SECONDS);})){
            try{dispatcher.schedule();assertTrue(entered.await(1,TimeUnit.SECONDS));for(int i=0;i<100;i++)dispatcher.schedule();assertEquals(1,calls.get());verify(outbox,times(1)).claim(any());release.countDown();assertTrue(delivered.await(1,TimeUnit.SECONDS));}
            finally{release.countDown();}
        }
    }
    @Test void closePreventsNewScheduledClaims(){
        var outbox=mock(JdbcDurableEvents.class);var dispatcher=new DurableTaskDispatcher(outbox,event->{});dispatcher.close();dispatcher.schedule();verifyNoInteractions(outbox);
    }
}
