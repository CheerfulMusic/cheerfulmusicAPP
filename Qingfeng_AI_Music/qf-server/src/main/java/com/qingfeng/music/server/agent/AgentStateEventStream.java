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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final ExecutorService deliveries;
    private final JdbcTemplate observations;
    private int scanOffset;
    private final Set<Subscription> subscriptions=new HashSet<>();
    private final Map<String,Integer> ownerCounts=new HashMap<>();
    private final ObjectMapper json=new ObjectMapper();
    private boolean stopped;

    @Autowired public AgentStateEventStream(AgentChatService chat, Environment env, JdbcTemplate db) {
        this(chat::state, env.getProperty("qingfeng.agent.events.maximum", Integer.class, capacityEnabled(env) ? 1200 : 32),
            env.getProperty("qingfeng.agent.events.per-owner", Integer.class, 2), 1000,
            () -> new SseEmitter(capacityEnabled(env) ? 60_000L : 30_000L), true, capacityEnabled(env) ? db : null);
    }

    private static boolean capacityEnabled(Environment env) {
        return env.getProperty("qingfeng.ai.capacity.enabled", Boolean.class, false);
    }

    AgentStateEventStream(Reader reader,int maximum,int perOwner,long intervalMillis,Supplier<SseEmitter> emitters,boolean schedule){
        this(reader, maximum, perOwner, intervalMillis, emitters, schedule, null);
    }

    AgentStateEventStream(Reader reader, int maximum, int perOwner, long intervalMillis,
                          Supplier<SseEmitter> emitters, boolean schedule, JdbcTemplate observations) {
        if (maximum < 1 || maximum > (observations == null ? 128 : 2000) || perOwner < 1 || perOwner > 4 || intervalMillis < 250)
            throw new IllegalArgumentException("Invalid observer limits");
        this.reader=reader;this.maximum=maximum;this.perOwner=perOwner;this.intervalMillis=intervalMillis;this.emitters=emitters;
        this.observations = observations;
        if (schedule) {
            var pool = new ScheduledThreadPoolExecutor(observations == null ? maximum : 1,
                r -> { var thread = new Thread(r, "agent-state-observer"); thread.setDaemon(true); return thread; });
            pool.setRemoveOnCancelPolicy(true);
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            pool.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            scheduler = pool;
        } else scheduler = null;
        if (schedule && observations != null) {
            deliveries = new ThreadPoolExecutor(24, 24, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(96));
            scheduler.scheduleWithFixedDelay(this::observe, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        } else deliveries = null;
    }

    private record Cursor(long sequence, long epoch) {}

    private void observe() {
        List<Subscription> copy;
        synchronized (this) {
            if (subscriptions.isEmpty() || stopped) return;
            copy = new ArrayList<>(subscriptions);
        }
        Collections.rotate(copy, scanOffset++ * 97 % copy.size());
        Map<String, Cursor> current = new HashMap<>();
        var ids = copy.stream().map(subscription -> subscription.conversation).distinct().toList();
        try {
            for (int i = 0; i < ids.size(); i += 160) {
                var batch = ids.subList(i, Math.min(i + 160, ids.size()));
                observations.query("""
                    SELECT c.id,c.history_epoch,COALESCE((SELECT MAX(m.seq) FROM agent_message m WHERE m.conversation_id=c.id),0)
                    FROM agent_conversation c WHERE c.id IN (
                    """ + String.join(",", Collections.nCopies(batch.size(), "?")) + ")",
                    row -> { current.put(row.getString(1), new Cursor(row.getLong(3), row.getLong(2))); }, batch.toArray());
            }
        } catch (RuntimeException unavailable) {
            return;
        }
        long now = System.nanoTime();
        for (var subscription : copy) {
            var cursor = current.get(subscription.conversation);
            if (cursor == null || !Objects.equals(subscription.epoch, cursor.epoch())
                    || !Objects.equals(subscription.cursor, cursor.sequence()) || now >= subscription.nextRefresh) {
                subscription.deliver();
            }
        }
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
            subscription.initialRead();
            if(scheduler!=null && observations == null){
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
        final String owner,conversation;final SseEmitter emitter;final AtomicBoolean closed=new AtomicBoolean(),retired=new AtomicBoolean();
        private int inFlight;
        private boolean completeRequested;
        volatile Long cursor,epoch;
        volatile long nextRefresh = Long.MAX_VALUE;
        final AtomicBoolean enqueued = new AtomicBoolean();
        String previous;volatile ScheduledFuture<?> future;
        Subscription(String owner,String conversation,Long cursor,Long epoch,SseEmitter emitter){this.owner=owner;this.conversation=conversation;this.cursor=cursor;this.epoch=epoch;this.emitter=emitter;}
        synchronized boolean begin(){if(closed.get() || inFlight > 0)return false;inFlight++;return true;}
        void end(){synchronized(this){inFlight--;}retire();}
        void initialRead()throws IOException{
            if(!begin())throw new DomainException("AGENT_STREAM_UNAVAILABLE","实时进度连接已结束，请使用状态刷新。");
            try{send(reader.read(owner,conversation,cursor,epoch));}finally{end();}
        }
        void send(AgentChatService.Snapshot snapshot)throws IOException{
            if(closed.get())return;
            String encoded=json.writeValueAsString(snapshot);
            if(!encoded.equals(previous)){
                emitter.send(SseEmitter.event().name("state").id(snapshot.historyEpoch()+":"+snapshot.latestSeq()).data(snapshot));
                previous=encoded;
            }else emitter.send(SseEmitter.event().name("ping").data(Map.of("alive",true)));
            cursor=snapshot.latestSeq();epoch=snapshot.historyEpoch();
            nextRefresh = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(12_000 + Math.floorMod(conversation.hashCode(), 6000));
        }
        void deliver() {
            if (!enqueued.compareAndSet(false, true)) return;
            try {
                deliveries.execute(() -> {
                    try { poll(); } finally { enqueued.set(false); }
                });
            } catch (RejectedExecutionException busy) {
                enqueued.set(false);
            }
        }
        void poll(){
            if(!begin())return;
            try{send(reader.read(owner,conversation,cursor,epoch));}
            catch(Exception failure){
                if(!closed.get())try{emitter.send(SseEmitter.event().name("error").data(Map.of("code","AGENT_STREAM_UNAVAILABLE","message","实时进度暂时断开，请通过状态刷新查看结果。")));}catch(Exception ignored){}
                complete();
            }finally{end();}
        }
        void release(){stop(false);}
        void complete(){stop(true);}
        void stop(boolean complete){
            synchronized(this){closed.set(true);completeRequested|=complete;}
            if(future!=null)future.cancel(false);
            retire();
        }
        void retire(){
            boolean complete;
            synchronized(this){
                if(!closed.get()||inFlight!=0||!retired.compareAndSet(false,true))return;
                complete=completeRequested;
            }
            // A timeout closes observation immediately, but capacity belongs to its actual read/write
            // until that work exits. complete() also takes Spring's write lock, so never call it from
            // the timeout callback while an in-flight send owns that lock.
            try{if(complete)emitter.complete();}catch(Exception ignored){}
            finally{synchronized(AgentStateEventStream.this){subscriptions.remove(this);ownerCounts.computeIfPresent(owner,(key,count)->count<=1?null:count-1);}}
        }
    }
    @jakarta.annotation.PreDestroy @Override public void close(){
        List<Subscription> copy;synchronized(this){stopped=true;copy=List.copyOf(subscriptions);}
        copy.forEach(Subscription::complete);if(scheduler!=null)scheduler.shutdownNow();
        if(deliveries!=null)deliveries.shutdownNow();
    }
}
