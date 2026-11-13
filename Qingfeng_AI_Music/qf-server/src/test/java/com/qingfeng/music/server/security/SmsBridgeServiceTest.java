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
    private static final SmsDestinationPolicy GB=new SmsDestinationPolicy(true,true,true,false,true,"GB");
    private static final class Time extends Clock {long now=1000000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}}
    private static byte[] body(String phone,String code){return body(phone,code,"203.0.113.1");}
    private static byte[] body(String phone,String code,String ip){return ("{\"to\":\""+phone+"\",\"type\":\"SignIn\",\"payload\":{\"code\":\""+code+"\",\"message\":\"ignored arbitrary text\"},\"ip\":\""+ip+"\"}").getBytes(StandardCharsets.UTF_8);}
    private static void send(SmsBridgeService service,String phone,String code){service.deliver(List.of("Bearer "+SECRET),"application/json",-1,new ByteArrayInputStream(body(phone,code)));}
    @Test void bearerIsRequiredBeforeAnyBodyReadAndDefaultIsClosed(){
        var service=new SmsBridgeService(true,SECRET,(phone,code)->fail(),200,50,Clock.systemUTC(),GB);
        var neverRead=new InputStream(){public int read(){throw new AssertionError("unauthorized request body must not be read");}};
        assertEquals(401,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer wrong"),"application/json",1,neverRead)).status());
        assertEquals(401,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET,"Bearer "+SECRET),"application/json",1,neverRead)).status());
        var disabled=new SmsBridgeService(false,"",null,200,50,Clock.systemUTC(),new SmsDestinationPolicy(false,false,false,false,false,""));assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->disabled.deliver(List.of(),null,0,neverRead)).status());
        assertThrows(IllegalStateException.class,()->new SmsBridgeService(true,"short",(phone,code)->{},200,50,Clock.systemUTC(),GB));
    }
    @Test void repeatedAcknowledgedDeliveryIsIdempotentAndNewCodesHaveCooldown(){
        var sent=new AtomicInteger();var time=new Time();var service=new SmsBridgeService(true,SECRET,(phone,code)->sent.incrementAndGet(),200,50,time,GB);
        send(service,"+447400123123","123456");send(service,"447400123123","123456");assertEquals(1,sent.get());
        assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447400123123","123457")).status());
        time.now+=61000;send(service,"447400123123","123457");assertEquals(2,sent.get());
    }
    @Test void failedOrConcurrentDeliveryIsNeverAutomaticallyResent()throws Exception{
        var called=new AtomicInteger();var service=new SmsBridgeService(true,SECRET,(phone,code)->{called.incrementAndGet();throw new IllegalStateException("private OTP and credentials");},200,50,Clock.systemUTC(),GB);
        assertEquals(502,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447400123123","123456")).status());
        assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447400123123","123456")).status());assertEquals(1,called.get());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var concurrent=new SmsBridgeService(true,SECRET,(phone,code)->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}},200,50,Clock.systemUTC(),GB);
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->send(concurrent,"447400123124","123456"));assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(503,assertThrows(SmsBridgeService.Failure.class,()->send(concurrent,"447400123124","123456")).status());release.countDown();first.get(5,TimeUnit.SECONDS);send(concurrent,"447400123124","123456");}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void chunkedBodiesInvalidPhonesCodesAndMessageTypesAreRejected(){
        var service=new SmsBridgeService(true,SECRET,(phone,code)->fail(),200,50,Clock.systemUTC(),GB);
        assertEquals(413,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET),"application/json",-1,new ByteArrayInputStream(new byte[2049]))).status());
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447400123123","anything")).status());
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->send(service,"8612345678901","123456")).status());
        byte[] bad="{\"to\":\"447400123123\",\"type\":\"MKT\",\"payload\":{\"code\":\"123456\"}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->service.deliver(List.of("Bearer "+SECRET),"application/json",bad.length,new ByteArrayInputStream(bad))).status());
    }
    @Test void globalHourlyAndPerPhoneHourlyCapsAreEnforced(){
        var time=new Time();var service=new SmsBridgeService(true,SECRET,(phone,code)->{},2,50,time,GB);
        send(service,"447400123123","123456");send(service,"447400123124","123456");assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(service,"447400123125","123456")).status());
        time.now+=3600001;send(service,"447400123125","123456");
        var phoneLimit=new SmsBridgeService(true,SECRET,(phone,code)->{},200,50,time,GB);
        for(int i=0;i<5;i++){send(phoneLimit,"447400123126","12345"+i);time.now+=61000;}
        assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->send(phoneLimit,"447400123126","999999")).status());
    }
    @Test void hostedConnectorCannotBypassClosedPublicSwitchAndIpLimit(){
        var closed=new SmsBridgeService(true,SECRET,(phone,code)->fail(),200,50,Clock.systemUTC(),
            new SmsDestinationPolicy(true,false,true,false,true,"GB"));
        assertEquals(403,assertThrows(SmsBridgeService.Failure.class,()->send(closed,"447400123456","123456")).status());
        var time=new Time();var sent=new AtomicInteger();
        var limited=new SmsBridgeService(true,SECRET,(phone,code)->sent.incrementAndGet(),200,2,time,GB);
        for(String phone:List.of("447400123456","447400123457"))
            limited.deliver(List.of("Bearer "+SECRET),"application/json",-1,new ByteArrayInputStream(body(phone,"123456","2001:db8::1")));
        assertEquals(429,assertThrows(SmsBridgeService.Failure.class,()->limited.deliver(List.of("Bearer "+SECRET),"application/json",-1,
            new ByteArrayInputStream(body("447400123458","123456","2001:0db8:0:0:0:0:0:1")))).status());
        assertEquals(2,sent.get());
        assertEquals(400,assertThrows(SmsBridgeService.Failure.class,()->limited.deliver(List.of("Bearer "+SECRET),"application/json",-1,
            new ByteArrayInputStream(body("447400123459","123456","localhost")))).status());
    }
}
