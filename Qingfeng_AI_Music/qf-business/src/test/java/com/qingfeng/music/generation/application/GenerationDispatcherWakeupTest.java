package com.qingfeng.music.generation.application;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationDispatcherWakeupTest {
    @Test void burstWakeupsCoalesceAndCannotExceedExistingSubmitPool()throws Exception{
        var first=new CountDownLatch(1);var release=new CountDownLatch(1);var second=new CountDownLatch(1);var calls=new AtomicInteger();var active=new AtomicInteger();var peak=new AtomicInteger();
        try(var dispatcher=new GenerationDispatcher(phase->{
            if(phase!=ExecutionRepository.Phase.SUBMIT)return;
            int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
            try{if(calls.incrementAndGet()==1){first.countDown();release.await(3,TimeUnit.SECONDS);}else second.countDown();}
            catch(InterruptedException e){Thread.currentThread().interrupt();}finally{active.decrementAndGet();}
        },Duration.ofMinutes(1),1,1,1)){
            try{dispatcher.start();assertTrue(first.await(2,TimeUnit.SECONDS));assertTrue(dispatcher.wakeup(ExecutionRepository.Phase.SUBMIT));
                for(int n=0;n<100;n++)assertFalse(dispatcher.wakeup(ExecutionRepository.Phase.SUBMIT));
                assertEquals(1,calls.get());release.countDown();assertTrue(second.await(2,TimeUnit.SECONDS));assertEquals(2,calls.get());assertEquals(1,peak.get());
            }finally{release.countDown();}
        }
    }
    @Test void wakeupBeforeStartAfterCloseAndForAnyDoesNotCreateAnotherExecutionChannel(){
        var calls=new AtomicInteger();var dispatcher=new GenerationDispatcher(p->calls.incrementAndGet(),Duration.ofMinutes(1),1,1,1);
        assertFalse(dispatcher.wakeup(ExecutionRepository.Phase.SUBMIT));assertFalse(dispatcher.wakeup(ExecutionRepository.Phase.ANY));dispatcher.close();assertFalse(dispatcher.wakeup(ExecutionRepository.Phase.SUBMIT));assertEquals(0,calls.get());
    }
}
