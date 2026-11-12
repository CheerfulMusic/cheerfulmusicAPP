package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.lyrics.LyricsMapper;
import com.qingfeng.music.infrastructure.persistence.lyrics.MyBatisLyricsRepository;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.settings.CreativePlan;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.app.LyricsController;
import com.qingfeng.music.server.web.app.LyricsController.ComposeRequest;
import com.qingfeng.music.server.web.publicapi.SystemInfoController;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
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
        String output;
        String instructions;
        String previousLyrics;
        public boolean isConfigured(){return true;}
        public String source(){return "OPENAI";}
        public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,Direction direction){
            this.instructions=instructions;
            this.previousLyrics=previousLyrics;
            return write(work,language);
        }
        public Draft write(WorkApi.Work work,String language){
            calls.incrementAndGet();
            if(failureCode!=null)throw new DomainException(failureCode,"offline failure");
            return new Draft("门口的小碗",output==null?"[Verse]\n门口的小碗还在\n[Chorus]\n愿你走得慢一点":output,
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
    @Autowired CompositionRequests requests;
    @Autowired CreativePlan.Repository plans;
    @Autowired OfflineGpt writer;
    @Autowired OfflineDeepSeek backup;
    @Autowired LyricsController controller;
    @Autowired SystemInfoController system;
    private static final OfflineGpt CUSTOM_GPT = new OfflineGpt();
    private static final OfflineDeepSeek CUSTOM_DEEPSEEK = new OfflineDeepSeek();
    @TestBean(name="customLyricsWriter", methodName="offlineCustomWriter", enforceOverride=true)
    LyricsWriter customWriter;

    static LyricsWriter offlineCustomWriter(){return new FallbackLyricsWriter(CUSTOM_GPT,CUSTOM_DEEPSEEK);}

    @Test void successfulCustomCreationAndRegenerationUseOpenAi(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","", "送别之后的回忆","","民谣",""));
        var principal=new CreatorPrincipal(owner);
        int sharedCalls=writer.calls.get(), customCalls=CUSTOM_GPT.calls.get(), backupCalls=CUSTOM_DEEPSEEK.calls.get();
        var first=new ComposeRequest(0L,UUID.randomUUID().toString(),"zh-CN","",null,null,false);
        assertTrue(system.info().capabilities().customLyricsGeneration());
        var generated=controller.compose(principal,work.id(),first);
        assertEquals(generated.id(),controller.compose(principal,work.id(),first).id());
        var next=new ComposeRequest(0L,UUID.randomUUID().toString(),"zh-CN","更温暖",generated.id(),null,false);
        var regenerated=controller.compose(principal,work.id(),next);
        assertEquals("OPENAI",generated.source());
        assertEquals("OPENAI",regenerated.source());
        assertNotEquals(generated.id(),regenerated.id());
        assertEquals(customCalls+2,CUSTOM_GPT.calls.get());
        assertEquals(sharedCalls,writer.calls.get());
        assertEquals(backupCalls,CUSTOM_DEEPSEEK.calls.get());
    }

    @Test void customCreationFallsBackOnceForAnExplicitProviderRejection(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","","夜里回家","","民谣",""));
        var principal=new CreatorPrincipal(owner);
        var request=new ComposeRequest(0L,UUID.randomUUID().toString(),"zh-CN","保持朴素",null,null,false);
        int customCalls=CUSTOM_GPT.calls.get(), backupCalls=CUSTOM_DEEPSEEK.calls.get();
        CUSTOM_GPT.failureCode="LYRICS_PROVIDER_REJECTED";
        try {
            var generated=controller.compose(principal,work.id(),request);
            assertEquals("DEEPSEEK",generated.source());
            assertEquals(generated.id(),controller.compose(principal,work.id(),request).id());
            assertEquals(customCalls+1,CUSTOM_GPT.calls.get());
            assertEquals(backupCalls+1,CUSTOM_DEEPSEEK.calls.get());
            assertTrue(CUSTOM_DEEPSEEK.instructions.contains("保持朴素"));
        } finally {
            CUSTOM_GPT.failureCode=null;
        }
    }

    @Test void customSectionOrderSurvivesGenerationEditsConfirmationAndRegeneration(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","","晚归时留下的灯","","民谣",""));
        var principal=new CreatorPrincipal(owner);
        var sections=List.of("Chorus","Verse 2","Chorus","Interlude");
        String key=UUID.randomUUID().toString();
        var first=new ComposeRequest(0L,key,"zh-CN","",null,"CUSTOM",false,sections);
        int before=CUSTOM_GPT.calls.get();
        CUSTOM_GPT.output="[Chorus]\n灯还亮着\n[Verse 2]\n窗台留着小碗\n[Chorus]\n灯还亮着\n[Interlude]";
        try {
            var generated=controller.compose(principal,work.id(),first);
            assertEquals("OPENAI",generated.source());
            assertEquals("CUSTOM",generated.templateId());
            assertEquals(sections,generated.customSections());
            assertTrue(CUSTOM_GPT.instructions.contains("[Chorus] → [Verse 2] → [Chorus] → [Interlude]"));
            assertEquals(generated.id(),controller.compose(principal,work.id(),first).id());

            var reordered=new ComposeRequest(0L,key,"zh-CN","",null,"CUSTOM",false,
                List.of("Verse 2","Chorus","Chorus","Interlude"));
            assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,
                ()->controller.compose(principal,work.id(),reordered)).code());
            assertEquals(before+1,CUSTOM_GPT.calls.get());

            var edited=lyrics.save(owner,work.id(),0,generated.title(),"窗台上还亮着灯",generated.id());
            var confirmed=lyrics.confirm(owner,work.id(),edited.id());
            assertTrue(confirmed.confirmed());
            assertEquals(sections,confirmed.customSections());
            var next=new ComposeRequest(0L,UUID.randomUUID().toString(),"zh-CN","更温暖",confirmed.id(),"CUSTOM",false,sections);
            var regenerated=controller.compose(principal,work.id(),next);
            assertEquals(sections,regenerated.customSections());
            assertEquals(confirmed.lyrics(),CUSTOM_GPT.previousLyrics);
            assertTrue(CUSTOM_GPT.instructions.contains("[Chorus] → [Verse 2] → [Chorus] → [Interlude]"));
            assertEquals(before+2,CUSTOM_GPT.calls.get());
        } finally {
            CUSTOM_GPT.output=null;
        }
    }

    @Test void missingCustomSectionColumnFailsBeforeClaimingOrCallingTheModel() throws Exception {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:lyrics_pre_custom_sections;MODE=MySQL","sa","");
        try (var connection=dataSource.getConnection()) {
            new JdbcTemplate(dataSource).execute("CREATE TABLE lyric_revision (id VARCHAR(36))");
            var factoryBean=new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            var factory=factoryBean.getObject();
            factory.getConfiguration().addMapper(LyricsMapper.class);
            var repository=new MyBatisLyricsRepository(new SqlSessionTemplate(factory).getMapper(LyricsMapper.class));
            var service=new LyricsCompositionService(works,lyrics,repository,CUSTOM_GPT,requests,true);

            String owner=identity.create().creatorId();
            var work=works.create(owner,new WorkApi.Draft("","","晚归时留下的灯","","民谣",""));
            String key=UUID.randomUUID().toString();
            int before=CUSTOM_GPT.calls.get();
            assertEquals("LYRICS_CUSTOM_STRUCTURE_UNAVAILABLE",assertThrows(DomainException.class,
                ()->service.composeCustom(owner,work.id(),0,key,"zh-CN","",null,"CUSTOM",false,List.of("Verse"))).code());
            assertEquals("NOT_FOUND",service.requestState(owner,work.id(),key).status());
            assertEquals(before,CUSTOM_GPT.calls.get());
        }
    }

    @Test void uncertainCustomRequestRetainsItsOriginalStatus(){
        String owner=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("","","等最后一班车","","民谣",""));
        var request=new ComposeRequest(0L,UUID.randomUUID().toString(),"zh-CN","",null,null,false);
        int backupCalls=CUSTOM_DEEPSEEK.calls.get();
        CUSTOM_GPT.failureCode="LYRICS_PROVIDER_TIMEOUT";
        try {
            assertEquals("LYRICS_PROVIDER_TIMEOUT",assertThrows(DomainException.class,
                ()->controller.compose(new CreatorPrincipal(owner),work.id(),request)).code());
            assertEquals("UNKNOWN",composer.requestState(owner,work.id(),request.requestKey()).status());
            assertEquals(backupCalls,CUSTOM_DEEPSEEK.calls.get());
            assertTrue(lyrics.list(owner,work.id()).isEmpty());
        } finally {
            CUSTOM_GPT.failureCode=null;
        }
    }

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
