package com.qingfeng.music.server;

import com.qingfeng.music.creation.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.server.query.WorkLibraryQuery;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"qingfeng.lyrics.enabled=true","qingfeng.generation.scheduler-enabled=false","qingfeng.credits.enabled=false"})
@ActiveProfiles("test") @Import(QuickCreationIntegrationTest.Offline.class)
class QuickCreationIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary FakeWriter quickWriter(){return new FakeWriter();}
        @Bean @Primary FakeGeneration quickMusic(){return new FakeGeneration();}
    }
    static class FakeWriter implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();Direction direction;boolean fail;
        public boolean isConfigured(){return true;}
        public Draft write(WorkApi.Work work,String language){calls.incrementAndGet();if(fail)throw new DomainException("LYRICS_PROVIDER_TIMEOUT","offline failure");return new Draft("小碗","[Verse]\n橘子放在门边\n[Chorus]\n谢谢你等我回家");}
        public Draft write(WorkApi.Work work,String language,String instructions,String previous,Direction direction){this.direction=direction;return write(work,language);}
    }
    static class FakeGeneration implements GenerationApi {
        final Map<String,Task> tasks=new LinkedHashMap<>();final AtomicInteger purchases=new AtomicInteger();boolean reject,loseAcknowledgement,busy;
        public Preflight preflight(String owner,String work,String revision){if(busy)throw new DomainException("GLOBAL_GENERATION_BUSY","offline capacity");if(reject)throw new DomainException("MUSIC_PROVIDER_UNAVAILABLE","offline");return new Preflight(0,revision,10,1000,0,500,"");}
        public Task submit(String owner,String work,String revision,String key){
            String index=work+key;if(tasks.containsKey(index))return tasks.get(index);
            var task=new Task(UUID.randomUUID().toString(),work,revision,0,key,"小碗","歌词","民谣","offline",GenerationStatus.QUEUED,Instant.now());
            purchases.incrementAndGet();tasks.put(index,task);if(loseAcknowledgement)throw new IllegalStateException("offline lost acknowledgement");return task;
        }
        public List<Task> list(String owner,String work){return tasks.values().stream().filter(t->t.workId().equals(work)).toList();}
        public void retryArchive(String o,String w,String t){}
        public Task cancel(String o,String w,String t){throw new UnsupportedOperationException();}
        public Task closeChecking(String o,String w,String t,boolean a){throw new UnsupportedOperationException();}
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;
    @Autowired LyricsCompositionService composer;@Autowired CompositionRequests requests;
    @Autowired QuickCreationRepository repository;@Autowired FakeWriter writer;@Autowired FakeGeneration music;
    @Autowired MusicSettings settings;@Autowired WorkLibraryQuery library;@Autowired JdbcTemplate jdbc;
    QuickCreationService service;String owner;WorkApi.Work work;
    @BeforeEach void setup(){
        writer.fail=false;music.reject=false;music.loseAcknowledgement=false;music.busy=false;
        owner=identity.create().creatorId();work=works.create(owner,new WorkApi.Draft("妈妈","生日","门口剥好的橘子","谢谢陪伴","温暖民谣",""));
        service=new QuickCreationService(repository,works,lyrics,composer,music,true);
    }
    QuickCreationService.View start(){return service.start(owner,work.id(),work.version(),UUID.randomUUID().toString(),"zh-CN");}
    void due(String id){jdbc.update("UPDATE quick_creation SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP),lease_until=NULL,lease_token=NULL WHERE id=?",id);}

    @Test void oneClickCompletesAndRepeatedRequestsReuseOneLyricAndOneMusicPurchase(){
        String key=UUID.randomUUID().toString();int words=writer.calls.get(),songs=music.purchases.get();
        var flow=service.start(owner,work.id(),work.version(),key,"zh-CN");
        assertEquals(flow.id(),service.start(owner,work.id(),work.version(),key,"zh-CN").id());
        assertEquals("GENERATING",library.list(owner,0).get(0).status());
        assertEquals("QUICK_CREATION_PENDING",assertThrows(DomainException.class,this::start).code());
        assertTrue(service.runOnce());var result=service.get(owner,work.id(),flow.id());
        assertEquals("QUEUED",result.status());assertNotNull(result.taskId());
        assertTrue(lyrics.get(owner,work.id(),result.revisionId()).confirmed());
        assertEquals(words+1,writer.calls.get());assertEquals(songs+1,music.purchases.get());
        assertFalse(service.runOnce());assertEquals(flow.id(),service.start(owner,work.id(),work.version(),key,"zh-CN").id());
        assertEquals(1,lyrics.list(owner,work.id()).size());
    }
    @Test void writingFailureRetainsStoryAndOnlyExplicitRetryCreatesNewAttempt(){
        writer.fail=true;var flow=start();int calls=writer.calls.get();service.runOnce();
        var stopped=service.get(owner,work.id(),flow.id());assertEquals("FAILED",stopped.status());assertTrue(stopped.canRetry());
        assertEquals("LYRICS_PROVIDER_TIMEOUT",stopped.errorCode());assertEquals(work.story(),works.get(owner,work.id()).story());
        assertFalse(service.runOnce());assertEquals(calls+1,writer.calls.get());
        writer.fail=false;service.retry(owner,work.id(),flow.id());service.runOnce();assertNotNull(service.get(owner,work.id(),flow.id()).taskId());
    }
    @Test void musicFailureKeepsSuccessfulLyricsAndRetryDoesNotWriteAnotherVersion(){
        music.reject=true;var flow=start();int calls=writer.calls.get();service.runOnce();
        var failed=service.get(owner,work.id(),flow.id());assertNotNull(failed.revisionId());assertEquals("FAILED",failed.status());
        music.reject=false;service.retry(owner,work.id(),flow.id());service.runOnce();
        assertNotNull(service.get(owner,work.id(),flow.id()).taskId());assertEquals(calls+1,writer.calls.get());assertEquals(1,lyrics.list(owner,work.id()).size());
    }
    @Test void capacityWaitDoesNotRepeatPaidLyricsAndResumesFromMusic(){
        music.busy=true;var flow=start();int calls=writer.calls.get();service.runOnce();
        assertEquals("LYRICS_READY",service.get(owner,work.id(),flow.id()).status());
        assertFalse(service.get(owner,work.id(),flow.id()).canRetry());
        music.busy=false;due(flow.id());service.runOnce();
        assertNotNull(service.get(owner,work.id(),flow.id()).taskId());assertEquals(calls+1,writer.calls.get());
    }
    @Test void oneOwnerCannotAccidentallyQueueMultipleQuickCreations(){
        start();var other=works.create(owner,new WorkApi.Draft("","","第二个故事","","民谣",""));
        assertEquals("PERSONAL_GENERATION_BUSY",assertThrows(DomainException.class,()->service.start(owner,other.id(),other.version(),UUID.randomUUID().toString(),"zh-CN")).code());
    }
    @Test void simultaneousDuplicateClicksCreateOneDurableFlow() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);String key=UUID.randomUUID().toString();
        try{
            var attempts=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<6;i++)attempts.add(pool.submit(()->service.start(owner,work.id(),work.version(),key,"zh-CN").id()));
            var ids=new HashSet<String>();for(var result:attempts)ids.add(result.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,ids.size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quick_creation WHERE work_id=?",Integer.class,work.id()));
        }finally{pool.shutdownNow();}
    }
    @Test void lostMusicAcknowledgementFindsOriginalTaskInsteadOfBuyingAgain(){
        music.loseAcknowledgement=true;int purchases=music.purchases.get();var flow=start();service.runOnce();
        var found=service.get(owner,work.id(),flow.id());assertNotNull(found.taskId());assertFalse(found.canRetry());
        service.retry(owner,work.id(),flow.id());assertFalse(service.runOnce());assertEquals(purchases+1,music.purchases.get());
    }
    @Test void pendingLyricsAfterRestartAreQueriedAndNeverAutomaticallyResubmitted(){
        var flow=start();var entry=repository.find(work.id(),flow.id()).orElseThrow();
        requests.claim(owner,work.id(),entry.lyricsKey(),"offline-existing");int before=writer.calls.get();
        service.runOnce();assertEquals("WRITING",service.get(owner,work.id(),flow.id()).status());assertEquals(before,writer.calls.get());
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",work.id(),entry.lyricsKey());
        due(flow.id());service.runOnce();assertEquals("FAILED",service.get(owner,work.id(),flow.id()).status());assertEquals(before,writer.calls.get());
    }
    @Test void aCompletedLyricWhoseCheckpointWasLostIsReusedAfterRestart(){
        var flow=start();var entry=repository.find(work.id(),flow.id()).orElseThrow();
        var revision=composer.compose(owner,work.id(),work.version(),entry.lyricsKey(),"zh-CN");int before=writer.calls.get();
        service.runOnce();assertEquals(revision.id(),service.get(owner,work.id(),flow.id()).revisionId());assertEquals(before,writer.calls.get());
    }
    @Test void changingOrDeletingWorkStopsQueuedCreationAndOtherOwnersCannotReadIt(){
        var flow=start();String stranger=identity.create().creatorId();
        assertThrows(DomainException.class,()->service.get(stranger,work.id(),flow.id()));
        assertThrows(DomainException.class,()->service.retry(stranger,work.id(),flow.id()));
        works.update(owner,work.id(),work.version(),new WorkApi.Draft("妈妈","生日","新的故事","谢谢","温暖民谣",""));
        service.runOnce();assertEquals("WORK_VERSION_CONFLICT",service.get(owner,work.id(),flow.id()).errorCode());assertFalse(service.get(owner,work.id(),flow.id()).canRetry());
        works.delete(owner,work.id(),false);assertFalse(service.runOnce());
    }
    @Test void expiredLeaseCannotOverwriteANewerWorkersCheckpoint(){
        var flow=start();repository.claim("first-token").orElseThrow();
        jdbc.update("UPDATE quick_creation SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",flow.id());
        repository.claim("second-token").orElseThrow();
        assertFalse(repository.checkpoint(flow.id(),"first-token","FAILED",null,null,"late",0));
        assertTrue(repository.checkpoint(flow.id(),"second-token","WRITING",null,null,null,0));
        repository.release(flow.id(),"second-token");
        // 清理本测试自己的排队状态，避免后续手动 worker 扫描到它。
        jdbc.update("UPDATE quick_creation SET status='FAILED' WHERE id=?",flow.id());
    }
    @Test void musicDirectionsAndExclusionsAreSentBeforeTheFirstLyric(){
        var prefs=new MusicSettings.Preferences(1,null,Map.of("genre",List.of("rap"),"tempo",List.of("fast")),"FEMALE","","不要苦情");
        // 使用目录中的实际速度选项，避免将测试写死在某个中文展示名上。
        String tempo=settings.catalog().groups().stream().filter(g->g.id().equals("tempo")).findFirst().orElseThrow().options().get(0).id();
        prefs=new MusicSettings.Preferences(1,null,Map.of("genre",List.of("rap"),"tempo",List.of(tempo)),"FEMALE","","不要苦情");
        work=settings.save(owner,work.id(),work.version(),prefs).work();start();service.runOnce();
        assertTrue(writer.direction.style().contains("rap"));assertTrue(writer.direction.exclusions().contains("不要苦情"));assertEquals("FEMALE",writer.direction.vocalGender());
    }
    @AfterEach void retireOwnPendingRows(){jdbc.update("UPDATE quick_creation SET status='FAILED',lease_token=NULL,lease_until=NULL WHERE work_id=? AND status IN ('QUEUED','WRITING','LYRICS_READY','SUBMITTING_MUSIC')",work.id());}
}
