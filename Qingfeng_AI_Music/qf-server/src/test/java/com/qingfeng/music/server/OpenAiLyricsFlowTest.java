package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.settings.CreativePlan;
import com.qingfeng.music.common.exception.DomainException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** H2 隔离流程验证；不加载 mysql profile、不发送真实模型请求。 */
@SpringBootTest(properties="qingfeng.lyrics.enabled=true")
@ActiveProfiles("test")
@Import(OpenAiLyricsFlowTest.Offline.class)
class OpenAiLyricsFlowTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean OfflineGpt offlineGpt(){return new OfflineGpt();}
        @Bean OfflineDeepSeek offlineDeepSeek(){return new OfflineDeepSeek();}
        @Bean @Primary LyricsWriter failover(OfflineGpt gpt,OfflineDeepSeek deepSeek){return new FallbackLyricsWriter(gpt,deepSeek);}
    }
    static class OfflineGpt implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();
        String failureCode;
        public boolean isConfigured(){return true;}
        public String source(){return "OPENAI";}
        public Draft write(WorkApi.Work work,String language){
            calls.incrementAndGet();
            if(failureCode!=null)throw new DomainException(failureCode,"offline failure");
            return new Draft("门口的小碗","[Verse]\n门口的小碗还在\n[Chorus]\n愿你走得慢一点",
                new CreativePlan("","Acoustic guitar","","按所选风格编配","Selected style","AUTO"));
        }
    }
    static class OfflineDeepSeek implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();
        Direction direction;
        String instructions;
        public boolean isConfigured(){return true;}
        public String source(){return "DEEPSEEK";}
        public Draft write(WorkApi.Work work,String language){
            calls.incrementAndGet();
            return new Draft("回家的灯","[Verse]\n灯亮着\n[Chorus]\n回家的路");
        }
        public Draft write(WorkApi.Work work,String language,String instructions,String previous,Direction direction){
            this.instructions=instructions;
            this.direction=direction;
            return write(work,language);
        }
    }
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired LyricsCompositionService composer;
    @Autowired CreativePlan.Repository plans;
    @Autowired OfflineGpt writer;
    @Autowired OfflineDeepSeek backup;

    @Test void gptVersionAndPlanPersistWithCorrectSourceWithoutOverwritingOrDuplicateCalls(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("妈妈","生日","门口的小碗","愿她安心","民谣",""));
        var manual=lyrics.save(owner,work.id(),0,"旧标题","已有手写歌词");
        String key=UUID.randomUUID().toString();int before=writer.calls.get();
        var generated=composer.compose(owner,work.id(),0,key,"zh-CN");
        assertEquals("OPENAI",generated.source());assertFalse(generated.confirmed());
        assertEquals(generated.id(),composer.compose(owner,work.id(),0,key,"zh-CN").id());
        assertEquals(before+1,writer.calls.get());
        assertEquals("已有手写歌词",lyrics.get(owner,work.id(),manual.id()).lyrics());
        assertEquals("Acoustic guitar",plans.find(work.id(),CreativePlan.sourceHash(work)).orElseThrow().suggestedArrangement());
        var another=composer.compose(owner,work.id(),0,UUID.randomUUID().toString(),"zh-CN","更温暖",generated.id());
        assertNotEquals(generated.id(),another.id());assertEquals("OPENAI",another.source());
        assertEquals(3,lyrics.list(owner,work.id()).size());
    }
    @Test void confirmedConnectionFailureUsesDeepSeekOnceAndPersistsItsActualSource(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","","夜里回家","","民谣","","MALE"));
        String key=UUID.randomUUID().toString();
        int calls=backup.calls.get();
        writer.failureCode="LYRICS_PROVIDER_CONNECT_FAILED";
        try{
            var result=composer.compose(owner,work.id(),0,key,"zh-CN","保持朴素",null);
            assertEquals("DEEPSEEK",result.source());
            assertEquals(result.id(),composer.compose(owner,work.id(),0,key,"zh-CN","保持朴素",null).id());
            assertEquals(calls+1,backup.calls.get());
            assertEquals("MALE",backup.direction.vocalGender());
            assertTrue(backup.instructions.contains("保持朴素"));
        }finally{writer.failureCode=null;}
    }
    @Test void uncertainGptResultKeepsOriginalRequestAndNeverInvokesDeepSeek(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","","等最后一班车","","民谣",""));
        String key=UUID.randomUUID().toString();
        int calls=backup.calls.get();
        writer.failureCode="LYRICS_PROVIDER_TIMEOUT";
        try{
            assertEquals(writer.failureCode,assertThrows(DomainException.class,()->composer.compose(owner,work.id(),0,key,"zh-CN")).code());
            assertEquals("UNKNOWN",composer.requestState(owner,work.id(),key).status());
            assertEquals(calls,backup.calls.get());
            assertTrue(lyrics.list(owner,work.id()).isEmpty());
        }finally{writer.failureCode=null;}
    }
}
