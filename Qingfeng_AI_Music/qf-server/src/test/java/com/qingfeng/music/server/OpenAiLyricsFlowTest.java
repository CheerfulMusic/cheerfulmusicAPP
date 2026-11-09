package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.settings.CreativePlan;
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
        @Bean @Primary OfflineGpt offlineGpt(){return new OfflineGpt();}
    }
    static class OfflineGpt implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();
        public boolean isConfigured(){return true;}
        public String source(){return "OPENAI";}
        public Draft write(WorkApi.Work work,String language){
            calls.incrementAndGet();
            return new Draft("门口的小碗","[Verse]\n门口的小碗还在\n[Chorus]\n愿你走得慢一点",
                new CreativePlan("","Acoustic guitar","","按所选风格编配","Selected style","AUTO"));
        }
    }
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired LyricsCompositionService composer;
    @Autowired CreativePlan.Repository plans;
    @Autowired OfflineGpt writer;

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
}
