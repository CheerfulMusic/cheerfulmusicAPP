package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.ExecutionRepository;
import com.qingfeng.music.generation.application.GenerationWorker;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** 真实准入和任务持久化，所有模型调用都由离线替身响应。 */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:kie_capacity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.generation.enabled=true","qingfeng.generation.max-in-flight=100",
    "qingfeng.usage.music.concurrent-global=100","qingfeng.usage.music.concurrent-per-creator=1"
})
@ActiveProfiles("test")
@Import(KieMusicCapacityIntegrationTest.Offline.class)
class KieMusicCapacityIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Offline {@Bean @Primary Provider provider(){return new Provider();}}
    static class Provider implements MusicProvider {
        final AtomicInteger submissions=new AtomicInteger();
        public String name(){return "kie-capacity-offline";}
        public boolean isConfigured(){return true;}
        public Submission submit(Request request,String key){submissions.incrementAndGet();return new Submission("offline-"+key);}
        public Result query(String task){throw new AssertionError("容量测试不查询付费平台");}
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;
    @Autowired GenerationApi tasks;@Autowired GenerationWorker worker;
    @Autowired JdbcTemplate jdbc;@Autowired Provider provider;
    record Fixture(String owner,String work,String revision){}

    @BeforeEach void finishPreviousOfflineTasks(){jdbc.update("UPDATE generation_task SET status='FAILED' WHERE provider=?",provider.name());provider.submissions.set(0);}
    Fixture fixture(){
        String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","旅行","故事","祝福","民谣","歌曲"));
        var revision=lyrics.save(owner,work.id(),0,"容量验证","已确认歌词");lyrics.confirm(owner,work.id(),revision.id());
        return new Fixture(owner,work.id(),revision.id());
    }
    void submit(Fixture f){tasks.submit(f.owner(),f.work(),f.revision(),UUID.randomUUID().toString());}

    @Test void tenCreatorsAreAcceptedAndDispatchedWithoutWaitingForPreviousSongsToFinish(){
        var first=fixture();submit(first);for(int i=1;i<10;i++)submit(fixture());
        assertEquals("PERSONAL_GENERATION_BUSY",assertThrows(DomainException.class,()->submit(first)).code());
        // 只在本测试内跳过撤销等待窗口，让真实Worker立即处理离线任务。
        jdbc.update("UPDATE generation_task SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE provider=?",provider.name());
        for(int i=0;i<10;i++)assertTrue(worker.runOnce(ExecutionRepository.Phase.SUBMIT),"第"+(i+1)+"首离线任务应可领取");
        assertEquals(10,provider.submissions.get());
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE provider=? AND status='RUNNING'",Integer.class,provider.name()));
    }

    @Test void applicationStillHasABoundedAdmissionBudgetAtOneHundred(){
        for(int i=0;i<100;i++)submit(fixture());
        assertEquals("GLOBAL_GENERATION_BUSY",assertThrows(DomainException.class,()->submit(fixture())).code());
        assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE provider=? AND status='QUEUED'",Integer.class,provider.name()));
        assertEquals(0,provider.submissions.get());
    }
}
