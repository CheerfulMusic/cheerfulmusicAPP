package com.qingfeng.music.common.concurrent;

import com.qingfeng.music.common.exception.DomainException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative deadline: never pretend cancelling a Future undoes an external side effect. */
public final class AgentRequestScope implements AutoCloseable {
    private static final ThreadLocal<AgentRequestScope> CURRENT=new ThreadLocal<>();
    private final long deadline;
    private final int maxPromptBytes;
    private final AtomicBoolean cancelled=new AtomicBoolean();
    public AgentRequestScope(Duration duration,int maxPromptBytes){this.deadline=System.nanoTime()+duration.toNanos();this.maxPromptBytes=maxPromptBytes;}
    public AgentRequestScope enter(){CURRENT.set(this);return this;}
    public void cancel(){cancelled.set(true);}
    public static void check(){var scope=CURRENT.get();if(scope!=null&&(scope.cancelled.get()||System.nanoTime()>=scope.deadline))throw expired();}
    public static Duration timeout(Duration maximum){check();var scope=CURRENT.get();return scope==null?maximum:Duration.ofNanos(Math.max(1,Math.min(maximum.toNanos(),scope.deadline-System.nanoTime())));}
    public static int maxPromptBytes(){var scope=CURRENT.get();return scope==null?65536:scope.maxPromptBytes;}
    public static DomainException expired(){return new DomainException("AGENT_DEADLINE_EXCEEDED","本轮等待时间已到，后续操作已停止。已发出的请求可能仍在完成，请查看作品和任务状态，并使用原请求标识核对结果，勿重复生成。");}
    @Override public void close(){CURRENT.remove();}
}
