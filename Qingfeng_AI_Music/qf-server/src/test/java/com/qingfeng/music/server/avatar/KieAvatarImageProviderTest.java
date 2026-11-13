package com.qingfeng.music.server.avatar;

import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Only loopback HTTP and isolated H2; no real provider, files or production database. */
class KieAvatarImageProviderTest {
    @Test void normalCreateAndQueryUseTheInjectedEndpointOnceEach() throws Exception {
        var prompt=new AtomicReference<String>();
        try(var server=new LocalServer(exchange->{
            if(exchange.getRequestMethod().equals("POST")) {
                prompt.set(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()).path("input").path("prompt").asText());
                reply(exchange,200,"{\"code\":200,\"data\":{\"taskId\":\"remote\"}}");
            } else {
                assertEquals("/jobs/recordInfo",exchange.getRequestURI().getPath());
                assertEquals("taskId=remote",exchange.getRequestURI().getRawQuery());
                reply(exchange,200,"{\"code\":200,\"data\":{\"taskId\":\"remote\",\"model\":\"google/nano-banana\",\"state\":\"generating\"}}");
            }
        })) {
            var provider=server.provider(Duration.ofSeconds(3));
            assertEquals("remote",provider.submit("橘猫歌手"));
            assertEquals("橘猫歌手",prompt.get());assertEquals("RUNNING",provider.query("remote").status());
            assertEquals(2,server.calls.get());
        }
    }

    @Test void truncatedJsonAndPrematureBodyEndAreUnknownAndNeverRetried() throws Exception {
        for(boolean prematureEnd:new boolean[]{false,true}) {
            try(var server=new LocalServer(exchange->{
                exchange.getRequestBody().readAllBytes();
                byte[] bytes="{\"code\":200,\"data\":".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length+(prematureEnd?100:0));
                exchange.getResponseBody().write(bytes);
            })) {
                var failure=assertTimeoutPreemptively(Duration.ofSeconds(4),()->assertThrows(RuntimeException.class,()->server.provider(Duration.ofSeconds(1)).submit("猫")));
                assertFalse(failure instanceof AvatarImageProvider.Rejected,"An incomplete result cannot authorize a refund");
                assertEquals(1,server.calls.get());
            }
        }
    }

    @Test void chunkedResponseAbove256KiBIsUnknownAndNeverRetried() throws Exception {
        try(var server=new LocalServer(exchange->{
            exchange.getRequestBody().readAllBytes();
            // A valid JSON result would succeed without the byte guard; no Content-Length is supplied.
            byte[] bytes=("{\"code\":200,\"data\":{\"taskId\":\"remote\"},\"padding\":\""+"x".repeat(256*1024)+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(bytes);
        })) {
            assertTimeoutPreemptively(Duration.ofSeconds(4),()->assertThrows(IllegalStateException.class,()->server.provider(Duration.ofSeconds(3)).submit("猫")));
            assertEquals(1,server.calls.get());
        }
    }

    @Test void stalledSubmissionCancelsSocketAndKeepsDebitedTaskUnknownWithoutResubmitting() throws Exception {
        try(var server=new StalledServer(200)) {
            var fixture=new Billing(server.provider(Duration.ofSeconds(1)));String key=UUID.randomUUID().toString();
            assertEquals("UNKNOWN",assertTimeoutPreemptively(Duration.ofSeconds(4),()->fixture.service.start("alice",key,"猫")).status());
            assertTrue(server.bodyStarted.await(1,TimeUnit.SECONDS));server.assertDisconnected();
            assertEquals(15,fixture.ledger.balance("alice"));assertEquals(0,fixture.refunds());
            assertEquals("UNKNOWN",fixture.service.start("alice",key,"猫").status());fixture.service.runOnce();
            assertEquals(1,server.calls.get());assertEquals(15,fixture.ledger.balance("alice"));assertEquals(0,fixture.refunds());
        }
    }

    @Test void stalledReadAlsoHasABodyDeadlineAndCancelsItsSocket() throws Exception {
        try(var server=new StalledServer(200)) {
            assertTimeoutPreemptively(Duration.ofSeconds(4),()->assertThrows(IllegalStateException.class,()->server.provider(Duration.ofSeconds(1)).query("remote")));
            assertTrue(server.bodyStarted.await(1,TimeUnit.SECONDS));server.assertDisconnected();assertEquals(1,server.calls.get());
        }
    }

    @Test void definitiveHttpAndJsonRefusalsRemainRejected() throws Exception {
        for(int code:new int[]{400,401,402,403,404,422,429}) {
            try(var server=new LocalServer(exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,code,"not JSON");})) {
                assertThrows(AvatarImageProvider.Rejected.class,()->server.provider(Duration.ofSeconds(3)).submit("猫"));assertEquals(1,server.calls.get());
            }
        }
        try(var server=new LocalServer(exchange->{exchange.getRequestBody().readAllBytes();reply(exchange,200,"{\"code\":429}");})) {
            assertThrows(AvatarImageProvider.Rejected.class,()->server.provider(Duration.ofSeconds(3)).submit("猫"));assertEquals(1,server.calls.get());
        }
    }

    @Test void definitiveHttpRefusalDoesNotWaitForItsStalledBodyAndRefundsExactlyOnce() throws Exception {
        try(var server=new StalledServer(400)) {
            var fixture=new Billing(server.provider(Duration.ofSeconds(10)));String key=UUID.randomUUID().toString();
            assertEquals("FAILED",assertTimeoutPreemptively(Duration.ofSeconds(3),()->fixture.service.start("alice",key,"猫")).status());
            assertEquals(20,fixture.ledger.balance("alice"));assertEquals(1,fixture.refunds());
            assertEquals("FAILED",fixture.service.start("alice",key,"猫").status());fixture.service.runOnce();
            assertEquals(1,server.calls.get());assertEquals(20,fixture.ledger.balance("alice"));assertEquals(1,fixture.refunds());
        }
    }

    private static void reply(HttpExchange exchange,int status,String body)throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    @FunctionalInterface private interface Handler {void handle(HttpExchange exchange)throws Exception;}
    private static class LocalServer implements AutoCloseable {
        final AtomicInteger calls=new AtomicInteger();final HttpServer server;final ExecutorService workers=Executors.newFixedThreadPool(2);
        LocalServer(Handler handler)throws IOException {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(workers);
            server.createContext("/jobs/",exchange->{
                calls.incrementAndGet();
                try{handler.handle(exchange);}
                catch(IOException disconnected){/* Expected when the bounded reader cancels an exchange. */}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                catch(Exception failure){throw new IllegalStateException(failure);}
                finally{exchange.close();}
            });server.start();
        }
        KieAvatarImageProvider provider(Duration timeout){
            return new KieAvatarImageProvider("test-only-key",true,()->{},()->{},URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/jobs/"),HttpClient.newHttpClient(),timeout);
        }
        public void close(){server.stop(0);workers.shutdownNow();}
    }
    private static final class StalledServer implements AutoCloseable {
        final CountDownLatch bodyStarted=new CountDownLatch(1),probe=new CountDownLatch(1),disconnected=new CountDownLatch(1);
        final LocalServer delegate;final AtomicInteger calls;
        StalledServer(int status)throws IOException {
            delegate=new LocalServer(exchange->{
                exchange.getRequestBody().readAllBytes();
                try {
                    exchange.sendResponseHeaders(status,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();bodyStarted.countDown();
                    if(!probe.await(6,TimeUnit.SECONDS))return;
                    byte[] chunk=new byte[4096];
                    // Less than 256 KiB, so disconnect is caused by cancellation, not the size guard.
                    for(int i=0;i<32;i++){exchange.getResponseBody().write(chunk);exchange.getResponseBody().flush();}
                }catch(IOException closed){disconnected.countDown();}
            });calls=delegate.calls;
        }
        KieAvatarImageProvider provider(Duration timeout){return delegate.provider(timeout);}
        void assertDisconnected()throws InterruptedException{probe.countDown();assertTrue(disconnected.await(3,TimeUnit.SECONDS),"The original HttpClient exchange must be cancelled");}
        public void close(){probe.countDown();delegate.close();}
    }
    private static final class Billing {
        final JdbcTemplate db;final JdbcCreditsLedger ledger;final AvatarGenerationService service;
        Billing(AvatarImageProvider provider){
            var ds=new DriverManagerDataSource("jdbc:h2:mem:avatar_http_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            db=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            db.execute("CREATE TABLE creator (id VARCHAR(36) PRIMARY KEY,payment_risk_frozen INT NOT NULL DEFAULT 0)");
            db.execute("CREATE TABLE credit_account (owner_id VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
            db.execute("CREATE TABLE credit_ledger (entry_type VARCHAR(16) NOT NULL,reference_id VARCHAR(160) NOT NULL,owner_id VARCHAR(36) NOT NULL,amount BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,PRIMARY KEY(entry_type,reference_id))");
            db.execute("CREATE TABLE avatar_generation_task (id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) NOT NULL,request_key VARCHAR(36) NOT NULL,prompt VARCHAR(1000) NOT NULL,expected_version VARCHAR(36),status VARCHAR(24) NOT NULL,provider_task_id VARCHAR(255),lease_token VARCHAR(36),lease_until TIMESTAMP,next_attempt_at TIMESTAMP,failures INT NOT NULL DEFAULT 0,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(creator_id,request_key))");
            db.update("INSERT INTO creator(id) VALUES ('alice')");ledger=new JdbcCreditsLedger(db,tx);ledger.credit("alice",20,"seed");
            var avatars=mock(AvatarService.class);when(avatars.current(anyString())).thenReturn(new AvatarService.View(null,null));
            var pricing=mock(CreditsPricing.class);when(pricing.enabled()).thenReturn(true);
            service=new AvatarGenerationService(db,tx,avatars,provider,ledger,pricing,3);
        }
        int refunds(){return db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND'",Integer.class);}
    }
}
