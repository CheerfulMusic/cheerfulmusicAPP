package com.qingfeng.music.server;
import com.qingfeng.music.server.security.ApiRateLimitFilter;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitTest {
    @Test void attemptsAreBoundedAndSeparateActorsDoNotBlockEachOther(){var limiter=new ApiRateLimitFilter(Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"),ZoneOffset.UTC));for(int i=0;i<5;i++)assertTrue(limiter.allow("recovery:one",5));assertFalse(limiter.allow("recovery:one",5));assertTrue(limiter.allow("recovery:two",5));}
}
