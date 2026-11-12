package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Read-only observer. The durable POST turn remains the only execution path. */
@Component
@Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
public final class AgentStateEventStream implements AutoCloseable {
    @FunctionalInterface interface Reader { AgentChatService.Snapshot read(String owner,String conversation,Long afterSeq,Long epoch); }
    private final Reader reader;
    private final int maximum,perOwner;
    private final long intervalMillis;
    private final Supplier<SseEmitter> emitters;
    private final ScheduledExecutorService scheduler;
    private final Set<Subscription> subscriptions=new HashSet<>();
    private final Map<String,Integer> ownerCounts=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper();
    private boolean stopped;

    @Autowired public AgentStateEventStream(AgentChatService chat,Environment env){
        this(chat::state,env.getProperty("qingfeng.agent.events.maximum",Integer.class,32),
            env.getProperty("qingfeng.agent.events.per-owner",Integer.class,2),1000,
            ()->new SseEmitter(30_000L),true);
    }
    AgentStateEventStream(Reader reader,int maximum,int perOwner,long intervalMillis,Supplier<SseEmitter> emitters,boolean schedule){
        if(maximum<1||maximum>128||perOwner<1||perOwner>4||intervalMillis<250)throw new IllegalArgumentException("Invalid observer limits");
        this.reader=reader;this.maximum=maximum;this.perOwner=perOwner;this.intervalMillis=intervalMillis;this.emitters=emitters;
        if(schedule){
            // Each admitted observer can own a worker. A slow socket must not occupy one of
            // only two shared ticker threads and prevent every healthy observer from updating.
            var pool=new ScheduledThreadPoolExecutor(maximum,r->{var t=new Thread(r,"agent-state-observer");t.setDaemon(true);return t;});
            pool.setRemoveOnCancelPolicy(true);
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            scheduler=pool;
        }else scheduler=null;
    }
    public SseEmitter open(String owner,String conversation,Long afterSeq,Long epoch){
        var subscription=new Subscription(owner,conversation,afterSeq,epoch,emitters.get());
        synchronized(this){
            if(stopped||subscriptions.size()>=maximum||ownerCounts.getOrDefault(owner,0)>=perOwner)
                throw new DomainException("AGENT_BUSY","实时进度连接较多，正在使用普通状态刷新。");
            subscriptions.add(subscription);ownerCounts.merge(owner,1,Integer::sum);
        }
        subscription.emitter.onCompletion(subscription::release);
        subscription.emitter.onTimeout(subscription::complete);
        subscription.emitter.onError(error->subscription.release());
        try{
            // Ownership is checked before returning an SSE response, never by trusting the path ID.
            subscription.send(reader.read(owner,conversation,afterSeq,epoch));
            if(scheduler!=null){
                subscription.future=scheduler.scheduleWithFixedDelay(subscription::poll,intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);
                if(subscription.closed.get())subscription.future.cancel(false);
            }
            return subscription.emitter;
        }catch(RuntimeException|IOException error){subscription.complete();if(error instanceof DomainException domain)throw domain;
            throw new DomainException("AGENT_STREAM_UNAVAILABLE","暂时无法连接实时进度，请使用状态刷新。");}
    }
    /** Also enables deterministic offline tests without opening sockets or sleeping. */
    void pollOnce(){List<Subscription> copy;synchronized(this){copy=List.copyOf(subscriptions);}copy.forEach(Subscription::poll);}
    synchronized int active(){return subscriptions.size();}
    private final class Subscription {
        final String owner,conversation;final SseEmitter emitter;final AtomicBoolean closed=new AtomicBoolean();
        Long cursor,epoch;String previous;volatile ScheduledFuture<?> future;
        Subscription(String owner,String conversation,Long cursor,Long epoch,SseEmitter emitter){this.owner=owner;this.conversation=conversation;this.cursor=cursor;this.epoch=epoch;this.emitter=emitter;}
        void send(AgentChatService.Snapshot snapshot)throws IOException{
            if(closed.get())return;
            String encoded=json.writeValueAsString(snapshot);
            if(!encoded.equals(previous)){
                emitter.send(SseEmitter.event().name("state").id(snapshot.historyEpoch()+":"+snapshot.latestSeq()).data(snapshot));
                previous=encoded;
            }else emitter.send(SseEmitter.event().name("ping").data(Map.of("alive",true)));
            cursor=snapshot.latestSeq();epoch=snapshot.historyEpoch();
        }
        void poll(){
            if(closed.get())return;
            try{send(reader.read(owner,conversation,cursor,epoch));}
            catch(Exception failure){
                try{emitter.send(SseEmitter.event().name("error").data(Map.of("code","AGENT_STREAM_UNAVAILABLE","message","实时进度暂时断开，请通过状态刷新查看结果。")));}catch(Exception ignored){}
                complete();
            }
        }
        void release(){
            if(!closed.compareAndSet(false,true))return;
            if(future!=null)future.cancel(false);
            synchronized(AgentStateEventStream.this){subscriptions.remove(this);ownerCounts.computeIfPresent(owner,(key,count)->count<=1?null:count-1);}
        }
        void complete(){release();try{emitter.complete();}catch(Exception ignored){}}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){
        List<Subscription> copy;synchronized(this){stopped=true;copy=List.copyOf(subscriptions);}
        copy.forEach(Subscription::complete);if(scheduler!=null)scheduler.shutdownNow();
    }
}
