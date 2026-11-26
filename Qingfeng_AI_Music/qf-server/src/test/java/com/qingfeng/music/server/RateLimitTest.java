package com.qingfeng.music.server;
import com.qingfeng.music.server.security.ApiRateLimitFilter;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitTest {
    @Test void attemptsAreBoundedAndSeparateActorsDoNotBlockEachOther(){var limiter=new ApiRateLimitFilter(Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"),ZoneOffset.UTC));for(int i=0;i<5;i++)assertTrue(limiter.allow("write:one",5));assertFalse(limiter.allow("write:one",5));assertTrue(limiter.allow("write:two",5));}
    @Test void fullLocalWindowKeepsExistingLimitsAndReleasesCapacityOnRollover() {
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.millis()).thenReturn(60_000L);
        var limiter = new ApiRateLimitFilter(clock);
        for (int i=0; i<32_768; i++) assertTrue(limiter.allow("actor-"+i,2));
        assertFalse(limiter.allow("new",2));
        assertTrue(limiter.allow("actor-0",2));
        assertFalse(limiter.allow("actor-0",2));
        org.mockito.Mockito.when(clock.millis()).thenReturn(120_000L);
        assertTrue(limiter.allow("new",2));
        assertTrue(limiter.allow("actor-0",2));
    }
}
