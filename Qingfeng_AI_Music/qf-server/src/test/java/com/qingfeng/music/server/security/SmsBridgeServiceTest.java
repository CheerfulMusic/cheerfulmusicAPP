package com.qingfeng.music.server.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmsBridgeServiceTest {
    private static final String SECRET="fixture_bridge_secret_with_at_least_43_safe_characters";
    private static final class Time extends Clock {long now=1000000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    private static byte[] body(String phone,String code){return ("{\"to\":\""+phone+"\",\"type\":\"SignIn\",\"payload\":{\"code\":\""+code+"\",\"message\":\"ignored arbitrary text\"},\"ip\":\"untrusted\"}").getBytes(StandardCharsets.UTF_8);}
    private static void send(SmsBridgeService service,String phone,String code){service.deliver(List.of("Bearer "+SECRET),"application/json",-1,new ByteArrayInputStream(body(phone,code)));}
    @Test void bearerIsRequiredBeforeAnyBodyReadAndDefaultIsClosed(){
        var service=new SmsBridgeService(true,SECRET,(phone,code)->fail(),200,Clock.systemUTC());
        var neverRead=new InputStream(){public int read(){throw new AssertionError("unauthorized request body must not be read");}};
        assertEquals(401,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer wrong"),"application/json",1,neverRead)).status());
        assertEquals(401,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET,"Bearer "+SECRET),"application/json",1,neverRead)).status());
        var disabled=new SmsBridgeService(false,"",null,200,Clock.systemUTC());assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->disabled.deliver(List.of(),null,0,neverRead)).status());
        assertThrows(IllegalStateException.class,()->new SmsBridgeService(true,"short",(phone,code)->{},200,Clock.systemUTC()));
    }
    @Test void repeatedAcknowledgedDeliveryIsIdempotentAndNewCodesHaveCooldown(){
        var sent=new AtomicInteger();var time=new Time();var service=new SmsBridgeService(true,SECRET,(phone,code)->sent.incrementAndGet(),200,time);
        send(service,"+447700900123","123456");send(service,"447700900123","123456");assertEquals(1,sent.get());
        assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447700900123","123457")).status());
        time.now+=61000;send(service,"447700900123","123457");assertEquals(2,sent.get());
    }
    @Test void failedOrConcurrentDeliveryIsNeverAutomaticallyResent()throws Exception{
        var called=new AtomicInteger();var service=new SmsBridgeService(true,SECRET,(phone,code)->{called.incrementAndGet();throw new IllegalStateException("private OTP and credentials");},200,Clock.systemUTC());
        assertEquals(502,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447700900123","123456")).status());
        assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447700900123","123456")).status());assertEquals(1,called.get());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var concurrent=new SmsBridgeService(true,SECRET,(phone,code)->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}},200,Clock.systemUTC());
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->send(concurrent,"447700900124","123456"));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->send(concurrent,"447700900124","123456")).status());release.countDown();first.get(5,TimeUnit.SECONDS);send(concurrent,"447700900124","123456");}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void chunkedBodiesInvalidPhonesCodesAndMessageTypesAreRejected(){
        var service=new SmsBridgeService(true,SECRET,(phone,code)->fail(),200,Clock.systemUTC());
        assertEquals(413,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET),"application/json",-1,new ByteArrayInputStream(new byte[2049]))).status());
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447700900123","anything")).status());
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->send(service,"8612345678901","123456")).status());
        byte[] bad="{\"to\":\"447700900123\",\"type\":\"MKT\",\"payload\":{\"code\":\"123456\"}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET),"application/json",bad.length,new ByteArrayInputStream(bad))).status());
    }
    @Test void globalHourlyAndPerPhoneHourlyCapsAreEnforced(){
        var time=new Time();var service=new SmsBridgeService(true,SECRET,(phone,code)->{},2,time);
        send(service,"447700900123","123456");send(service,"447700900124","123456");assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447700900125","123456")).status());
        time.now+=3600001;send(service,"447700900125","123456");
        var phoneLimit=new SmsBridgeService(true,SECRET,(phone,code)->{},200,time);
        for(int i=0;i<5;i++){send(phoneLimit,"447700900126","12345"+i);time.now+=61000;}
        assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(phoneLimit,"447700900126","999999")).status());
    }
}
