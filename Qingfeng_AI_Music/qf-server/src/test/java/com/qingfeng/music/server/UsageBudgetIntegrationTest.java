package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.infrastructure.persistence.usage.JdbcUsageBudget;
import com.qingfeng.music.usage.UsageBudget.Kind;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:usage_budget;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true","qingfeng.lyrics.enabled=true","qingfeng.usage.music.daily-per-creator=2","qingfeng.usage.music.daily-global=3","qingfeng.usage.music.concurrent-global=2","qingfeng.usage.lyrics.daily-per-creator=2","qingfeng.usage.lyrics.concurrent-global=2"})
@ActiveProfiles("test") @Import(UsageBudgetIntegrationTest.Offline.class)
class UsageBudgetIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary MusicProvider music(){return new MusicProvider(){public String name(){return "offline-budget";}public boolean isConfigured(){return true;}public Submission submit(Request r,String k){throw new AssertionError("不得调用外部模型");}public Result query(String id){throw new AssertionError("不得查询外部模型");}};}
        @Bean @Primary Writer writer(){return new Writer();}
    }
    static class Writer implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();String requirements,previous;RuntimeException failure;
        public boolean isConfigured(){return true;}
        public Draft write(WorkApi.Work work,String lang){throw new AssertionError("应携带修改上下文");}
        public Draft write(WorkApi.Work work,String lang,String instructions,String prior){calls.incrementAndGet();if(failure!=null)throw failure;requirements=instructions;previous=prior;return new Draft("新的歌","[Chorus]\n愿你的每一天都有光");}
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;
    @Autowired LyricsCompositionService composer;@Autowired CompositionRequests requests;@Autowired JdbcUsageBudget budget;
    @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;@Autowired Writer writer;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    @BeforeEach void reset(){jdbc.update("DELETE FROM model_usage_reservation");jdbc.update("UPDATE generation_task SET status='FAILED'");jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED'");writer.calls.set(0);writer.failure=null;}
    record Fixture(String owner,String work,String revision){}
    Fixture fixture(String owner){var w=works.create(owner,new WorkApi.Draft("妈妈","生日","一起做饭","愿你快乐","民谣","故事"));var r=lyrics.save(owner,w.id(),0,"歌","旧歌词\n请保留这一段");lyrics.confirm(owner,w.id(),r.id());return new Fixture(owner,w.id(),r.id());}
    Fixture fixture(){return fixture(identity.create().creatorId());}
    String submit(Fixture f){return tasks.submit(f.owner,f.work,f.revision,UUID.randomUUID().toString()).id();}
    int reservations(){return jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation",Integer.class);}
    @Test void simultaneousIdempotentSubmissionUsesOneSlotAndOneReservation()throws Exception{
        var f=fixture();String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->tasks.submit(f.owner,f.work,f.revision,key));var b=pool.submit(()->tasks.submit(f.owner,f.work,f.revision,key));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());assertEquals(1,reservations());assertEquals(1,budget.allowance(f.owner,Kind.MUSIC).active());}finally{pool.shutdownNow();}
    }
    @Test void concurrentDifferentWorksCannotBypassPersonalConcurrency()throws Exception{
        var a=fixture();var b=fixture(a.owner);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        java.util.function.Function<Fixture,Callable<String>> action=f->()->{start.await();try{submit(f);return "accepted";}catch(DomainException e){return e.code();}};
        try{var x=pool.submit(action.apply(a));var y=pool.submit(action.apply(b));start.countDown();var outcomes=List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter("accepted"::equals).count());assertTrue(outcomes.contains("PERSONAL_GENERATION_BUSY"));assertEquals(1,reservations());}finally{pool.shutdownNow();}
    }
    @Test void globalConcurrencyIncludesAllCreatorsAndUnknownTasks(){
        var a=fixture();var b=fixture();String first=submit(a);submit(b);jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN' WHERE id=?",first);
        var c=fixture();assertEquals("GLOBAL_GENERATION_BUSY",assertThrows(DomainException.class,()->submit(c)).code());assertEquals(2,reservations());assertTrue(tasks.list(c.owner,c.work).isEmpty());
    }
    @Test void failuresStillCountAndRejectedRequestsCreateNoTask(){
        var f=fixture();for(int n=0;n<2;n++)jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",submit(f));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->submit(f)).code());assertEquals(2,reservations());assertEquals(0,budget.allowance(f.owner,Kind.MUSIC).dailyRemaining());assertEquals(2,tasks.list(f.owner,f.work).size());
    }
    @Test void globalDailyLimitAppliesAcrossNewCreatorIdentities(){
        for(int n=0;n<3;n++)jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",submit(fixture()));
        assertEquals("GLOBAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->submit(fixture())).code());assertEquals(3,reservations());
    }
    @Test void rejectedLyricsClaimRollsBackAndCanBeRetriedAfterSlotIsFree(){
        var f=fixture();String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();assertTrue(requests.claim(f.owner,f.work,first,"first"));
        assertEquals("PERSONAL_GENERATION_BUSY",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,second,"zh-CN")).code());assertTrue(requests.find(f.work,second).isEmpty());assertEquals(1,reservations());assertEquals(0,writer.calls.get());
        jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED' WHERE work_id=? AND request_key=?",f.work,first);
        composer.compose(f.owner,f.work,0,second,"zh-CN");assertEquals(1,writer.calls.get());assertEquals(2,reservations());
    }
    @Test void failedLyricsReleaseConcurrencyKeepDailyCostAndNeverResubmitTheSameKey(){
        var f=fixture();String key=UUID.randomUUID().toString();writer.failure=new DomainException("LYRICS_RESPONSE_INVALID","结果不完整");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());assertEquals("LYRICS_RESPONSE_INVALID",composer.requestState(f.owner,f.work,key).errorCode());
        assertEquals(0,budget.allowance(f.owner,Kind.LYRICS).active());assertEquals(1,budget.allowance(f.owner,Kind.LYRICS).dailyRemaining());
        assertEquals("LYRICS_REQUEST_FAILED",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());assertEquals(1,writer.calls.get());
        writer.failure=null;composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(2,writer.calls.get());assertEquals(2,reservations());assertEquals(2,lyrics.list(f.owner,f.work).size());assertEquals(0,budget.allowance(f.owner,Kind.LYRICS).dailyRemaining());
    }
    @Test void stalePendingRequestsRecoverWithoutModelCallAndLateResultsCannotBeSaved(){
        var f=fixture();String key=UUID.randomUUID().toString();requests.claim(f.owner,f.work,key,"hash");
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",f.work,key);
        assertEquals(0,budget.allowance(f.owner,Kind.LYRICS).active());
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());assertEquals("LYRICS_REQUEST_TIMEOUT",composer.requestState(f.owner,f.work,key).errorCode());assertEquals(0,writer.calls.get());
        var saved=new java.util.concurrent.atomic.AtomicBoolean();assertThrows(DomainException.class,()->requests.completeWithRevision(f.work,key,f.revision,()->saved.set(true)));assertFalse(saved.get());
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"en");assertEquals(1,writer.calls.get());assertEquals(2,reservations());
    }
    @Test void aNewWorkCanClaimAfterAnOldWorkTimedOutUnderTheSameCreator(){
        var f=fixture();String key=UUID.randomUUID().toString();requests.claim(f.owner,f.work,key,"hash");
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=?",f.work);
        var other=fixture(f.owner);composer.compose(other.owner,other.work,0,UUID.randomUUID().toString(),"en");
        assertEquals("FAILED",requests.find(f.work,key).orElseThrow().status());assertEquals(1,writer.calls.get());
    }
    @Test void revisionSaveFailureRollsBackCompletionAndRequestStatusIsOwnerScoped(){
        var f=fixture();String key=UUID.randomUUID().toString();requests.claim(f.owner,f.work,key,"hash");
        assertThrows(IllegalStateException.class,()->requests.completeWithRevision(f.work,key,f.revision,()->{throw new IllegalStateException("database write failed");}));
        assertEquals("PENDING",composer.requestState(f.owner,f.work,key).status());
        assertThrows(DomainException.class,()->composer.requestState(identity.create().creatorId(),f.work,key));assertEquals(0,writer.calls.get());
    }
    @Test void regenerationUsesOwnedPreviousVersionAndBindsIdempotencyToInstructions(){
        var f=fixture();String key=UUID.randomUUID().toString();var generated=composer.compose(f.owner,f.work,0,key,"en","保留第二段",f.revision);
        assertEquals("保留第二段",writer.requirements);assertEquals("旧歌词\n请保留这一段",writer.previous);assertEquals(generated.id(),composer.compose(f.owner,f.work,0,key,"en","保留第二段",f.revision).id());assertEquals(1,writer.calls.get());
        assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"en","换个风格",f.revision)).code());assertEquals(1,reservations());assertEquals("旧歌词\n请保留这一段",lyrics.get(f.owner,f.work,f.revision).lyrics());
        var other=fixture();assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"en","保留",other.revision));assertEquals(1,writer.calls.get());
    }
    @Test void dailyWindowResetsAtShanghaiMidnightAndSurvivesNewAdapterInstance(){
        var f=fixture();var caps=Map.of(Kind.MUSIC,new JdbcUsageBudget.Limits(1,1,10,10));var zone=ZoneId.of("Asia/Shanghai");
        var before=new JdbcUsageBudget(jdbc,new TransactionTemplate(manager),caps,Clock.fixed(Instant.parse("2026-11-03T15:59:00Z"),ZoneOffset.UTC),zone);
        before.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC);
        var restarted=new JdbcUsageBudget(jdbc,new TransactionTemplate(manager),caps,Clock.fixed(Instant.parse("2026-11-03T15:59:30Z"),ZoneOffset.UTC),zone);
        assertEquals(0,restarted.allowance(f.owner,Kind.MUSIC).dailyRemaining());assertEquals(Instant.parse("2026-11-03T16:00:00Z"),restarted.allowance(f.owner,Kind.MUSIC).resetsAt());
        var after=new JdbcUsageBudget(jdbc,new TransactionTemplate(manager),caps,Clock.fixed(Instant.parse("2026-11-03T16:01:00Z"),ZoneOffset.UTC),zone);
        after.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC);assertEquals(2,reservations());
    }
    @Test void unlimitedDailyQuotaAllowsMoreThanTwentyAndStillRecordsEveryAdmission(){
        var f=fixture();var unlimited=configured(new JdbcUsageBudget.Limits(-1,-1,1,3));
        for(int i=0;i<25;i++)unlimited.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC);
        var allowance=unlimited.allowance(f.owner,Kind.MUSIC);
        assertEquals(25,reservations());assertTrue(allowance.dailyUnlimited());assertEquals(-1,allowance.dailyRemaining());assertEquals(-1,allowance.dailyLimit());assertFalse(allowance.concurrentUnlimited());
    }
    @Test void unlimitedDailyQuotaKeepsConcurrencyProtection(){
        var f=fixture();submit(f);var unlimited=configured(new JdbcUsageBudget.Limits(-1,-1,1,3));
        assertEquals("PERSONAL_GENERATION_BUSY",assertThrows(DomainException.class,()->unlimited.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC)).code());assertEquals(1,reservations());
    }
    @Test void personalAndGlobalDailyLimitsCanBeConfiguredIndependently(){
        var f=fixture();var global=configured(new JdbcUsageBudget.Limits(-1,1,-1,-1));
        global.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC);
        var other=fixture();assertEquals("GLOBAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->global.reserve(other.owner,other.work,UUID.randomUUID().toString(),Kind.MUSIC)).code());
        var personal=configured(new JdbcUsageBudget.Limits(1,-1,-1,-1));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->personal.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC)).code());
        personal.reserve(other.owner,other.work,UUID.randomUUID().toString(),Kind.MUSIC);assertEquals(2,reservations());
    }
    @Test void zeroStillDisablesNewRequestsAndOtherNegativeLimitsAreInvalid(){
        var f=fixture();var disabled=configured(new JdbcUsageBudget.Limits(0,-1,1,3));
        assertEquals("PERSONAL_DAILY_QUOTA_EXCEEDED",assertThrows(DomainException.class,()->disabled.reserve(f.owner,f.work,UUID.randomUUID().toString(),Kind.MUSIC)).code());assertEquals(0,reservations());
        assertThrows(IllegalArgumentException.class,()->new JdbcUsageBudget.Limits(-2,-1,1,3));
    }
    private JdbcUsageBudget configured(JdbcUsageBudget.Limits limits){return new JdbcUsageBudget(jdbc,new TransactionTemplate(manager),Map.of(Kind.MUSIC,limits),Clock.systemUTC(),ZoneId.of("Asia/Shanghai"));}
    @Test void httpQuotaIsPrivateAndRejectedGenerationReturns429WithoutTask()throws Exception{
        var json=new tools.jackson.databind.ObjectMapper();var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        String base="http://127.0.0.1:"+port;
        var csrfResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());String csrf=json.readTree(csrfResponse.body()).path("token").asText();
        var session=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/session")).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        var f=fixture(json.readTree(session.body()).path("creatorId").asText());for(int n=0;n<2;n++)jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",submit(f));
        String body=json.writeValueAsString(Map.of("revisionId",f.revision,"requestKey",UUID.randomUUID().toString()));
        var denied=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/works/"+f.work+"/generations")).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(429,denied.statusCode());assertTrue(denied.body().contains("PERSONAL_DAILY_QUOTA_EXCEEDED"));assertEquals(2,reservations());assertEquals(2,tasks.list(f.owner,f.work).size());
        var usage=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/usage")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,usage.statusCode());assertEquals(0,json.readTree(usage.body()).path("music").path("dailyRemaining").asInt());assertFalse(usage.body().contains("owner"));
        var anonymous=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/usage")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(401,anonymous.statusCode());
    }
}
