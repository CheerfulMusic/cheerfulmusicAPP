package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/**
 * 歌词生成的积分扣费链路：成功才扣、失败必退、重复提交只扣一次、并发不超额。
 * 使用真实数据库与真实积分账本，不使用 mock 替代验收。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:lyrics_credits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.lyrics.enabled=true",
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
        @Bean @Primary LyricsWriter offlineLyricsWriter(){return new RecordingWriter();}
    }
    static class RecordingWriter implements LyricsWriter {
        final AtomicInteger calls=new AtomicInteger();
        volatile RuntimeException failure;
        volatile boolean emptyResult;
        public boolean isConfigured(){return true;}
        public Draft write(WorkApi.Work work,String language){throw new AssertionError("应携带修改上下文");}
        public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics){
            calls.incrementAndGet();
            if(failure!=null)throw failure;
            if(emptyResult)return new Draft("新的歌","   ");
            return new Draft("新的歌","[Chorus]\n愿你的每一天都有光");
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
        writer.calls.set(0); writer.failure=null; writer.emptyResult=false;
    }
    record Fixture(String owner,String work){}
    Fixture fixture(){return fixture(identity.create().creatorId());}
    Fixture fixture(String owner){
        var work=works.create(owner,new WorkApi.Draft("妈妈","生日","一起做饭","愿你快乐","民谣","故事"));
        return new Fixture(owner,work.id());
    }
    void fund(Fixture f,long amount){credits.credit(f.owner,amount,"fund-"+f.owner);}
    record Consumption(String owner,String type,String work,String requestKey,long amount,String status,int refunded,String createdAt){}
    List<Consumption> consumptions(String owner){
        return jdbc.query("SELECT owner_id,consumption_type,work_id,request_key,amount,task_status,refunded,CAST(created_at AS VARCHAR) FROM credit_consumption WHERE owner_id=? ORDER BY created_at",
            (rs,n)->new Consumption(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getInt(7),rs.getString(8)),owner);
    }

    @Test void sufficientCreditsChargeExactlyOnceOnSuccess(){
        var f=fixture();fund(f,20);
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
        var f=fixture();fund(f,LYRICS_COST-2);
        var error=assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN"));
        assertEquals("INSUFFICIENT_CREDITS",error.code());
        assertEquals(LYRICS_COST-2,credits.balance(f.owner));
        assertEquals(0,writer.calls.get());
        assertEquals(0,consumptions(f.owner).size());
        assertTrue(lyrics.list(f.owner,f.work).isEmpty());
    }

    @Test void failedGenerationRefundsTheReservedCredits(){
        var f=fixture();fund(f,20);
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","歌词编写超时，可以重新编写；已有内容仍会保留。");
        String key=UUID.randomUUID().toString();
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals(20,credits.balance(f.owner));
        var rows=consumptions(f.owner);
        assertEquals(1,rows.size());
        assertEquals("FAILED",rows.get(0).status());
        assertEquals(1,rows.get(0).refunded());
        assertEquals("LYRICS_PROVIDER_TIMEOUT",composer.requestState(f.owner,f.work,key).errorCode());
    }

    @Test void emptyLyricsResultDoesNotCharge(){
        var f=fixture();fund(f,20);
        writer.emptyResult=true;
        String key=UUID.randomUUID().toString();
        var error=assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals("LYRICS_RESPONSE_INVALID",error.code());
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).get(0).refunded());
    }

    @Test void repeatedSubmissionWithSameKeyChargesOnlyOnce(){
        var f=fixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        var first=composer.compose(f.owner,f.work,0,key,"zh-CN");
        var second=composer.compose(f.owner,f.work,0,key,"zh-CN");
        assertEquals(first.id(),second.id());
        assertEquals(1,writer.calls.get());
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());
    }

    @Test void refreshingAndResubmittingTheSameKeyDoesNotChargeAgain(){
        var f=fixture();fund(f,20);
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
        var f=fixture();fund(f,LYRICS_COST);
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(0,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).size());
    }

    @Test void concurrentRequestsNeverOverspend() throws Exception{
        var f=fixture();fund(f,LYRICS_COST);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            var a=pool.submit(()->attempt(f,UUID.randomUUID().toString(),start));
            var b=pool.submit(()->attempt(f,UUID.randomUUID().toString(),start));
            start.countDown();
            var outcomes=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter("ok"::equals).count());
            assertTrue(outcomes.contains("INSUFFICIENT_CREDITS"),"并发请求必须只有一次成功扣费："+outcomes);
            assertEquals(0,credits.balance(f.owner));
            assertEquals(1,consumptions(f.owner).size());
        }finally{pool.shutdownNow();}
    }

    @Test void staleRequestIsRefundedWhenRecovered(){
        var f=fixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        requests.claim(f.owner,f.work,key,"hash");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",f.work,key);
        assertEquals("FAILED",composer.requestState(f.owner,f.work,key).status());
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,consumptions(f.owner).get(0).refunded());
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

    /** 重新生成是另一次创作，用新的请求键必须再扣一次积分。 */
    @Test void regenerationWithANewKeyChargesAgain(){
        var f=fixture();fund(f,20);
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        composer.compose(f.owner,f.work,0,UUID.randomUUID().toString(),"zh-CN");
        assertEquals(20-2*LYRICS_COST,credits.balance(f.owner));
        assertEquals(2,writer.calls.get());
        var rows=consumptions(f.owner);
        assertEquals(2,rows.size());
        assertEquals("SUCCEEDED",rows.get(0).status());
        assertEquals("SUCCEEDED",rows.get(1).status());
        assertEquals(0,rows.get(1).refunded());
        assertNotEquals(rows.get(0).requestKey(),rows.get(1).requestKey());
    }

    /** 退款幂等且可追溯到原始消费：同一请求键只会有一条扣费与一条退款。 */
    @Test void refundIsIdempotentAndTraceableToTheOriginalConsumption(){
        var f=fixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","歌词编写超时，可以重新编写；已有内容仍会保留。");
        assertThrows(DomainException.class,()->composer.compose(f.owner,f.work,0,key,"zh-CN"));
        assertEquals(20,credits.balance(f.owner));
        // 重复结算失败与超时清扫都不得二次退款。
        requests.failed(f.work,key,"LYRICS_PROVIDER_TIMEOUT");
        requests.failed(f.work,key,"LYRICS_PROVIDER_TIMEOUT");
        assertEquals(20,credits.balance(f.owner));
        String reference="lyrics:"+f.work+":"+key;
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?",Integer.class,reference));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,reference));
        // 退款记录与原始消费用同一个引用键，可追溯到具体任务。
        assertEquals(f.work,jdbc.queryForObject("SELECT work_id FROM credit_consumption WHERE consumption_type='LYRICS_GENERATION' AND request_key=?",String.class,key));
        assertEquals(1,jdbc.queryForObject("SELECT refunded FROM credit_consumption WHERE consumption_type='LYRICS_GENERATION' AND request_key=?",Integer.class,key));
    }

    /** 超时清扫重复触发也只退一次。 */
    @Test void staleSweepRefundsOnlyOnce(){
        var f=fixture();fund(f,20);
        String key=UUID.randomUUID().toString();
        requests.claim(f.owner,f.work,key,"hash");
        assertEquals(20-LYRICS_COST,credits.balance(f.owner));
        jdbc.update("UPDATE lyrics_composition_request SET created_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE work_id=? AND request_key=?",f.work,key);
        composer.requestState(f.owner,f.work,key);
        composer.requestState(f.owner,f.work,key);
        composer.requestState(f.owner,f.work,key);
        assertEquals(20,credits.balance(f.owner));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id=?",Integer.class,"lyrics:"+f.work+":"+key));
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
        var f=fixture(session.owner());
        fund(f,20);
        writer.failure=new DomainException("LYRICS_PROVIDER_TIMEOUT","歌词编写超时，可以重新编写；已有内容仍会保留。");
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
