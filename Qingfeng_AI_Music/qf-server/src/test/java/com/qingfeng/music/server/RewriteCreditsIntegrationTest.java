package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.application.InspirationRewriter;
import com.qingfeng.music.lyrics.application.InspirationRewriteService;
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

/**
 * AI 改写的计费：每日免费额度内不扣积分，超额后每次扣 1 积分，失败退还。
 * 使用真实数据库与真实积分账本，不使用 mock 替代验收。
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:rewrite_credits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=true","qingfeng.credits.rewrite-cost=1","qingfeng.credits.rewrite-free-per-day=2",
    "qingfeng.inspiration.rewrite.enabled=true"})
@ActiveProfiles("test") @Import(RewriteCreditsIntegrationTest.Offline.class)
class RewriteCreditsIntegrationTest {
    static final long FREE_PER_DAY = 2L;
    static final long REWRITE_COST = 1L;
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary InspirationRewriter offlineRewriter(){return new RecordingWriter();}
    }
    static class RecordingWriter implements InspirationRewriter {
        final AtomicInteger calls=new AtomicInteger();
        volatile RuntimeException failure;
        public boolean isConfigured(){return true;}
        public String rewrite(String text,String language){calls.incrementAndGet();if(failure!=null)throw failure;return "改写后的创作描述";}
    }
    @Autowired IdentityApi identity; @Autowired CreditsLedger credits; @Autowired JdbcTemplate jdbc;
    @Autowired InspirationRewriteService service; @Autowired RewriteCharges charges; @Autowired RecordingWriter writer;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @BeforeEach void reset(){
        jdbc.update("DELETE FROM rewrite_consumption");
        jdbc.update("DELETE FROM daily_quota");
        writer.calls.set(0); writer.failure=null;
    }
    String owner(){return identity.create().creatorId();}
    String key(){return UUID.randomUUID().toString();}
    String rewrite(String owner,String key){return service.rewrite(owner,"我想感谢爸爸","zh-CN",key);}

    @Test void freeAllowanceIsUsedWithoutChargingCredits(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        rewrite(owner,key());
        rewrite(owner,key());
        assertEquals(100,credits.balance(owner));
        assertEquals(2,writer.calls.get());
        assertEquals(0L,charges.freeRemaining(owner));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=? AND charged=0",Integer.class,owner));
        assertEquals(0L,jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM rewrite_consumption WHERE creator_id=?",Long.class,owner));
    }

    @Test void exceedingFreeAllowanceChargesOneCreditPerRewrite(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        rewrite(owner,key());
        rewrite(owner,key());
        rewrite(owner,key());
        assertEquals(100-REWRITE_COST,credits.balance(owner));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=? AND charged=1",Integer.class,owner));
        assertEquals(REWRITE_COST,jdbc.queryForObject("SELECT amount FROM rewrite_consumption WHERE creator_id=? AND charged=1",Long.class,owner));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND charged=1",String.class,owner));
    }

    @Test void insufficientCreditsAreRejectedBeforeTheModelRuns(){
        String owner=owner();
        rewrite(owner,key());
        rewrite(owner,key());
        assertEquals(0,credits.balance(owner));
        var error=assertThrows(DomainException.class,()->rewrite(owner,key()));
        assertEquals("INSUFFICIENT_CREDITS",error.code());
        assertEquals(2,writer.calls.get());
        assertEquals(0,credits.balance(owner));
    }

    @Test void failedRewriteRefundsTheChargedCredit(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        rewrite(owner,key());
        rewrite(owner,key());
        writer.failure=new DomainException("REWRITE_PROVIDER_UNAVAILABLE","AI改写服务暂时繁忙，请稍后再试。");
        assertThrows(DomainException.class,()->rewrite(owner,key()));
        assertEquals(100,credits.balance(owner));
        assertEquals(1,jdbc.queryForObject("SELECT refunded FROM rewrite_consumption WHERE creator_id=? AND charged=1",Integer.class,owner));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND charged=1",String.class,owner));
    }

    @Test void freeAllowanceIsNotRefundedOnFailureButCreditsAreNeverCharged(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        writer.failure=new DomainException("REWRITE_PROVIDER_UNAVAILABLE","AI改写服务暂时繁忙，请稍后再试。");
        assertThrows(DomainException.class,()->rewrite(owner,key()));
        assertEquals(100,credits.balance(owner));
        assertEquals(1,charges.freeRemaining(owner));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=?",String.class,owner));
    }

    @Test void sameRequestKeyDoesNotChargeTwiceOrCallTheModelTwice(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        rewrite(owner,key());
        rewrite(owner,key());
        String key=key();
        String first=rewrite(owner,key);
        String second=rewrite(owner,key);
        assertEquals(first,second);
        // 两次免费 + 一次超额扣费；重放同一请求键不会再扣一次，也不会再调用模型。
        assertEquals(100-REWRITE_COST,credits.balance(owner));
        assertEquals(3,writer.calls.get());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM rewrite_consumption WHERE creator_id=?",Integer.class,owner));
    }

    @Test void failedRequestCanBeRetriedWithANewKey(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        writer.failure=new DomainException("REWRITE_PROVIDER_UNAVAILABLE","AI改写服务暂时繁忙，请稍后再试。");
        String failed=key();
        assertThrows(DomainException.class,()->rewrite(owner,failed));
        // 同一个键不会再次调用模型。
        assertEquals("REWRITE_REQUEST_FAILED",assertThrows(DomainException.class,()->rewrite(owner,failed)).code());
        assertEquals(1,writer.calls.get());
        writer.failure=null;
        assertEquals("改写后的创作描述",rewrite(owner,key()));
        assertEquals(2,writer.calls.get());
        assertEquals(100,credits.balance(owner));
    }

    @Test void freeAllowanceIsScopedToTheCurrentDay(){
        String owner=owner();
        jdbc.update("INSERT INTO daily_quota(creator_id,bucket,usage_day,used_count,updated_at) VALUES(?,'REWRITE',DATEADD(DAY,-1,CURRENT_DATE),9,CURRENT_TIMESTAMP)",owner);
        assertEquals(FREE_PER_DAY,charges.freeRemaining(owner));
    }

    /** 跨实例并发上限：在途请求数达到上限时直接拒绝，不再调用模型。 */
    @Test void concurrentLimitIsEnforcedAcrossInstances(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        // 模拟另一个实例留下的在途记录。
        jdbc.update("INSERT INTO rewrite_consumption(id,creator_id,request_key,amount,charged,status,refunded,created_at,updated_at) VALUES(?,?,?,0,0,'RESERVED',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            java.util.UUID.randomUUID().toString(),owner,key());
        var error=assertThrows(DomainException.class,()->rewrite(owner,key()));
        assertEquals("REWRITE_GENERATION_BUSY",error.code());
        assertEquals(0,writer.calls.get());
    }

    /** 进程崩溃留下的在途记录会被回收：按失败处理并退还已扣积分，不永久占用并发额度。 */
    @Test void staleInflightRequestIsReapedAndRefunded(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        String stale=key();
        jdbc.update("INSERT INTO rewrite_consumption(id,creator_id,request_key,amount,charged,status,refunded,created_at,updated_at) VALUES(?,?,?,1,1,'RESERVED',0,TIMESTAMPADD(SECOND,-600,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)",
            java.util.UUID.randomUUID().toString(),owner,stale);
        jdbc.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES('DEBIT',?,?,-1,CURRENT_TIMESTAMP)","rewrite:"+owner+":"+stale,owner);
        assertEquals(99,credits.balance(owner));
        // 触发一次新的改写：回收旧记录并退款。
        rewrite(owner,key());
        assertEquals(100,credits.balance(owner));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM rewrite_consumption WHERE creator_id=? AND request_key=?",String.class,owner,stale));
    }

    @Test void rewriteConsumptionIsTraceableToTheOriginalRequest(){
        String owner=owner();
        credits.credit(owner,100,"fund-"+owner);
        rewrite(owner,key());
        rewrite(owner,key());
        String key=key();
        rewrite(owner,key);
        assertEquals(key,jdbc.queryForObject("SELECT request_key FROM rewrite_consumption WHERE creator_id=? AND charged=1",String.class,owner));
        assertEquals(REWRITE_COST,jdbc.queryForObject("SELECT amount FROM rewrite_consumption WHERE creator_id=? AND charged=1",Long.class,owner));
        assertEquals("rewrite:"+owner+":"+key,jdbc.queryForObject("SELECT reference_id FROM credit_ledger WHERE entry_type='DEBIT' AND owner_id=?",String.class,owner));
    }

    /** 走完整 HTTP 链路：接口扣费 → 余额变化 → app/credits 同步返回剩余免费次数。 */
    @Test void httpRewriteChargesCreditsAndTheCreditsEndpointStaysInSync() throws Exception {
        var session=sessionOwner();
        // 只给 1 积分：正好够免费额度用完后的第一次改写，第二次必须被拦截。
        credits.credit(session.owner(),1,"fund-"+session.owner());
        assertEquals(1L,rewriteCostOf(session));
        assertEquals(FREE_PER_DAY,rewriteFreeRemainingOf(session));
        // 免费额度内的两次改写不扣积分，剩余次数同步递减。
        assertEquals(200,post(session,"{\"text\":\"我想感谢爸爸\",\"language\":\"zh-CN\",\"requestKey\":\""+key()+"\"}").statusCode());
        assertEquals(200,post(session,"{\"text\":\"我想感谢爸爸\",\"language\":\"zh-CN\",\"requestKey\":\""+key()+"\"}").statusCode());
        assertEquals(1,credits.balance(session.owner()));
        assertEquals(0L,rewriteFreeRemainingOf(session));
        // 免费额度用完后开始扣积分，余额与剩余额度同时更新。
        assertEquals(200,post(session,"{\"text\":\"我想感谢爸爸\",\"language\":\"zh-CN\",\"requestKey\":\""+key()+"\"}").statusCode());
        assertEquals(0,credits.balance(session.owner()));
        assertEquals(0L,rewriteFreeRemainingOf(session));
        // 余额不足时拒绝，不再调用模型。
        int callsBefore=writer.calls.get();
        var denied=post(session,"{\"text\":\"我想感谢爸爸\",\"language\":\"zh-CN\",\"requestKey\":\""+key()+"\"}");
        assertEquals(409,denied.statusCode());
        assertTrue(denied.body().contains("INSUFFICIENT_CREDITS"),denied.body());
        assertEquals(callsBefore,writer.calls.get());
        assertEquals(0,credits.balance(session.owner()));
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
    java.net.http.HttpResponse<String> post(Session session,String body) throws Exception {
        var csrf=json().readTree(session.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(session.base()+"/api/public/csrf")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString()).body()).path("token").asText();
        return session.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(session.base()+"/api/app/inspiration/rewrite")).header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    tools.jackson.databind.JsonNode creditsView(Session session) throws Exception {
        var view=session.client().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(session.base()+"/api/app/credits")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200,view.statusCode());
        return json().readTree(view.body());
    }
    long rewriteCostOf(Session session) throws Exception { return creditsView(session).path("rewriteCost").asLong(); }
    long rewriteFreeRemainingOf(Session session) throws Exception { return creditsView(session).path("rewriteFreeRemaining").asLong(); }
    tools.jackson.databind.ObjectMapper json(){return new tools.jackson.databind.ObjectMapper();}
}
