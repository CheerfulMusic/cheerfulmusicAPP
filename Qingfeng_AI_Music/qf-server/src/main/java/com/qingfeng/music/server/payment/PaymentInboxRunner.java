package com.qingfeng.music.server.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated worker switch: API-only and local-main processes must leave it disabled. */
@Component @Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.payment.inbox.scheduler-enabled",havingValue="true")
public class PaymentInboxRunner {
    private final PayPalPaymentService paypal;
    private final java.util.List<VerifiedPaymentProcessor> processors;
    private int nextProcessor;
    private final java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"payment-inbox-worker");thread.setDaemon(true);return thread;});
    private final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reconciling=new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors){
        this.paypal=paypal;this.processors=java.util.List.copyOf(processors);
        var providers=new java.util.HashSet<String>();
        for(var processor:this.processors)if(!providers.add(processor.provider()))throw new IllegalArgumentException("Duplicate verified payment processor");
    }
    public PaymentInboxRunner(PayPalPaymentService paypal){this(paypal,java.util.List.of(paypal));}
    @Scheduled(fixedDelayString="${qingfeng.payment.inbox.scan-ms:1000}")
    public void runOnce(){submit(polling,this::pollBatch);}
    void pollBatch(){
        // Rotate across providers; a busy channel cannot consume every polling turn.
        int idle=0;
        for(int attempts=0;attempts<10&&!processors.isEmpty()&&idle<processors.size();attempts++){
            var processor=processors.get(nextProcessor++%processors.size());
            if(nextProcessor==Integer.MAX_VALUE)nextProcessor=0;
            if(processor.processNextEvent())idle=0;else idle++;
        }
    }
    @Scheduled(fixedDelayString="${qingfeng.payment.reconcile.scan-ms:60000}")
    public void reconcile(){submit(reconciling,paypal::reconcileOrders);}
    private void submit(java.util.concurrent.atomic.AtomicBoolean running,Runnable task){
        // At most one task of each kind is queued/running. Provider HTTP never blocks the shared Spring scheduler.
        if(!running.compareAndSet(false,true))return;
        try{workers.execute(()->{try{task.run();}finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException stopping){running.set(false);}
    }
    @jakarta.annotation.PreDestroy void stop(){workers.shutdownNow();}
}
