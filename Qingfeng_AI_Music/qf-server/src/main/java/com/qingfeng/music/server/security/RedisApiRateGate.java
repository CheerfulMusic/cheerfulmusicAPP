package com.qingfeng.music.server.security;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis TIME + atomic increment; unavailable Redis is propagated for fail-closed HTTP 503. */
public final class RedisApiRateGate implements ApiRateLimitFilter.SharedGate {
    private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>("""
        local t=redis.call('TIME');local bucket=KEYS[1]..':'..math.floor(t[1]/60)
        local count=redis.call('INCR',bucket);if count==1 then redis.call('EXPIRE',bucket,120) end
        if count>tonumber(ARGV[1]) then return 0 end return 1
        """,Long.class);
    private final StringRedisTemplate redis;
    public RedisApiRateGate(StringRedisTemplate redis){this.redis=redis;}
    public boolean allow(String key,int limit){return Long.valueOf(1).equals(redis.execute(SCRIPT,List.of("{qingfeng-api-rate}:"+key),Integer.toString(limit)));}
}
