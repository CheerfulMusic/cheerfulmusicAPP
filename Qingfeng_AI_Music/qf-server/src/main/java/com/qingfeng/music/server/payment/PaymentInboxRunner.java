package com.qingfeng.music.server.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dedicated worker switch: API-only and local-main processes must leave it disabled. */
@Component @Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.payment.inbox.scheduler-enabled",havingValue="true")
public class PaymentInboxRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PaymentInboxRunner.class);
    private final PayPalPaymentService paypal;
    private final java.util.List<VerifiedPaymentProcessor> processors;
    private int nextProcessor;
    private final java.util.concurrent.ExecutorService workers;
    private final java.util.function.LongSupplier monotonicClock;
    private final java.util.Map<String,Long> lastWarnings=new java.util.HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean polling=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean reconciling=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean stopping=new java.util.concurrent.atomic.AtomicBoolean();
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors){
        this(paypal,processors,java.util.concurrent.Executors.newFixedThreadPool(2,r->{var thread=new Thread(r,"payment-inbox-worker");thread.setDaemon(true);return thread;}));
    }
    PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,java.util.concurrent.ExecutorService workers){
        this(paypal,processors,workers,System::nanoTime);
    }
    PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,java.util.concurrent.ExecutorService workers,java.util.function.LongSupplier monotonicClock){
        this.paypal=paypal;this.processors=java.util.List.copyOf(processors);
        this.workers=java.util.Objects.requireNonNull(workers);
        this.monotonicClock=java.util.Objects.requireNonNull(monotonicClock);
        var providers=new java.util.HashSet<String>();
        for(var processor:this.processors)if(!providers.add(processor.provider()))throw new IllegalArgumentException("Duplicate verified payment processor");
    }
    public PaymentInboxRunner(PayPalPaymentService paypal){this(paypal,java.util.List.of(paypal));}
    @Scheduled(fixedDelayString="${qingfeng.payment.inbox.scan-ms:1000}")
    public void runOnce(){submit("poll",polling,this::pollBatch);}
    void pollBatch(){
        // Rotate across providers; a busy channel cannot consume every polling turn.
        int idle=0;
        var failedProviders=new java.util.HashSet<String>();
        for(int attempts=0;attempts<10&&!processors.isEmpty()&&idle<processors.size();attempts++){
            var processor=processors.get(nextProcessor++%processors.size());
            if(nextProcessor==Integer.MAX_VALUE)nextProcessor=0;
            String provider=processor.provider();
            if(failedProviders.contains(provider)){idle++;continue;}
            try{if(processor.processNextEvent())idle=0;else idle++;}
            catch(RuntimeException unavailable){
                // Leave durable deliveries recoverable and do not retry a broken channel again in this batch.
                failedProviders.add(provider);idle++;failure("poll",provider,unavailable);
            }
        }
    }
    @Scheduled(fixedDelayString="${qingfeng.payment.reconcile.scan-ms:60000}")
    public void reconcile(){submit("reconcile",reconciling,this::reconcileBatch);}
    void reconcileBatch(){
        // A failed provider must not suppress another provider's recovery pass.
        for(var processor:processors){
            try{processor.reconcileOrders();}
            catch(RuntimeException unavailable){failure("reconcile",processor.provider(),unavailable);}
        }
    }
    private void submit(String stage,java.util.concurrent.atomic.AtomicBoolean running,Runnable task){
        // At most one task of each kind is queued/running. Provider HTTP never blocks the shared Spring scheduler.
        if(!running.compareAndSet(false,true))return;
        try{workers.execute(()->{try{task.run();}catch(RuntimeException failed){failure(stage,"ALL",failed);}finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException rejected){
            running.set(false);
            if(!stopping.get())failure(stage,"ALL",rejected);
        }
    }
    private void failure(String stage,String provider,RuntimeException error){
        String channel=provider!=null&&provider.matches("[A-Z_]{1,24}")?provider:"UNKNOWN";
        String type=error.getClass().getSimpleName();
        if(!type.matches("[A-Za-z0-9_$]{1,80}"))type="RuntimeException";
        synchronized(lastWarnings){
            String key=stage+":"+channel;
            long now=monotonicClock.getAsLong();
            Long previous=lastWarnings.get(key);
            if(previous!=null&&now-previous<java.util.concurrent.TimeUnit.MINUTES.toNanos(1))return;
            lastWarnings.put(key,now);
        }
        // Provider exception messages and stack traces can contain credentials or payment payloads.
        // These bounded operational warnings must never affect application health or restart policy.
        log.warn("payment_worker_failure stage={} provider={} errorClass={}",stage,channel,type);
    }
    @jakarta.annotation.PreDestroy void stop(){stopping.set(true);workers.shutdownNow();}
}
