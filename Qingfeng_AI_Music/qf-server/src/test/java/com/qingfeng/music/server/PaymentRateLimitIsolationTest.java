package com.qingfeng.music.server;

import com.qingfeng.music.server.security.ApiRateLimitFilter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class PaymentRateLimitIsolationTest {
    @Test void webhookHasDedicatedBoundedBucket()throws Exception{
        var filter=new ApiRateLimitFilter();
        for(int i=0;i<60;i++)assertTrue(filter.allow("write:127.0.0.1",60));
        var request=new MockHttpServletRequest("POST","/api/public/paypal/webhook");request.setRemoteAddr("127.0.0.1");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(q,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(204));assertEquals(204,response.getStatus());
        for(int i=1;i<600;i++)assertTrue(filter.allow("payment-webhook:/api/public/paypal/webhook",600));
        var blocked=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST","/api/public/paypal/webhook"),blocked,(q,s)->fail("limit bypass"));assertEquals(429,blocked.getStatus());
    }
    @Test void sharedRateStoreOutageDoesNotCallApplication()throws Exception{
        var filter=new ApiRateLimitFilter(Clock.systemUTC(),(key,limit)->{throw new IllegalStateException("offline");});var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST","/api/public/paypal/webhook"),response,(q,s)->fail("unprotected request"));assertEquals(503,response.getStatus());
    }
}
