package com.qingfeng.music.generation.application;

import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.application.port.MusicProvider.*;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.common.exception.DomainException;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResilientMusicProviderTest {
    static class Time extends Clock {Instant now=Instant.parse("2026-11-06T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
    static class Fake implements MusicProvider {
        int submits,queries;RuntimeException failure;
        public String name(){return "offline";}public boolean isConfigured(){return true;}
        public Submission submit(Request r,String key){submits++;if(failure!=null)throw failure;return new Submission("accepted");}
        public Result query(String id){queries++;return new Result(id,GenerationStatus.RUNNING,null);}
    }
    Request request=new Request("title","lyrics","folk");
    @Test void balanceAndAuthenticationProblemsStopNewSubmissionsButNotQueries(){
        var fake=new Fake();var time=new Time();var guarded=new ResilientMusicProvider(fake,time);fake.failure=new Rejected("PROVIDER_BALANCE_LOW");
        assertThrows(Rejected.class,()->guarded.submit(request,"1"));assertThrows(DomainException.class,guarded::checkAdmission);
        assertThrows(NotSubmitted.class,()->guarded.submit(request,"2"));assertEquals(1,fake.submits);
        guarded.query("original");assertEquals(1,fake.queries);
        time.now=time.now.plusSeconds(601);fake.failure=null;guarded.checkAdmission();assertEquals("accepted",guarded.submit(request,"3").providerTaskId());
    }
    @Test void ambiguousCallsAreNeverRetriedAndRepeatedFailuresOpenTheCircuit(){
        var fake=new Fake();var guarded=new ResilientMusicProvider(fake,new Time());fake.failure=new IllegalStateException("ambiguous");
        for(int n=0;n<3;n++)assertThrows(IllegalStateException.class,()->guarded.submit(request,"same"));
        assertEquals(3,fake.submits);assertThrows(DomainException.class,guarded::checkAdmission);
    }
    @Test void oneInvalidInputDoesNotBlockOtherUsers(){
        var fake=new Fake();var guarded=new ResilientMusicProvider(fake,new Time());fake.failure=new Rejected("PROVIDER_INPUT_REJECTED");
        assertThrows(Rejected.class,()->guarded.submit(request,"1"));guarded.checkAdmission();fake.failure=null;guarded.submit(request,"2");assertEquals(2,fake.submits);
    }
}
