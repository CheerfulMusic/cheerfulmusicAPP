package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic Redis gates supplement durable DB reservations. Redis failure never bypasses admission. */
public final class RedisAgentAdmission implements AgentAdmission {
    private static final DefaultRedisScript<Long> ACQUIRE=new DefaultRedisScript<>("""
        local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
        for i=1,2 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now) end
        for i=3,4 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now-60000) end
        for i=1,4 do if redis.call('ZCARD',KEYS[i])>=tonumber(ARGV[i+2]) then return 0 end end
        for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[2]),ARGV[1]);redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2) end
        for i=3,4 do redis.call('ZADD',KEYS[i],now,ARGV[1]);redis.call('PEXPIRE',KEYS[i],120000) end
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>("for i=1,2 do redis.call('ZREM',KEYS[i],ARGV[1]) end return 1",Long.class);
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>("""
        local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
        for i=1,2 do local score=redis.call('ZSCORE',KEYS[i],ARGV[1]);if not score or tonumber(score)<=now then return 0 end end
        for i=1,2 do redis.call('ZADD',KEYS[i],now+tonumber(ARGV[2]),ARGV[1]);redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[2])*2) end
        return 1
        """,Long.class);
    private final AgentAdmission durable;private final StringRedisTemplate redis;private final Limits limits;private final String prefix;
    public RedisAgentAdmission(AgentAdmission durable,StringRedisTemplate redis,Limits limits,String namespace){
        if(namespace==null||!namespace.matches("[A-Za-z0-9:_-]{1,48}"))throw new IllegalArgumentException("Invalid Redis namespace");
        this.durable=durable;this.redis=redis;this.limits=limits;this.prefix="{"+namespace+"}:agent:";
    }
    private List<String> keys(Ticket ticket){String user=JdbcAgentAdmission.hash(ticket.owner());return List.of(prefix+"active",prefix+"active:"+user,prefix+"rpm",prefix+"rpm:"+user);}
    @Override public Ticket acquire(String owner,String conversation,String key,String text,String locale){
        Ticket ticket=durable.acquire(owner,conversation,key,text,locale);if(ticket.replay())return ticket;
        try{Long allowed=redis.execute(ACQUIRE,keys(ticket),ticket.id(),Long.toString(limits.lease().toMillis()),Integer.toString(limits.globalConcurrent()),Integer.toString(limits.userConcurrent()),Integer.toString(limits.globalRpm()),Integer.toString(limits.userRpm()));
            if(!Long.valueOf(1).equals(allowed))throw new DomainException("AGENT_BUSY","当前对话请求较多，请稍后再试；本次尚未开始执行。");return ticket;
        }catch(RuntimeException e){durable.abandon(ticket);if(e instanceof DomainException d)throw d;throw unavailable();}
    }
    @Override public void start(Ticket ticket){durable.start(ticket);}
    @Override public void heartbeat(Ticket ticket){if(ticket.replay())return;try{if(!Long.valueOf(1).equals(redis.execute(RENEW,keys(ticket).subList(0,2),ticket.id(),Long.toString(limits.lease().toMillis()))))throw unavailable();durable.heartbeat(ticket);}catch(RuntimeException e){throw unavailable();}}
    @Override public void finish(Ticket ticket,boolean completed){durable.finish(ticket,completed);release(ticket);}
    @Override public void abandon(Ticket ticket){durable.abandon(ticket);release(ticket);}
    private void release(Ticket ticket){if(!ticket.replay())try{redis.execute(RELEASE,keys(ticket).subList(0,2),ticket.id());}catch(RuntimeException ignored){/* TTL retains a conservative slot; DB remains authoritative. */}}
    private static DomainException unavailable(){return new DomainException("AGENT_ADMISSION_UNAVAILABLE","共享对话调度暂不可用，本次没有启动新的创作步骤，请稍后重试。");}
}
