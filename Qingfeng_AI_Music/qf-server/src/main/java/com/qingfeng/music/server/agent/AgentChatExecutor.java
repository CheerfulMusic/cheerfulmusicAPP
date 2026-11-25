package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.context.request.async.DeferredResult;

/** Zero waiting queue: overload is rejected before admission; accepted calls own a durable identity. */
public final class AgentChatExecutor implements AutoCloseable {
    private final AgentAdmission admission;private final AgentChatService chat;private final Duration deadline;private final int promptBytes;
    private final ThreadPoolExecutor workers;private final ScheduledExecutorService heartbeat;private final Semaphore slots;
    public AgentChatExecutor(AgentAdmission admission,AgentChatService chat,int threads,Duration deadline,int promptBytes){
        if(threads<1||threads>64||deadline.compareTo(Duration.ofSeconds(10))<0||deadline.compareTo(Duration.ofMinutes(5))>0)throw new IllegalArgumentException("Invalid Agent executor limits");
        this.admission=admission;this.chat=chat;this.deadline=deadline;this.promptBytes=promptBytes;this.slots=new Semaphore(threads);
        workers=new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"agent-chat-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"agent-chat-admission");t.setDaemon(true);return t;});
    }
    public DeferredResult<AgentChatService.Snapshot> submit(String owner,String conversation,String text,String key,String locale){
        return submit(owner,conversation,text,key,locale,false);
    }
    public DeferredResult<AgentChatService.Snapshot> submit(String owner,String conversation,String text,String key,String locale,boolean paidConfirmed){
        return submit(owner,conversation,text,key,locale,paidConfirmed,null);
    }
    public DeferredResult<AgentChatService.Snapshot> submit(String owner,String conversation,String text,String transportKey,String locale,boolean paidConfirmed,AgentChatService.DisplayedContext displayedContext){
        String key=AgentCreationRequest.turnKey(text,transportKey);
        if(!slots.tryAcquire())throw new DomainException("AGENT_BUSY","当前对话请求较多，请稍后再试；本次尚未开始执行。");
        AgentAdmission.Ticket ticket;
        try{ticket=paidConfirmed?admission.acquire(owner,conversation,key,text,locale,true):admission.acquire(owner,conversation,key,text,locale);}catch(RuntimeException e){slots.release();throw e;}
        var scope=new AgentRequestScope(deadline,promptBytes);
        var result=new DeferredResult<AgentChatService.Snapshot>(deadline.toMillis()+1000);
        result.onTimeout(()->{scope.cancel();result.setErrorResult(AgentRequestScope.expired());});
        result.onError(error->scope.cancel());
        try{workers.execute(()->execute(ticket,scope,result,owner,conversation,text,key,locale,displayedContext));}
        catch(RejectedExecutionException e){try{admission.abandon(ticket);}finally{slots.release();}throw new DomainException("AGENT_BUSY","对话执行器暂不可用，本次尚未开始执行。");}
        return result;
    }
    private void execute(AgentAdmission.Ticket ticket,AgentRequestScope scope,DeferredResult<AgentChatService.Snapshot> result,String owner,String conversation,String text,String key,String locale,AgentChatService.DisplayedContext displayedContext){
        boolean completed=false;ScheduledFuture<?> renewal=null;
        var started=new AtomicBoolean();
        try(var ignored=scope.enter()){
            renewal=heartbeat.scheduleWithFixedDelay(()->{try{if(started.get())admission.heartbeat(ticket);}catch(RuntimeException failure){scope.cancel();}},10,10,TimeUnit.SECONDS);
            AgentRequestScope.check();
            var snapshot=chat.send(owner,conversation,text,key,locale,displayedContext,claim -> {
                var resultClaim = admission.startTurn(ticket,conversation,claim);
                started.set(true);
                return resultClaim;
            });
            completed=true;
            admission.finish(ticket,true);result.setResult(snapshot);
        }catch(Throwable failure){
            if(!completed)try{
                if(started.get())admission.finish(ticket,false);
                else admission.abandon(ticket);
            }catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            result.setErrorResult(failure);
        }finally{
            if(renewal!=null)renewal.cancel(false);
            slots.release();
        }
    }
    @Override public void close(){workers.shutdown();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))workers.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();workers.shutdownNow();}heartbeat.shutdownNow();}
}
