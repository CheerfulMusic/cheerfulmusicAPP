package com.qingfeng.music.server.events;

import com.qingfeng.music.events.DurableEvents;
import com.qingfeng.music.events.TaskEventHandler;
import com.qingfeng.music.infrastructure.persistence.events.JdbcDurableEvents;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded outbox delivery; a crash after delivery may duplicate a wakeup, never a business operation. */
public final class DurableTaskDispatcher implements AutoCloseable {
    @FunctionalInterface public interface Transport { void send(DurableEvents.Event event) throws Exception; }
    private final JdbcDurableEvents outbox;
    private final Transport transport;
    private final int batchSize;
    private final AtomicBoolean running=new AtomicBoolean();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var thread=new Thread(r,"durable-outbox");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    public DurableTaskDispatcher(JdbcDurableEvents outbox,Transport transport){this(outbox,transport,32);}
    public DurableTaskDispatcher(JdbcDurableEvents outbox,Transport transport,int batchSize){
        if(batchSize<1||batchSize>256)throw new IllegalArgumentException("Outbox batch must be between 1 and 256");
        this.outbox=outbox;this.transport=transport;this.batchSize=batchSize;
    }
    /** Keep broker confirms and local handler HTTP off the shared Spring scheduler. */
    @Scheduled(fixedDelayString="${qingfeng.events.poll-ms:1000}") public void schedule(){
        if(!running.compareAndSet(false,true))return;
        try{worker.execute(()->{try{poll();}catch(RuntimeException error){System.getLogger(DurableTaskDispatcher.class.getName()).log(System.Logger.Level.WARNING,"Outbox scan failed: {0}",error.getClass().getSimpleName());}finally{running.set(false);}});}
        catch(RejectedExecutionException stopping){running.set(false);}
    }
    public void poll(){
      for(int i=0;i<batchSize;i++){
        var claimed=outbox.claim(Duration.ofSeconds(120));
        if(claimed.isEmpty())return;
        try{transport.send(claimed.get().event());outbox.delivered(claimed.get());}
        catch(Exception failure){outbox.failed(claimed.get());return;}
      }
    }
    public static Transport local(List<TaskEventHandler> handlers) {
        Map<String,TaskEventHandler> byTopic=handlers.stream().collect(Collectors.toMap(TaskEventHandler::topic,Function.identity()));
        return event->{var handler=byTopic.get(event.topic());if(handler==null)throw new IllegalStateException("No durable worker for topic");handler.handle(event);};
    }
    @Override public void close(){worker.shutdown();try{if(!worker.awaitTermination(5,TimeUnit.SECONDS))worker.shutdownNow();}catch(InterruptedException error){Thread.currentThread().interrupt();worker.shutdownNow();}}
}
