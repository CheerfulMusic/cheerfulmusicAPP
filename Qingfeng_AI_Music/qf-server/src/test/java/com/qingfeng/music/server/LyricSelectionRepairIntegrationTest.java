package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.RewriteCharges;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.provider.QwenInspirationRewriter;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic works and the real reservation/ledger; no production datasource or external model calls. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:lyric_selection_repair;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=true", "qingfeng.credits.rewrite-cost=2", "qingfeng.credits.rewrite-free-per-day=1"
})
@ActiveProfiles("test")
class LyricSelectionRepairIntegrationTest {
    static final String SOURCE = "[Verse 1]\n原句甲\n  原样保留  \n\n[Chorus]\n原句乙\n原句丙\n";
    static final String EXPECTED = "[Verse 1]\n合格的新句甲\n  原样保留  \n\n[Chorus]\n补写的新句乙\n补写的新句丙\n";
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired CreditsLedger credits;
    @Autowired RewriteCharges charges;
    @Autowired JdbcTemplate jdbc;

    record Fixture(String owner, String work, long balance, String originalRevision) {}
    Fixture fixture(boolean paid) {
        String owner = identity.create().creatorId();
        credits.credit(owner, 100, "repair-fund-"+owner);
        var work = works.create(owner, new WorkApi.Draft("", "", "独立验证歌词改写", "", "民谣", ""));
        var original = lyrics.save(owner, work.id(), 0, "原稿", SOURCE);
        if (paid) new InspirationRewriteService(new Script((p,t)->List.of()), true, 1, charges)
            .rewrite(owner, "使用一次免费改写", "zh-CN", UUID.randomUUID().toString());
        return new Fixture(owner, work.id(), credits.balance(owner), original.id());
    }
    static class Script implements InspirationRewriter {
        final AtomicInteger calls = new AtomicInteger();
        final BiFunction<String,List<Integer>,List<Replacement>> response;
        Script(BiFunction<String,List<Integer>,List<Replacement>> response) { this.response=response; }
        public boolean isConfigured() { return true; }
        public String rewrite(String text, String language) { return "已完成的免费改写"; }
        public List<Replacement> rewriteSelection(String text, String language, List<Integer> targets) {
            calls.incrementAndGet(); return response.apply(text,targets);
        }
    }
    LyricSelectionRewriteService service(InspirationRewriter writer) {
        return new LyricSelectionRewriteService(works, new InspirationRewriteService(writer,true,1,charges));
    }
    String preview(LyricSelectionRewriteService service, Fixture f, UUID key) {
        return service.preview(f.owner(), f.work(), 0, key, SOURCE, List.of(2), "zh-CN");
    }
    static InspirationRewriter.Replacement row(int index, String text) { return new InspirationRewriter.Replacement(index,text); }
    static List<InspirationRewriter.Replacement> complete() {
        return List.of(row(1,"合格的新句甲"), row(5,"补写的新句乙"), row(6,"补写的新句丙"));
    }
    int count(Fixture f, String table, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+condition+" AND "+
            (table.equals("credit_ledger")?"owner_id":"creator_id")+"=?", Integer.class, f.owner());
    }
    void originalUnchanged(Fixture f) {
        assertEquals(1, lyrics.list(f.owner(),f.work()).size());
        assertEquals(SOURCE,lyrics.get(f.owner(),f.work(),f.originalRevision()).lyrics());
    }

    @Test void onlyBrokenLinesAreRepairedAndBothCallsChargeOnceWithReplay() {
        var f=fixture(true); var requests=new ArrayList<List<Integer>>();
        var writer=new Script((prompt,targets)->{
            requests.add(targets);
            if(requests.size()==1) return List.of(row(1,"合格的新句甲"),row(5,"原句乙"));
            assertEquals(List.of(5,6),targets);
            assertTrue(prompt.contains("1\t合格的新句甲"));
            assertTrue(prompt.contains("2\t  原样保留  "));
            // Ignore attempts to overwrite a good line, a kept line and a section header.
            return List.of(row(5,"补写的新句乙"),row(6,"补写的新句丙"),row(1,"错误覆盖"),row(2,"错误覆盖"),row(4,"错误覆盖"));
        });
        var service=service(writer); var key=UUID.randomUUID();
        assertEquals(EXPECTED,preview(service,f,key));
        assertEquals(EXPECTED,preview(service,f,key));
        assertEquals(List.of(List.of(1,5,6),List.of(5,6)),requests);
        assertEquals(f.balance()-2,credits.balance(f.owner()));
        assertEquals(1,count(f,"credit_ledger","entry_type='DEBIT'"));
        assertEquals(2,count(f,"rewrite_consumption","status='SUCCEEDED'")); // free warm-up + one preview
        originalUnchanged(f);
    }

    @Test void freePreviewUsesOnlyOneFreeAllowanceEvenWithRepair() {
        var f=fixture(false); var call=new AtomicInteger();
        var writer=new Script((p,t)->call.incrementAndGet()==1?List.of(row(1,"合格的新句甲")):complete());
        assertEquals(EXPECTED,preview(service(writer),f,UUID.randomUUID()));
        assertEquals(2,writer.calls.get());
        assertEquals(f.balance(),credits.balance(f.owner()));
        assertEquals(0,charges.freeRemaining(f.owner()));
        assertEquals(1,count(f,"rewrite_consumption","status='SUCCEEDED'"));
        assertEquals(0,count(f,"credit_ledger","entry_type='DEBIT'"));
    }

    @Test void missingDuplicateNullUnchangedMultilineAndOversizedRowsRepairOnlyTheirOwnPosition() {
        var cases=new ArrayList<List<InspirationRewriter.Replacement>>();
        cases.add(List.of());
        for(String text:List.of("原句乙","", " \t ","[Verse]","两行\n歌词","含\t制表符","x".repeat(12001))) cases.add(List.of(row(5,text)));
        cases.add(List.of(row(5,null)));
        cases.add(List.of(row(5,"看起来合格"),row(5,"重复行号")));
        cases.add(Arrays.asList((InspirationRewriter.Replacement)null));
        for(var bad:cases) {
            var f=fixture(false);var calls=new AtomicInteger();
            var writer=new Script((p,t)->{
                if(calls.incrementAndGet()==1) { var rows=new ArrayList<>(bad); rows.add(row(1,"合格的新句甲"));rows.add(row(6,"补写的新句丙"));return rows; }
                assertEquals(List.of(5),t);return List.of(row(5,"补写的新句乙"));
            });
            assertEquals(EXPECTED,preview(service(writer),f,UUID.randomUUID()));
            assertEquals(2,writer.calls.get());originalUnchanged(f);
        }
    }

    @Test void repairStillInvalidStopsAfterTwoCallsRefundsAndNewKeyCanRetry() {
        var f=fixture(true);var writer=new Script((p,t)->List.of(row(1,"合格的新句甲")));
        var service=service(writer);var key=UUID.randomUUID();
        assertEquals("INVALID_REWRITE_RESPONSE",assertThrows(DomainException.class,()->preview(service,f,key)).code());
        assertEquals("REWRITE_REQUEST_FAILED",assertThrows(DomainException.class,()->preview(service,f,key)).code());
        assertEquals(2,writer.calls.get());assertEquals(f.balance(),credits.balance(f.owner()));
        assertEquals(1,count(f,"rewrite_consumption","status='FAILED' AND refunded=1"));
        assertEquals(EXPECTED,preview(service(new Script((p,t)->complete())),f,UUID.randomUUID()));
        assertEquals(f.balance()-2,credits.balance(f.owner()));originalUnchanged(f);
    }

    @Test void unknownRepairIsNotRefundedOrResubmittedAndReleasesTheSlot() {
        var f=fixture(true);var calls=new AtomicInteger();
        var writer=new Script((p,t)->{
            if(calls.incrementAndGet()==1)return List.of(row(1,"合格的新句甲"));
            throw new DomainException("REWRITE_PROVIDER_RESULT_UNKNOWN","模拟补写超时");
        });
        var service=service(writer);var key=UUID.randomUUID();
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN",assertThrows(DomainException.class,()->preview(service,f,key)).code());
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN",assertThrows(DomainException.class,()->preview(service,f,key)).code());
        assertEquals(2,writer.calls.get());assertEquals(f.balance()-2,credits.balance(f.owner()));
        assertEquals(1,count(f,"rewrite_consumption","status='UNKNOWN' AND refunded=0"));
        assertEquals("WORK_VERSION_CONFLICT",assertThrows(DomainException.class,()->
            service.preview(f.owner(),f.work(),1,UUID.randomUUID(),SOURCE,List.of(2),"zh-CN")).code());
        // The same service's single slot and owner lock are available for a distinct explicit request.
        var other=fixture(false);
        assertEquals("REWRITE_PROVIDER_RESULT_UNKNOWN",assertThrows(DomainException.class,()->preview(service,other,UUID.randomUUID())).code());
        assertEquals(3,writer.calls.get());originalUnchanged(f);
    }

    @Test void knownMalformedResponseRepairsOnceButTransportFailureDoesNotRetry() {
        for(String code:List.of("INVALID_REWRITE_RESPONSE","REWRITE_PROVIDER_UNAVAILABLE","REWRITE_PROVIDER_RESULT_UNKNOWN")) {
            var f=fixture(true);var calls=new AtomicInteger();
            var writer=new Script((p,t)->{
                if(calls.incrementAndGet()==1)throw new DomainException(code,"模拟错误");
                assertEquals(List.of(1,5,6),t);return complete();
            });
            if(code.equals("INVALID_REWRITE_RESPONSE")) {
                assertEquals(EXPECTED,preview(service(writer),f,UUID.randomUUID()));assertEquals(2,writer.calls.get());
            } else {
                assertEquals(code,assertThrows(DomainException.class,()->preview(service(writer),f,UUID.randomUUID())).code());
                assertEquals(1,writer.calls.get());
            }
            originalUnchanged(f);
        }
    }

    @Test void workChangedBeforeRepairStopsAndRefundsWithoutAnotherModelCall() {
        var f=fixture(true);
        var writer=new Script((p,t)->{
            works.update(f.owner(),f.work(),0,new WorkApi.Draft("","","用户修改了故事","","流行",""));
            return List.of(row(1,"合格的新句甲"));
        });
        assertEquals("WORK_VERSION_CONFLICT",assertThrows(DomainException.class,()->preview(service(writer),f,UUID.randomUUID())).code());
        assertEquals(1,writer.calls.get());assertEquals(f.balance(),credits.balance(f.owner()));originalUnchanged(f);
    }

    @Test void concurrentReplayDuringRepairDoesNotStartAnotherReservationOrModelCall() throws Exception {
        var f=fixture(true);var key=UUID.randomUUID();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();var writer=new Script((p,t)->{
            if(calls.incrementAndGet()==1)return List.of(row(1,"合格的新句甲"));
            entered.countDown();
            try { if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("repair latch timed out"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new AssertionError(e); }
            return List.of(row(5,"补写的新句乙"),row(6,"补写的新句丙"));
        });
        var first=service(writer);var anotherInstance=service(writer);var pool=Executors.newSingleThreadExecutor();
        try {
            var running=pool.submit(()->preview(first,f,key));assertTrue(entered.await(15,TimeUnit.SECONDS));
            assertEquals("REWRITE_REQUEST_PENDING",assertThrows(DomainException.class,()->preview(anotherInstance,f,key)).code());
            assertEquals(1,count(f,"credit_ledger","entry_type='DEBIT'"));
            release.countDown();assertEquals(EXPECTED,running.get(15,TimeUnit.SECONDS));
            assertEquals(EXPECTED,preview(anotherInstance,f,key));assertEquals(2,writer.calls.get());
        } finally { release.countDown();pool.shutdownNow(); }
    }

    @Test void realHttpAdapterPreservesGoodRowsFromMalformedJsonEntriesForRepair() throws Exception {
        var f=fixture(true);var json=new ObjectMapper();var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var request=json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var context=json.readTree(request.path("messages").path(1).path("content").asText());
            int call=count.incrementAndGet();
            List<?> rows=call==1?List.of(Map.of("index",1,"text","合格的新句甲"),Map.of("index",5,"text",123),Map.of("index","6","text","错误行号"))
                :List.of(Map.of("index",5,"text","补写的新句乙"),Map.of("index",6,"text","补写的新句丙"));
            boolean correct=call==1?context.path("targetIndices").equals(json.readTree("[1,5,6]")):
                context.path("targetIndices").equals(json.readTree("[5,6]")) && context.path("text").asText().contains("1\t合格的新句甲");
            var body=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(Map.of("replacements",rows))))))).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(correct?200:400,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var writer=new QwenInspirationRewriter("offline-key","offline-model","http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions");
            assertEquals(EXPECTED,preview(service(writer),f,UUID.randomUUID()));
            assertEquals(2,count.get());assertEquals(f.balance()-2,credits.balance(f.owner()));originalUnchanged(f);
        } finally {server.stop(0);}
    }
}
