package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.infrastructure.provider.QwenLyricsWriter;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 歌词扣费：确定失败才退，未知结果保留预扣；重复提交只扣一次，并发不超额。
 * 本类是 H2 应用回归；真实 MySQL 验收由独立集成测试负责。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:lyrics_credits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.providers.openai.api-key=",
    "qingfeng.providers.qwen.api-key=offline-unused-key",
    "qingfeng.providers.qwen.base-url=http://127.0.0.1:1/v1",
    "qingfeng.credits.enabled=true",
    "qingfeng.credits.lyrics-cost=5",
    "qingfeng.credits.music-cost=15",
    "qingfeng.usage.lyrics.daily-per-creator=-1",
    "qingfeng.usage.lyrics.daily-global=1000",
    "qingfeng.usage.lyrics.concurrent-per-creator=5",
    "qingfeng.usage.lyrics.concurrent-global=1000"})
@ActiveProfiles("test") @Import(LyricsCreditsIntegrationTest.Offline.class)
class LyricsCreditsIntegrationTest {
    static final long LYRICS_COST = 5L;
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary @org.springframework.beans.factory.annotation.Qualifier("customLyricsWriter")
        LyricsWriter offlineLyricsWriter(){return new RecordingWriter();}
    }
    static class RecordingWriter implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();
        volatile RuntimeException failure;
        volatile boolean emptyResult;
        volatile LyricsWriter delegate;
        public boolean isConfigured(){return true;}
        public Draft write(WorkApi.Work work,String language){throw new AssertionError("应携带修改上下文");}
        public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics){
            calls.incrementAndGet();
            if(delegate!=null)return delegate.write(work,language,instructions,previousLyrics);
            if(failure!=null)throw failure;
            if(emptyResult)return new Draft("新的歌","   ");
            return new Draft("新的歌","[Chorus]\n愿你的每一天都有光");
        }
        @Override public Map<Integer, String> repairSections(SectionRepair request) {
            return delegate.repairSections(request);
        }
    }
    @Autowired IdentityApi identity; @Autowired WorkApi works; @Autowired LyricsApi lyrics;
    @Autowired LyricsCompositionService composer; @Autowired CompositionRequests requests;
    @Autowired CreditsLedger credits; @Autowired JdbcTemplate jdbc;
    @Autowired RecordingWriter writer;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @BeforeEach void reset(){
        jdbc.update("DELETE FROM model_usage_reservation");
        jdbc.update("UPDATE lyrics_composition_request SET status='SUCCEEDED'");
        writer.calls.set(0); writer.failure=null; writer.emptyResult=false;writer.delegate=null;
    }
    record Fixture(String owner,String work){}
    Fixture fixture(){return fixture(identity.create().creatorId());}
    Fixture fixture(String owner){
        var work=works.create(owner,new WorkApi.Draft("妈妈","生日","一起做饭","愿你快乐","民谣","故事"));
        return new Fixture(owner,work.id());
    }
    Fixture chargedFixture(){return chargedFixture(identity.create().creatorId());}
    Fixture chargedFixture(String owner){
        var f=fixture(owner);
        // Existing charged-path tests start after the initial free grant has been used.
        jdbc.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES('LYRIC_GRANT',?, '00000000-0000-0000-0000-000000000000',?,CURRENT_DATE,CURRENT_TIMESTAMP)",f.work,f.owner);
        jdbc.update("INSERT INTO model_usage_reservation(kind,work_id,request_key,owner_id,usage_day,created_at) VALUES('LYRIC_REDEEM',?,?,?,CURRENT_DATE,CURRENT_TIMESTAMP)",f.work,UUID.randomUUID().toString(),f.owner);
        return f;
    }
    void fund(Fixture f,long amount){credits.credit(f.owner,amount,"fund-"+f.owner);}
    record Consumption(String owner,String type,String work,String requestKey,long amount,String status,int refunded,String createdAt){}
    List<Consumption> consumptions(String owner){
        return jdbc.query("SELECT owner_id,consumption_type,work_id,request_key,amount,task_status,refunded,CAST(created_at AS VARCHAR) FROM credit_consumption WHERE owner_id=? ORDER BY created_at",
            (rs,n)->new Consumption(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getInt(7),rs.getString(8)),owner);
    }

    @Test void firstLyricForAWorkIsFreeWithoutAStoredBalance(){
        var f=fixture();String key=UUID.randomUUID().toString();
        var revision=composer.compose(f.owner,f.work,0,key,"zh-CN");
        assertNotNull(revision.id());
        assertEquals(0,credits.balance(f.owner));
        assertTrue(consumptions(f.owner).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_GRANT' AND work_id=?",Integer.class,f.work));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_REDEEM' AND work_id=? AND request_key=?",Integer.class,f.work,key));
    }

    @Test void definiteFailureRestoresTheFirstFreeLyricUse(){
        var f=fixture();String failedKey=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_RESPONSE_INVALID","模型返回无效结果");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,failedKey,"zh-CN"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_REDEEM' AND work_id=?",Integer.class,f.work));
        writer.failure=null;
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(0,credits.balance(f.owner));
        assertTrue(consumptions(f.owner).isEmpty());
    }

    @Test void unknownFirstLyricOutcomeKeepsTheFreeUseReserved(){
        var f=fixture();String key=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","供应商结果尚未确认");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,key).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_REDEEM' AND work_id=?",Integer.class,f.work));
        assertEquals(0,credits.balance(f.owner));
        assertEquals("INSUFFICIENT_CREDITS",assertThrows(DomainException.class,
            ()->composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN")).code());
        assertEquals(1,writer.calls.get());
    }

    @Test void sufficientCreditsChargeExactlyOnceOnSuccess(){
        var f=chargedFixture();fund(f,20);
        var revision=composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertNotNull(revision.id());
        assertEquals(1,writer.calls.get());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        var rows=consumptions(f.owner);
        assertEquals(1,rows.size());
        var row=rows.get(0);
        assertEquals(f.owner,row.owner());
        assertEquals("LYRICS_GENERATION",row.type());
        assertEquals(f.work,row.work());
        assertEquals(LYRICS_COST,row.amount());
        assertEquals("SUCCEEDED",row.status());
        assertEquals(0,row.refunded());
        assertNotNull(row.createdAt());
        assertEquals(1,lyrics.list(f.owner,f.work).size());
    }

    @Test void insufficientCreditsRejectBeforeCallingTheModel(){
        var f=chargedFixture();fund(f,LYRICS_COST-2);
        var error=assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN"));
        assertEquals("INSUFFICIENT_CREDITS",error.code());
        assertEquals(LYRICS_COST-2,credits.balance(f.owner));
        assertEquals(0,writer.calls.get());
        assertEquals(0,consumptions(f.owner).size());
        assertTrue(lyrics.list(f.owner,f.work).isEmpty());
    }

    @Test void failedGenerationRefundsTheReservedCredits(){
        var f=chargedFixture();fund(f,20);
        writer.failure=new DomainException("LYRICS_RESPONSE_INVALID","模型已返回无效结果，本次生成失败。");
        String key=UUID.randomUUID().toString();
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals(20,credits.balance(f.owner));
        var rows=consumptions(f.owner);
        assertEquals(1,rows.size());
        assertEquals("FAILED",rows.get(0).status());
        assertEquals(1,rows.get(0).refunded());
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());
        assertEquals("LYRICS_RESPONSE_INVALID",composer.requestState(f.owner,f.work,key).errorCode());
    }

    @ParameterizedTest @ValueSource(strings={"LYRICS_QUALITY_NOT_MET","LYRICS_QUALITY_UNVERIFIED"})
    void undeliveredQualityFailureRefundsOnceAndNeverSavesOrReplaysAnUnapprovedDraft(String code){
        var f=chargedFixture();fund(f,20);
        var previous=lyrics.save(f.owner,f.work,0,"已有手写版","[Chorus]\n保留我自己写的这一句");
        String key=UUID.randomUUID().toString();
        writer.failure=new DomainException(code,"本次歌词未通过交付检查");
        assertEquals(code,assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());
        assertEquals(code,composer.requestState(f.owner,f.work,key).errorCode());
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,writer.calls.get());
        assertEquals(List.of(previous.id()),lyrics.list(f.owner,f.work).stream().map(LyricsApi.Revision::id).toList());
        assertEquals(previous.lyrics(),lyrics.get(f.owner,f.work,previous.id()).lyrics());
        assertEquals(1,consumptions(f.owner).size());
        assertEquals("FAILED",consumptions(f.owner).get(0).status());
        assertEquals(1,consumptions(f.owner).get(0).refunded());
        // Replayed settlement and the original key may neither refund again nor call another model.
        requests.failed(f.work,key,code);requests.failed(f.work,key,code);
        assertEquals("LYRICS_REQUEST_FAILED",assertThrows(DomainException.class,
            ()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        assertEquals(1,writer.calls.get());
        assertEquals(20,credits.balance(f.owner));
        String reference="lyrics:"+f.work+":"+key;
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?",Integer.class,reference));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,reference));
    }

    @ParameterizedTest @ValueSource(strings={"LYRICS_QUALITY_NOT_MET","LYRICS_QUALITY_UNVERIFIED"})
    void undeliveredQualityFailureRestoresTheFreeUseWithoutCreatingPaidCredits(String code){
        var f=fixture();String failedKey=UUID.randomUUID().toString();
        writer.failure=new DomainException(code,"本次歌词未通过交付检查");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,failedKey,"zh-CN"));
        assertEquals("FAILED",composer.requestState(f.owner,f.work,failedKey).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='LYRIC_REDEEM' AND work_id=?",Integer.class,f.work));
        assertEquals(0,credits.balance(f.owner));
        assertTrue(consumptions(f.owner).isEmpty());
        assertTrue(lyrics.list(f.owner,f.work).isEmpty());
        writer.failure=null;
        var accepted=composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertNotNull(accepted.id());
        assertEquals(0,credits.balance(f.owner));
        assertTrue(consumptions(f.owner).isEmpty());
    }

    @Test void invalidRefinementRefundsOnceWithoutPublishingEitherDraft()throws Exception{
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);var sends=new AtomicInteger();
        try(var scope=new AgentRequestScope(java.time.Duration.ofSeconds(60),65536).enter()){
            org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                int stage=sends.incrementAndGet();assertTrue(stage<=2,"Invalid refinement must stop without another generation");
                if(stage==2)scope.cancel();return qualityStageResponse(stage);
            });
            writer.delegate=new OpenAiLyricsWriter("offline-key","gpt-6.1-sol",java.net.URI.create("https://example.invalid"),client);
            assertEquals("LYRICS_QUALITY_UNVERIFIED",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        }
        assertEquals(2,sends.get());assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());
        assertEquals(20,credits.balance(f.owner));assertEquals(1,consumptions(f.owner).get(0).refunded());assertTrue(lyrics.list(f.owner,f.work).isEmpty());
        requests.failed(f.work,key,"LYRICS_QUALITY_UNVERIFIED");requests.failed(f.work,key,"LYRICS_QUALITY_UNVERIFIED");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,"lyrics:"+f.work+":"+key));
        assertEquals("LYRICS_REQUEST_FAILED",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        assertEquals(2,sends.get());assertEquals(1,writer.calls.get());
    }

    @Test void dispatchedRefinementTimeoutKeepsChargeUnknownAndNeverRetriesWithANewDraft()throws Exception{
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        var client=org.mockito.Mockito.mock(java.net.http.HttpClient.class);var sends=new AtomicInteger();
        org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            int stage=sends.incrementAndGet();assertTrue(stage<=2,"A dispatched refinement timeout must not cause another model request");
            if(stage==2)throw new java.net.http.HttpTimeoutException("offline dispatched refinement timeout");return qualityStageResponse(stage);
        });
        writer.delegate=new OpenAiLyricsWriter("offline-key","gpt-6.1-sol",java.net.URI.create("https://example.invalid"),client);
        assertEquals("LYRICS_PROVIDER_TIMEOUT",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        assertEquals(2,sends.get());assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,key).status());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));assertEquals("UNKNOWN",consumptions(f.owner).get(0).status());
        assertEquals(0,consumptions(f.owner).get(0).refunded());assertTrue(lyrics.list(f.owner,f.work).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,"lyrics:"+f.work+":"+key));
        assertEquals("LYRICS_REQUEST_PENDING",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        assertEquals(2,sends.get());assertEquals(1,writer.calls.get());
    }

    @Test void missingSectionRepairPreservesGoodLyricsAndChargesOnlyOnce() throws Exception {
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        var calls=scriptSectionRepair("```json\n{\"1\":\"缺的这一段\"}\n```", false);
        var revision=composeWithSections(f,key);
        assertEquals("[Verse]\n原主歌\n[Pre-Chorus]\n缺的这一段\n[Chorus]\n原副歌",revision.lyrics());
        assertEquals(revision.id(),composeWithSections(f,key).id());
        assertEquals(revision.id(),composer.requestState(f.owner,f.work,key).revisionId());
        assertEquals(3,calls.size());assertEquals(1,lyrics.list(f.owner,f.work).size());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(List.of(key),consumptions(f.owner).stream().map(Consumption::requestKey).toList());
    }

    @Test void malformedSectionRepairFailsOnceRefundsAndNeverSavesTheFragment() throws Exception {
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        var calls=scriptSectionRepair("{\"0\":\"覆盖已经合格的歌词\",\"1\":\"缺的这一段\"}", false);
        assertEquals("LYRICS_STRUCTURE_MISMATCH",assertThrows(DomainException.class,()->composeWithSections(f,key)).code());
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());
        assertEquals("LYRICS_REQUEST_FAILED",assertThrows(DomainException.class,()->composeWithSections(f,key)).code());
        assertEquals(3,calls.size());assertTrue(lyrics.list(f.owner,f.work).isEmpty());
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());assertEquals(1,consumptions(f.owner).get(0).refunded());
    }

    @Test void timedOutSectionRepairKeepsOriginalKeyForLookupWithoutAnotherCallOrDebit() throws Exception {
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        var calls=scriptSectionRepair("", true);
        assertEquals("LYRICS_PROVIDER_TIMEOUT",assertThrows(DomainException.class,()->composeWithSections(f,key)).code());
        assertEquals(new LyricsCompositionService.RequestState("UNKNOWN",null,"LYRICS_PROVIDER_TIMEOUT"),composer.requestState(f.owner,f.work,key));
        assertEquals("LYRICS_REQUEST_PENDING",assertThrows(DomainException.class,()->composeWithSections(f,key)).code());
        assertEquals(3,calls.size());assertTrue(lyrics.list(f.owner,f.work).isEmpty());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());assertEquals("UNKNOWN",consumptions(f.owner).get(0).status());
    }

    private LyricsApi.Revision composeWithSections(Fixture fixture,String key) {
        return composer.composeCustom(fixture.owner,fixture.work,0,key,"zh-CN","沿用原有故事",null,"CUSTOM",false,
            List.of("Verse","Pre-Chorus","Chorus"));
    }

    private List<HttpRequest> scriptSectionRepair(String replacement, boolean timeout) throws Exception {
        var client=org.mockito.Mockito.mock(HttpClient.class);
        var calls=new ArrayList<HttpRequest>();
        var json=new ObjectMapper();
        String candidate="```lyrics\n## 【主歌】\n原主歌\n[Chorus]\n原副歌\n```";
        var plan=Map.of("requirements","","suggestedArrangement","Acoustic guitar","negativeTags","",
            "summaryZh","按所选风格编配","summaryEn","Selected style","vocalGender","AUTO");
        org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            HttpRequest request=call.getArgument(0);calls.add(request);
            String output=switch(calls.size()) {
                case 1 -> json.writeValueAsString(Map.of("title","一首歌","lyrics",candidate,"plan",plan));
                case 2 -> candidate;
                case 3 -> {
                    var sent=json.readTree(readRequestBody(request));
                    assertEquals("json_object",sent.path("response_format").path("type").asText());
                    var context=json.readTree(sent.path("messages").path(1).path("content").asText());
                    assertEquals(json.readTree("{\"1\":\"Pre-Chorus\"}"),context.path("missingSections"));
                    assertEquals("[Verse]\n原主歌\n[Chorus]\n原副歌",context.path("lyrics").asText());
                    if(timeout)throw new HttpTimeoutException("offline section repair timeout");
                    yield replacement;
                }
                default -> throw new AssertionError("Unexpected additional model request");
            };
            @SuppressWarnings("unchecked") HttpResponse<String> response=org.mockito.Mockito.mock(HttpResponse.class);
            org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
            org.mockito.Mockito.when(response.body()).thenReturn(json.writeValueAsString(Map.of("choices",List.of(
                Map.of("finish_reason","stop","message",Map.of("content",output))))));
            return response;
        });
        writer.delegate=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("https://example.invalid"),client);
        return calls;
    }

    private static String readRequestBody(HttpRequest request) {
        var output=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { byte[] bytes=new byte[item.remaining()];item.get(bytes);output.writeBytes(bytes); }
            public void onError(Throwable failure) { throw new AssertionError(failure); }
            public void onComplete() {}
        });
        return output.toString(StandardCharsets.UTF_8);
    }

    private static java.net.http.HttpResponse<String> qualityStageResponse(int stage){
        var json=new tools.jackson.databind.ObjectMapper();
        String oldLyrics="[Verse 1]\n一起做饭的晚上\n[Chorus]\n愿你的每一天都有光";
        String newLyrics="[Verse 1]\n两双筷子碰着锅沿\n[Chorus]\n留一口热的等你回来";
        var candidate=new java.util.LinkedHashMap<String,Object>(java.util.Map.of("title","故事","lyrics",stage==1?oldLyrics:newLyrics,
            "plan",java.util.Map.of("requirements","","suggestedArrangement","","negativeTags","","summaryZh","","summaryEn","","vocalGender","AUTO")));
        if(stage==2)candidate.put("quality",java.util.Map.of("decision","REWRITE","issues",List.of(java.util.Map.of("code","HOOK","quote","愿你的每一天都有光","reason","原副歌没有具体表达")),
            "hookQuote","留一口热的等你回来","progressionQuote","两双筷子碰着锅沿","rhythmQuote","留一口热的等你回来","reason","根据做饭素材具体化表达"));
        String body=json.writeValueAsString(java.util.Map.of("status","completed","output",List.of(java.util.Map.of("type","message","role","assistant","status","completed","content",List.of(java.util.Map.of("type","output_text","text",json.writeValueAsString(candidate)))))));
        @SuppressWarnings("unchecked") java.net.http.HttpResponse<String> response=org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(response.statusCode()).thenReturn(200);org.mockito.Mockito.when(response.body()).thenReturn(body);return response;
    }

    @Test void providerTimeoutRetainsChargeAndCanRecoverTheSameKey(){
        var f=chargedFixture();fund(f,20);String key=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","供应商结果尚未确认");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,key).status());
        assertEquals("UNKNOWN",consumptions(f.owner).get(0).status());
        assertEquals(0,consumptions(f.owner).get(0).refunded());
        assertEquals("LYRICS_REQUEST_PENDING",assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN")).code());
        requests.failed(f.work,key,"LYRICS_PROVIDER_TIMEOUT");
        assertEquals(1,writer.calls.get());assertEquals(1,consumptions(f.owner).size());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,"lyrics:"+f.work+":"+key));
        // A verified late result settles the original reservation; it never charges again.
        var revision=lyrics.save(f.owner,f.work,0,"晚到的歌词","[Chorus]\n愿你的每一天都有光");
        requests.complete(f.work,key,revision.id());
        assertEquals("SUCCEEDED",composer.requestState(f.owner,f.work,key).status());
        assertEquals(revision.id(),composer.compose(f.owner,f.work,0,key,"zh-CN").id());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));assertEquals(0,consumptions(f.owner).get(0).refunded());
    }

    @Test void paidRetryAfterTimeoutChargesOnlyItsNewRequestAndPreservesTheOldCharge(){
        var f=chargedFixture();
        fund(f,30);
        String oldKey=UUID.randomUUID().toString(), retryKey=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","offline timeout");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,oldKey,"zh-CN"));
        assertEquals(30-LYRICS_COST,credits.balance(f.owner));

        writer.failure=null;
        var revision=composer.compose(f.owner,f.work,0,retryKey,"zh-CN");
        assertEquals(revision.id(),composer.compose(f.owner,f.work,0,retryKey,"zh-CN").id());
        assertEquals(2,writer.calls.get());
        assertEquals(30-2*LYRICS_COST,credits.balance(f.owner));
        assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,oldKey).status());
        assertEquals("LYRICS_REQUEST_SUPERSEDED",composer.requestState(f.owner,f.work,oldKey).errorCode());
        var entries=consumptions(f.owner);
        assertEquals(2,entries.size());
        assertTrue(entries.stream().allMatch(entry->entry.refunded()==0));
    }

    @Test void emptyLyricsResultDoesNotCharge(){
        var f=chargedFixture();fund(f,20);
        writer.emptyResult=true;
        String key=UUID.randomUUID().toString();
        var error=assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals("LYRICS_RESPONSE_INVALID",error.code());
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).get(0).refunded());
    }

    @Test void repeatedSubmissionWithSameKeyChargesOnlyOnce(){
        var f=chargedFixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        var first=composer.compose(f.owner,f.work,0,key,"zh-CN");
        var second=composer.compose(f.owner,f.work,0,key,"zh-CN");
        assertEquals(first.id(),second.id());
        assertEquals(1,writer.calls.get());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());
    }

    @Test void refreshingAndResubmittingTheSameKeyDoesNotChargeAgain(){
        var f=chargedFixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        var first=composer.compose(f.owner,f.work,0,key,"zh-CN");
        var state=composer.requestState(f.owner,f.work,key);
        assertEquals("SUCCEEDED",state.status());
        assertEquals(first.id(),state.revisionId());
        var again=composer.compose(f.owner,f.work,0,key,"zh-CN");
        assertEquals(first.id(),again.id());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());
    }

    @Test void exactBalanceStillGenerates(){
        var f=chargedFixture();fund(f,LYRICS_COST);
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(0,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());
    }

    @Test void concurrentRequestsNeverOverspend() throws Exception{
        var f=chargedFixture();fund(f,LYRICS_COST);
        var otherWork=chargedFixture(f.owner);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var a=pool.submit(()->attempt(f,UUID.randomUUID().toString(),start));
            var b=pool.submit(()->attempt(otherWork,UUID.randomUUID().toString(),start));
            start.countDown();
            var outcomes=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("ok"::equals).count());
            assertTrue(outcomes.contains("INSUFFICIENT_CREDITS"),"并发请求必须只有一次成功扣费："+outcomes);
            assertEquals(0,credits.balance(f.owner));
            assertEquals(1,consumptions(f.owner).size());
        }finally{pool.shutdownNow();}
    }

    @Test void staleRequestIsUnknownWithoutAProviderFailureProof(){
        var f=chargedFixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        requests.claim(f.owner,f.work,key,"hash");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",f.work,key);
        assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,key).status());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(0,consumptions(f.owner).get(0).refunded());
    }

    @Test void creditsEndpointMatchesRealBalanceAndPublishedPrice() throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        String base="http://127.0.0.1:"+port;
        var csrfResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        String csrf=json.readTree(csrfResponse.body()).path("token").asText();
        var session=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/session")).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        String owner=json.readTree(session.body()).path("creatorId").asText();
        credits.credit(owner,12,"fund-"+owner);
        var view=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/credits")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,view.statusCode());
        var body=json.readTree(view.body());
        assertEquals(credits.balance(owner),body.path("balance").asLong());
        assertEquals(LYRICS_COST,body.path("lyricsCost").asLong());
        assertEquals(15,body.path("musicCost").asLong());
        assertTrue(body.path("enabled").asBoolean());
        // 改写价格与每日免费次数同样由后端下发，前端不硬编码。
        assertEquals(1,body.path("rewriteCost").asInt());
        assertEquals(5,body.path("rewriteFreeRemaining").asLong());
    }

    String attempt(Fixture f,String key,CountDownLatch start) throws InterruptedException{
        try{
            start.await();
            composer.compose(f.owner,f.work,0,key,"zh-CN");
            return "ok";
        }catch(DomainException e){
            return e.code();
        }catch(Exception e){
            return "unexpected:"+e.getClass().getSimpleName();
        }
    }

    /** 首次免费，后续在音乐生成刷新权益前按配置价格扣费。 */
    @Test void regenerationWithANewKeyChargesAgain(){
        var f=fixture();fund(f,20);
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(20,credits.balance(f.owner));
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(2,writer.calls.get());
        var rows=consumptions(f.owner);
        assertEquals(1,rows.size());
        assertEquals("SUCCEEDED",rows.get(0).status());
        assertEquals(0,rows.get(0).refunded());
    }

    /** 退款幂等且可追溯到原始消费：同一请求键只会有一条扣费与一条退款。 */
    @Test void refundIsIdempotentAndTraceableToTheOriginalConsumption(){
        var f=chargedFixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_RESPONSE_INVALID","模型已返回无效结果，本次生成失败。");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals(20,credits.balance(f.owner));
        // 重复结算失败与超时清扫都不得二次退款。
        requests.failed(f.work,key,"LYRICS_RESPONSE_INVALID");
        requests.failed(f.work,key,"LYRICS_RESPONSE_INVALID");
        assertEquals(20,credits.balance(f.owner));
        String reference="lyrics:"+f.work+":"+key;
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?",Integer.class,reference));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,reference));
        // 退款记录与原始消费用同一个引用键，可追溯到具体任务。
        assertEquals(f.work,jdbc.queryForObject("SELECT work_id FROM credit_consumption WHERE consumption_type='LYRICS_GENERATION' AND request_key=?",String.class,key));
        assertEquals(1,jdbc.queryForObject("SELECT refunded FROM credit_consumption WHERE consumption_type='LYRICS_GENERATION' AND request_key=?",Integer.class,key));
    }

    /** 重复超时清扫不能将未知结果伪装为确定失败。 */
    @Test void staleSweepNeverRefundsAnUnconfirmedSubmission(){
        var f=chargedFixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        requests.claim(f.owner,f.work,key,"hash");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",f.work,key);
        composer.requestState(f.owner,f.work,key);
        composer.requestState(f.owner,f.work,key);
        composer.requestState(f.owner,f.work,key);
        assertEquals("UNKNOWN",composer.requestState(f.owner,f.work,key).status());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,"lyrics:"+f.work+":"+key));
    }

    /** 充值到账后余额接口立刻反映新余额；同一订单重复入账只加一次。 */
    @Test void rechargedCreditsAreVisibleThroughTheCreditsEndpoint() throws Exception {
        var session=sessionOwner();
        String orderId=UUID.randomUUID().toString();
        // 与 PayPalPaymentService.settle 相同的入账方式：参考键为 payment:{订单号}。
        credits.credit(session.owner,100,"payment:"+orderId);
        credits.credit(session.owner,100,"payment:"+orderId);
        assertEquals(100,credits.balance(session.owner));
        long balance=creditsEndpointBalance(session);
        assertEquals(100,balance);
    }

    /** 退款到账后余额接口立刻反映恢复后的余额。 */
    @Test void refundedBalanceIsVisibleThroughTheCreditsEndpoint() throws Exception {
        var session=sessionOwner();
        var f=chargedFixture(session.owner());
        fund(f,20);
        writer.failure=new DomainException("LYRICS_RESPONSE_INVALID","模型已返回无效结果，本次生成失败。");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN"));
        assertEquals(20,credits.balance(f.owner));
        assertEquals(20,creditsEndpointBalance(session));
    }

    record Session(String owner,java.net.http.HttpClient client,String base){}
    Session sessionOwner() throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        String base="http://127.0.0.1:"+port;
        var csrfResponse=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        String csrf=json.readTree(csrfResponse.body()).path("token").asText();
        var session=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/public/session")).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        return new Session(json.readTree(session.body()).path("creatorId").asText(),client,base);
    }
    long creditsEndpointBalance(Session session) throws Exception {
        var view=session.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(session.base()+"/api/app/credits")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,view.statusCode());
        return new tools.jackson.databind.ObjectMapper().readTree(view.body()).path("balance").asLong();
    }
}
