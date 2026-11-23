package com.qingfeng.music.server.config;

import com.qingfeng.music.server.lyrics.LyricsPreparation;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LyricsPreparationSchedulerTest {
    @Test void backgroundPreparationRunsWithoutAPlaybackRequestAndStopsWithTheApplication()throws Exception {
        var worker=mock(LyricsPreparation.class);
        var prepared=new CountDownLatch(1);
        when(worker.schemaAvailable()).thenReturn(true);
        doAnswer(call->{prepared.countDown();return null;}).when(worker).processOnce();
        var scheduler=new LyricsPreparationConfiguration.PreparationScheduler(worker,1000,1000,false,true);
        try {
            scheduler.start();scheduler.start();assertTrue(scheduler.isRunning());
            assertTrue(prepared.await(5,TimeUnit.SECONDS));
            var order=inOrder(worker);order.verify(worker).enqueueNew(25,false);order.verify(worker).processOnce();
        }finally{scheduler.stop();}
        assertFalse(scheduler.isRunning());
        clearInvocations(worker);Thread.sleep(1200);verifyNoInteractions(worker);
    }
}
