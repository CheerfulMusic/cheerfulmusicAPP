package com.qingfeng.music.server.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentInboxRunnerTest {
    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> warnings;
    @BeforeEach void captureOperationalWarnings(){
        logger=(Logger)LoggerFactory.getLogger(PaymentInboxRunner.class);previousLevel=logger.getLevel();
        logger.setLevel(Level.WARN);warnings=new ListAppender<>();warnings.start();logger.addAppender(warnings);
    }
    @AfterEach void releaseLogCapture(){logger.detachAppender(warnings);warnings.stop();logger.setLevel(previousLevel);}
    @Test void multiplePollingLanesProgressWhileOneDeliveryBlocksWithoutUnboundedQueueing() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var provider=mock(VerifiedPaymentProcessor.class);when(provider.provider()).thenReturn("STRIPE_SANDBOX");
        when(provider.processNextEvent()).thenAnswer(invocation->{calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);return false;});
        var workers=(java.util.concurrent.ThreadPoolExecutor)Executors.newFixedThreadPool(3);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider),workers,System::nanoTime,2);
        try{
            runner.runOnce();assertTrue(entered.await(5,TimeUnit.SECONDS),"Two deliveries should run independently");
            for(int n=0;n<100;n++)runner.runOnce();
            assertEquals(2,calls.get());assertTrue(workers.getQueue().isEmpty(),"Scans must not queue duplicate lane tasks");
        }finally{release.countDown();runner.stop();}
    }
    @Test void concurrencyConfigurationRejectsUnboundedWorkerCounts(){
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(),0));
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(),33));
    }
    @Test void capacityBatchHonorsItsBoundAndSkipsIdleChannels(){
        var calls=new ArrayList<String>();
        var idle=processor("IDLE",calls,false);
        var busy=processor("BUSY",calls,true);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(idle,busy),1,128);
        try{
            runner.pollBatch();
            assertEquals(128,calls.size());
            assertEquals(1,calls.stream().filter("IDLE"::equals).count());
        }finally{runner.stop();}
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(null,List.of(),1,0));
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(null,List.of(),1,257));
    }
    @Test void pollingIsBoundedAndRotatesAcrossIndependentProviders(){
        var calls=new ArrayList<String>();
        var first=processor("FIRST",calls,true);var second=processor("SECOND",calls,true);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{
            runner.pollBatch();assertEquals(10,calls.size());
            for(int n=0;n<10;n++)assertEquals(n%2==0?"FIRST":"SECOND",calls.get(n));
        }finally{runner.stop();}
    }
    @Test void anEmptyPassStopsWithoutSpinningAndDuplicateChannelsAreRejected(){
        var calls=new ArrayList<String>();var first=processor("FIRST",calls,false);var second=processor("SECOND",calls,false);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{runner.pollBatch();assertEquals(List.of("FIRST","SECOND"),calls);assertTrue(warnings.list.isEmpty());}finally{runner.stop();}
        assertThrows(IllegalArgumentException.class,()->new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,first)));
    }
    @Test void failedReconciliationIsObservableAndDoesNotSuppressAnotherProvider(){
        var first=mock(VerifiedPaymentProcessor.class);when(first.provider()).thenReturn("FIRST");
        var second=mock(VerifiedPaymentProcessor.class);when(second.provider()).thenReturn("SECOND");
        doThrow(new IllegalStateException("sk_live_private whsec_private owner@example.test")).when(first).reconcileOrders();
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{
            assertDoesNotThrow(runner::reconcileBatch);verify(second).reconcileOrders();
            assertWarning("reconcile","FIRST","IllegalStateException");
        }finally{runner.stop();}
    }
    @Test void aFailingPollProviderIsTriedOnlyOncePerBatchWhileTheOtherChannelContinues(){
        var first=mock(VerifiedPaymentProcessor.class);when(first.provider()).thenReturn("FIRST");
        when(first.processNextEvent()).thenThrow(new IllegalStateException("sk_live_private"));
        var second=mock(VerifiedPaymentProcessor.class);when(second.provider()).thenReturn("SECOND");when(second.processNextEvent()).thenReturn(true);
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(first,second));
        try{
            assertDoesNotThrow(runner::pollBatch);verify(first,times(1)).processNextEvent();
            verify(second,atLeastOnce()).processNextEvent();verify(second,atMost(10)).processNextEvent();
            assertWarning("poll","FIRST","IllegalStateException");
        }finally{runner.stop();}
    }
    @Test void anUnexpectedAsynchronousBatchFailureReleasesThePollingGuardForTheNextScan() throws Exception {
        var fail=new AtomicBoolean();var provider=mock(VerifiedPaymentProcessor.class);
        when(provider.provider()).thenAnswer(call->{if(fail.getAndSet(false))throw new IllegalStateException("whsec_private");return "FIRST";});
        ExecutorService worker=Executors.newSingleThreadExecutor();
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider),worker);
        try{
            fail.set(true);runner.runOnce();worker.submit(()->{}).get(5,TimeUnit.SECONDS);
            verify(provider,never()).processNextEvent();assertWarning("poll","ALL","IllegalStateException");
            runner.runOnce();worker.submit(()->{}).get(5,TimeUnit.SECONDS);
            verify(provider,times(1)).processNextEvent();assertEquals(1,warnings.list.size());
        }finally{runner.stop();}
    }
    @Test void scheduledCallsRejectedAfterNormalShutdownDoNotRaiseAnOperationalAlarm(){
        var provider=mock(VerifiedPaymentProcessor.class);when(provider.provider()).thenReturn("FIRST");
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider));
        runner.stop();assertDoesNotThrow(runner::runOnce);assertDoesNotThrow(runner::reconcile);
        verify(provider,never()).processNextEvent();verify(provider,never()).reconcileOrders();assertTrue(warnings.list.isEmpty());
    }
    @Test void unexpectedExecutorRejectionIsSanitizedAndDoesNotPermanentlySuppressTheNextSubmission(){
        var provider=mock(VerifiedPaymentProcessor.class);when(provider.provider()).thenReturn("FIRST");
        var executor=mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("sk_live_private owner@example.test")).when(executor).execute(any(Runnable.class));
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider),executor);
        try{
            assertDoesNotThrow(runner::runOnce);assertWarning("poll","ALL","RejectedExecutionException");
            doAnswer(call->{call.getArgument(0,Runnable.class).run();return null;}).when(executor).execute(any(Runnable.class));
            runner.runOnce();verify(provider).processNextEvent();assertEquals(1,warnings.list.size());
        }finally{runner.stop();}
    }
    @Test void repeatedFailuresAreRateLimitedPerChannelAndStageWithoutDelayingRecoveryWork(){
        var clock=new AtomicLong();var provider=mock(VerifiedPaymentProcessor.class);when(provider.provider()).thenReturn("FIRST");
        when(provider.processNextEvent()).thenThrow(new IllegalStateException("sk_live_private"));
        doThrow(new IllegalStateException("whsec_private")).when(provider).reconcileOrders();
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider),mock(ExecutorService.class),clock::get);
        try{
            runner.pollBatch();runner.reconcileBatch();assertEquals(2,warnings.list.size());
            clock.set(TimeUnit.SECONDS.toNanos(59));runner.pollBatch();runner.reconcileBatch();assertEquals(2,warnings.list.size());
            clock.set(TimeUnit.MINUTES.toNanos(1));runner.pollBatch();runner.reconcileBatch();assertEquals(4,warnings.list.size());
            verify(provider,times(3)).processNextEvent();verify(provider,times(3)).reconcileOrders();assertNoSensitiveFailureText();
        }finally{runner.stop();}
    }
    @Test void invalidProviderLabelsCannotInjectSecretsOrExtraLinesIntoWarnings(){
        var provider=mock(VerifiedPaymentProcessor.class);when(provider.provider()).thenReturn("FIRST\nsk_live_private");
        doThrow(new IllegalStateException("owner@example.test")).when(provider).reconcileOrders();
        var runner=new PaymentInboxRunner(mock(PayPalPaymentService.class),List.of(provider));
        try{runner.reconcileBatch();assertWarning("reconcile","UNKNOWN","IllegalStateException");}finally{runner.stop();}
    }
    private void assertWarning(String stage,String provider,String errorClass){
        assertEquals(1,warnings.list.size());var event=warnings.list.get(0);assertEquals(Level.WARN,event.getLevel());
        assertEquals("payment_worker_failure stage="+stage+" provider="+provider+" errorClass="+errorClass,event.getFormattedMessage());
        assertNoSensitiveFailureText();
    }
    private void assertNoSensitiveFailureText(){
        for(var event:warnings.list){
            assertNull(event.getThrowableProxy(),"Provider exception stacks must not leak payloads or secrets");
            String message=event.getFormattedMessage();assertFalse(message.contains("sk_live_private"));assertFalse(message.contains("whsec_private"));assertFalse(message.contains("owner@example.test"));assertFalse(message.contains("\n"));
        }
    }
    private VerifiedPaymentProcessor processor(String provider,List<String> calls,boolean pending){
        return new VerifiedPaymentProcessor(){public String provider(){return provider;}public boolean processNextEvent(){calls.add(provider);return pending;}};
    }
}
