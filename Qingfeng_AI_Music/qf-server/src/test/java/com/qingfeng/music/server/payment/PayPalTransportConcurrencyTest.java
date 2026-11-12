package com.qingfeng.music.server.payment;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class PayPalTransportConcurrencyTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void concurrentRequestsShareOneTokenAndExpiredTokenRefreshesOnce() throws Exception {
        var now=new AtomicLong(0);var tokenCalls=new AtomicInteger();var requests=new AtomicInteger();
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var transport=new PayPalCheckoutGateway.HttpTransport(PayPalCheckoutGateway.Environment.SANDBOX,"fake","fake",clock,(method,path,headers,body)->{
            if(path.equals("/v1/oauth2/token")){tokenCalls.incrementAndGet();return json.readTree("{\"access_token\":\"offline-token\",\"expires_in\":100}");}
            requests.incrementAndGet();return json.readTree("{}");
        });
        var pool=Executors.newFixedThreadPool(12);
        try{
            var tasks=new ArrayList<Callable<Void>>();for(int n=0;n<30;n++)tasks.add(()->{transport.send("GET","/offline",Map.of(),null);return null;});
            for(var task:pool.invokeAll(tasks))task.get();assertEquals(1,tokenCalls.get());assertEquals(30,requests.get());
            now.set(91000);for(var task:pool.invokeAll(tasks))task.get();assertEquals(2,tokenCalls.get());
        }finally{pool.shutdownNow();}
    }
    @Test void authorizationFailureRetriesOnceButDoesNotRetryUnknownCaptureFailure(){
        var tokenCalls=new AtomicInteger();var apiCalls=new AtomicInteger();
        var transport=new PayPalCheckoutGateway.HttpTransport(PayPalCheckoutGateway.Environment.SANDBOX,"fake","fake",Clock.systemUTC(),(method,path,headers,body)->{
            if(path.equals("/v1/oauth2/token")){tokenCalls.incrementAndGet();return json.readTree("{\"access_token\":\"offline-token\",\"expires_in\":100}");}
            if(apiCalls.incrementAndGet()==1)throw new PayPalCheckoutGateway.ProviderHttpException(401);return json.readTree("{}");
        });
        transport.send("POST","/capture",Map.of("PayPal-Request-Id","same-key"),"{}");assertEquals(2,tokenCalls.get());assertEquals(2,apiCalls.get());
        var failed=new AtomicInteger();
        var unknown=new PayPalCheckoutGateway.HttpTransport(PayPalCheckoutGateway.Environment.SANDBOX,"fake","fake",Clock.systemUTC(),(method,path,headers,body)->{
            if(path.equals("/v1/oauth2/token"))return json.readTree("{\"access_token\":\"offline-token\",\"expires_in\":100}");failed.incrementAndGet();throw new PayPalCheckoutGateway.ProviderHttpException(503);
        });
        assertThrows(PayPalCheckoutGateway.ProviderHttpException.class,()->unknown.send("POST","/capture",Map.of(),"{}"));assertEquals(1,failed.get());
    }
    @Test void blockedUpstreamIsBoundedAndRejectedRequestsNeverReachOAuthOrApi() throws Exception {
        var entered=new CountDownLatch(32);var release=new CountDownLatch(1);var requests=new AtomicInteger();var oauth=new AtomicInteger();
        var transport=new PayPalCheckoutGateway.HttpTransport(PayPalCheckoutGateway.Environment.SANDBOX,"fake","fake",Clock.systemUTC(),(method,path,headers,body)->{
            if(path.equals("/v1/oauth2/token")){oauth.incrementAndGet();return json.readTree("{\"access_token\":\"offline-token\",\"expires_in\":100}");}
            requests.incrementAndGet();entered.countDown();
            try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Offline test did not release provider");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return json.readTree("{}");
        });
        var pool=Executors.newFixedThreadPool(32);var futures=new ArrayList<Future<?>>();
        try{
            for(int n=0;n<32;n++)futures.add(pool.submit(()->transport.send("GET","/offline",Map.of(),null)));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(PayPalCheckoutGateway.ProviderBusy.class,()->transport.send("POST","/capture",Map.of(),"{}"));
            assertEquals(32,requests.get());assertEquals(1,oauth.get());
            release.countDown();for(var future:futures)future.get(5,TimeUnit.SECONDS);
            transport.send("GET","/offline",Map.of(),null);assertEquals(33,requests.get());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void oauthAndApiFailuresAlwaysReleaseTheirPermit(){
        var oauthFails=new AtomicBoolean(true);var calls=new AtomicInteger();
        var transport=new PayPalCheckoutGateway.HttpTransport(PayPalCheckoutGateway.Environment.SANDBOX,"fake","fake",Clock.systemUTC(),(method,path,headers,body)->{
            calls.incrementAndGet();
            if(path.equals("/v1/oauth2/token")){
                if(oauthFails.get())throw new PayPalCheckoutGateway.ProviderHttpException(503);
                return json.readTree("{\"access_token\":\"offline-token\",\"expires_in\":100}");
            }
            throw new PayPalCheckoutGateway.ProviderHttpException(503);
        });
        for(int n=0;n<40;n++)assertThrows(PayPalCheckoutGateway.ProviderHttpException.class,()->transport.send("GET","/offline",Map.of(),null));
        oauthFails.set(false);
        for(int n=0;n<40;n++)assertThrows(PayPalCheckoutGateway.ProviderHttpException.class,()->transport.send("GET","/offline",Map.of(),null));
        assertEquals(81,calls.get());
    }
}
