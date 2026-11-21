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
    private final java.util.concurrent.atomic.AtomicInteger nextProcessor=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.ExecutorService workers;
    private final java.util.function.LongSupplier monotonicClock;
    private final java.util.Map<String,Long> lastWarnings=new java.util.HashMap<>();
    private final java.util.List<java.util.concurrent.atomic.AtomicBoolean> polling;
    private final java.util.concurrent.atomic.AtomicBoolean reconciling=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean stopping=new java.util.concurrent.atomic.AtomicBoolean();
    private final int batchSize;
    @org.springframework.beans.factory.annotation.Autowired
    public PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,
            @org.springframework.beans.factory.annotation.Value("${qingfeng.payment.inbox.worker-concurrency:4}") int concurrency,
            @org.springframework.beans.factory.annotation.Value("${qingfeng.payment.inbox.batch-size:10}") int batchSize){
        this(paypal,processors,newWorkers(concurrency),System::nanoTime,concurrency,batchSize);
    }
    public PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,int concurrency){
        this(paypal,processors,concurrency,10);
    }
    private static java.util.concurrent.ExecutorService newWorkers(int concurrency){
        if(concurrency<1||concurrency>32)throw new IllegalArgumentException("Payment concurrency must be 1..32");
        return java.util.concurrent.Executors.newFixedThreadPool(concurrency+1,r->{var thread=new Thread(r,"payment-inbox-worker");thread.setDaemon(true);return thread;});
    }
    public PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors){
        this(paypal,processors,newWorkers(1));
    }
    PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,java.util.concurrent.ExecutorService workers){
        this(paypal,processors,workers,System::nanoTime);
    }
    PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,java.util.concurrent.ExecutorService workers,java.util.function.LongSupplier monotonicClock){
        this(paypal,processors,workers,monotonicClock,1);
    }
    PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,
            java.util.concurrent.ExecutorService workers,java.util.function.LongSupplier monotonicClock,int concurrency){
        this(paypal,processors,workers,monotonicClock,concurrency,10);
    }
    private PaymentInboxRunner(PayPalPaymentService paypal,java.util.List<VerifiedPaymentProcessor> processors,
            java.util.concurrent.ExecutorService workers,java.util.function.LongSupplier monotonicClock,int concurrency,int batchSize){
        if(batchSize<1 || batchSize>256)throw new IllegalArgumentException("Payment batch size must be 1..256");
        this.batchSize=batchSize;
        if(concurrency<1||concurrency>32)throw new IllegalArgumentException("Payment concurrency must be 1..32");
        this.polling=java.util.stream.IntStream.range(0,concurrency).mapToObj(i->new java.util.concurrent.atomic.AtomicBoolean()).toList();
        this.paypal=paypal;this.processors=java.util.List.copyOf(processors);
        this.workers=java.util.Objects.requireNonNull(workers);
        this.monotonicClock=java.util.Objects.requireNonNull(monotonicClock);
        var providers=new java.util.HashSet<String>();
        for(var processor:this.processors)if(!providers.add(processor.provider()))throw new IllegalArgumentException("Duplicate verified payment processor");
    }
    public PaymentInboxRunner(PayPalPaymentService paypal){this(paypal,java.util.List.of(paypal));}
    @Scheduled(fixedDelayString="${qingfeng.payment.inbox.scan-ms:1000}")
    public void runOnce(){for(var lane:polling)submit("poll",lane,this::pollBatch);}
    void pollBatch(){
        if (processors.isEmpty()) return;
        // Each lane visits every channel in order, even when other lanes are polling concurrently.
        int index = nextProcessor.getAndUpdate(i -> (i + 1) % processors.size());
        var idleProviders = new java.util.HashSet<String>();
        for (int attempts = 0; attempts < batchSize && !stopping.get() && idleProviders.size() < processors.size(); ) {
            var processor = processors.get(index);
            index = (index + 1) % processors.size();
            String provider = processor.provider();
            if (idleProviders.contains(provider)) continue;
            attempts++;
            try {
                if (!processor.processNextEvent()) idleProviders.add(provider);
            } catch (RuntimeException unavailable) {
                idleProviders.add(provider);
                failure("poll", provider, unavailable);
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
        // At most one task per polling lane and one reconciliation task is queued/running. Provider HTTP never blocks the shared Spring scheduler.
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
