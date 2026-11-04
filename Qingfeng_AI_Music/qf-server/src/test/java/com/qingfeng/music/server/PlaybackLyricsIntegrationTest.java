package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.common.exception.DomainException;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:playback_sync;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true","qingfeng.lyrics.timing.enabled=true","qingfeng.usage.timing.daily-per-creator=2"})
@ActiveProfiles("test") @Import({PlaybackLyricsIntegrationTest.Offline.class,GenerationSubmissionIntegrationTest.Providers.class})
class PlaybackLyricsIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)static class Offline{@Bean @Primary Timing timing(){return new Timing();}}
    static class Timing implements LyricsTimingProvider {
        AtomicInteger calls=new AtomicInteger();String mode="READY";CountDownLatch entered,release;
        public boolean isConfigured(){return true;}
        public List<PlaybackLyrics.Word> fetch(String task,String url){calls.incrementAndGet();if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("测试锁超时");}catch(InterruptedException e){throw new IllegalStateException(e);}}
            if(mode.equals("FAILED"))throw new Rejected();if(mode.equals("UNKNOWN"))throw new IllegalStateException("网络响应丢失");return List.of(new PlaybackLyrics.Word("[Verse]\n实际唱出的歌词\n",8,12));}
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;@Autowired MediaService media;
    @Autowired PlaybackLyricsService service;@Autowired JdbcTemplate jdbc;@Autowired Timing timing;
    @BeforeEach void reset(){timing.calls.set(0);timing.mode="READY";timing.entered=null;timing.release=null;}
    record Fixture(String owner,String work,String revision,String asset){}
    Fixture fixture(){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","歌曲"));var revision=lyrics.save(owner,work.id(),0,"歌曲","原始已确认歌词");lyrics.confirm(owner,work.id(),revision.id());return new Fixture(owner,work.id(),revision.id(),asset(owner,work.id(),revision.id()));}
    String asset(String owner,String work,String revision){var task=tasks.submit(owner,work,revision,UUID.randomUUID().toString());String id=media.store(task.id(),work,new ByteArrayInputStream(new GiftWorkflowIntegrationTest().wav()));jdbc.update("UPDATE generation_task SET provider='kie-suno',provider_task_id='upstream-task',audio_url='https://cdn.example/song.mp3',status='SUCCEEDED',asset_id=? WHERE id=?",id,task.id());return id;}
    @Test void readsNeverCallProviderAndReadyCacheIsBoundToOriginalAudio(){
        var f=fixture();assertEquals("NOT_REQUESTED",service.get(f.owner,f.work,f.asset).status());assertEquals(0,timing.calls.get());String key=UUID.randomUUID().toString();var ready=service.sync(f.owner,f.work,f.asset,key);assertEquals("READY",ready.status());assertEquals(8,ready.lines().get(0).start());
        lyrics.save(f.owner,f.work,0,"改后的标题","后来编辑的歌词");assertEquals("原始已确认歌词",service.get(f.owner,f.work,f.asset).text());service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString());assertEquals(1,timing.calls.get());
    }
    @Test void otherCreatorsCannotReadOrSyncPrivateTimeline(){var f=fixture();String other=identity.create().creatorId();assertEquals("WORK_NOT_FOUND",assertThrows(DomainException.class,()->service.get(other,f.work,f.asset)).code());assertThrows(DomainException.class,()->service.sync(other,f.work,f.asset,UUID.randomUUID().toString()));assertEquals(0,timing.calls.get());}
    @Test void simultaneousRequestsForSameAssetOnlyFetchOnce()throws Exception{
        var f=fixture();timing.entered=new CountDownLatch(1);timing.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{var first=pool.submit(()->service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString()));assertTrue(timing.entered.await(3,TimeUnit.SECONDS));assertEquals("FETCHING",service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString()).status());assertEquals(1,timing.calls.get());timing.release.countDown();assertEquals("READY",first.get(5,TimeUnit.SECONDS).status());}finally{timing.release.countDown();pool.shutdownNow();}
    }
    @Test void reusingRequestKeyAcrossDifferentAudioDoesNotBypassDailyQuota(){
        var f=fixture();String second=asset(f.owner,f.work,f.revision),third=asset(f.owner,f.work,f.revision),key=UUID.randomUUID().toString();service.sync(f.owner,f.work,f.asset,key);service.sync(f.owner,f.work,second,key);assertEquals(2,timing.calls.get());assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->service.sync(f.owner,f.work,third,key)).code());assertEquals(2,timing.calls.get());
    }
    @Test void knownFailureRequiresAnExplicitNewRequestAndUnknownIsNotReposted(){
        var f=fixture();String key=UUID.randomUUID().toString();timing.mode="FAILED";assertEquals("FAILED",service.sync(f.owner,f.work,f.asset,key).status());service.sync(f.owner,f.work,f.asset,key);assertEquals(1,timing.calls.get());timing.mode="UNKNOWN";assertEquals("UNKNOWN",service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString()).status());service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString());assertEquals(2,timing.calls.get());
    }
    @Test void personalConcurrencyCoversDifferentAudio()throws Exception{
        var f=fixture();String second=asset(f.owner,f.work,f.revision);timing.entered=new CountDownLatch(1);timing.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{var first=pool.submit(()->service.sync(f.owner,f.work,f.asset,UUID.randomUUID().toString()));assertTrue(timing.entered.await(3,TimeUnit.SECONDS));assertEquals("PERSONAL_GENERATION_BUSY",assertThrows(DomainException.class,()->service.sync(f.owner,f.work,second,UUID.randomUUID().toString())).code());assertEquals(1,timing.calls.get());timing.release.countDown();first.get(5,TimeUnit.SECONDS);}finally{timing.release.countDown();pool.shutdownNow();}
    }
}
