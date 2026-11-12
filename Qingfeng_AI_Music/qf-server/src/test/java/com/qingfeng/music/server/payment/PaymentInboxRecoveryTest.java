package com.qingfeng.music.server.payment;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:payment_inbox_recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.paypal.enabled=true","qingfeng.paypal.client-id=fake","qingfeng.paypal.client-secret=fake","qingfeng.paypal.merchant-id=MERCHANT","qingfeng.paypal.webhook-id=fake","qingfeng.payment.inbox.scheduler-enabled=false","qingfeng.payment.risk.admin-owner-ids=00000000-0000-0000-0000-000000000001"})
@ActiveProfiles("test")
class PaymentInboxRecoveryTest {
    @Autowired PayPalPaymentService actual; @Autowired PaymentInbox inbox; @Autowired PaymentRiskService risks;
    @Autowired CreditsLedger credits; @Autowired IdentityApi identity; @Autowired JdbcTemplate db; @Autowired PlatformTransactionManager manager;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    final ObjectMapper json=new ObjectMapper();
    String key(){return UUID.randomUUID().toString();}
    String owner(){return identity.create().creatorId();}
    String insert(String owner,String remote){String id=key();db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,provider_link_id,status,created_at,provider) VALUES(?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,'PAYPAL_SANDBOX')",id,owner,"starter-USD",new BigDecimal("9.90"),"USD",100,remote);return id;}
    PayPalCheckoutGateway.Snapshot paid(String id,String remote,String capture){return new PayPalCheckoutGateway.Snapshot(remote,id,"COMPLETED","MERCHANT",capture,"COMPLETED",new BigDecimal("9.90"),"USD");}
    String uniqueId(String prefix){return prefix+key().replace("-","").substring(0,20);}
    byte[] body(String id,String type,String resource,String remote){return json.writeValueAsString(Map.of("id",id,"event_type",type,"resource",Map.of("id",resource,"supplementary_data",Map.of("related_ids",Map.of("order_id",remote))))).getBytes(StandardCharsets.UTF_8);}
    PayPalPaymentService service(PayPalCheckoutGateway gateway){var service=spy(actual);doReturn(gateway).when(service).gateway();when(gateway.verifyWebhook(any(),any())).thenReturn(true);return service;}
    PayPalCheckoutGateway.WebhookHeaders headers(){return new PayPalCheckoutGateway.WebhookHeaders("fake","fake","fake","fake","fake");}

    @Test void verifiedCallbackCommitsInboxBeforeAcknowledgementAndWorkerCreditsOnce(){
        String owner=owner(),remote=uniqueId("O"),capture=uniqueId("C"),id=insert(owner,remote),event=uniqueId("WH-");
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);when(gateway.retrieve(remote)).thenReturn(paid(id,remote,capture));
        byte[] body=body(event,"PAYMENT.CAPTURE.COMPLETED",capture,remote);
        service.webhook(headers(),body);service.webhook(headers(),body);
        assertEquals(0,credits.balance(owner));verify(gateway,never()).retrieve(anyString());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id=?",Integer.class,event));
        assertTrue(service.processNextEvent());assertEquals(100,credits.balance(owner));
        service.webhook(headers(),body);assertEquals(100,credits.balance(owner));
        assertEquals("DONE",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE event_id=?",String.class,event));
        verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void leaseExpiryAllowsRecoveryAndAnOldWorkerCannotCommit(){
        String event=uniqueId("lease-");inbox.acceptVerified("LEASE_TEST",event,"TEST.EVENT","{}");
        var first=inbox.claim("LEASE_TEST").orElseThrow();assertEquals(event,first.eventId());
        db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE provider='LEASE_TEST' AND event_id=?",event);
        var second=inbox.claim("LEASE_TEST").orElseThrow();assertEquals(event,second.eventId());assertNotEquals(first.token(),second.token());
        inbox.complete(first,()->fail("stale lease must not execute its mutation"));
        assertThrows(IllegalStateException.class,()->inbox.complete(second,()->{throw new IllegalStateException("simulated crash before commit");}));
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider='LEASE_TEST' AND event_id=?",String.class,event));
        inbox.complete(second,()->{});assertEquals("DONE",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider='LEASE_TEST' AND event_id=?",String.class,event));
    }
    @Test void inboxAndWakeupCommitAtomicallyAndProviderNamespacesAreIndependent(){
        String event=uniqueId("same-");
        var failing=new PaymentInbox(db,new TransactionTemplate(manager),(topic,key,payload)->{throw new IllegalStateException("outbox unavailable");});
        assertThrows(IllegalStateException.class,()->failing.acceptVerified("OFFLINE",event,"TEST.EVENT","{}"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id=?",Integer.class,event));
        inbox.acceptVerified("OFFLINE",event,"TEST.EVENT","{}");inbox.acceptVerified("STRIPE",event,"TEST.EVENT","{}");
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id=?",Integer.class,event));
        assertThrows(IllegalStateException.class,()->inbox.acceptVerified("OFFLINE",event,"TEST.EVENT","{\"changed\":true}"));
    }
    @Test void anExpiredLeaseCannotCommitRenewOrDelayRecoveryEvenBeforeAnotherWorkerClaims(){
        String event=uniqueId("expired-");inbox.acceptVerified("EXPIRED_TEST",event,"TEST.EVENT","{}");
        var expired=inbox.claim("EXPIRED_TEST").orElseThrow();
        db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE provider='EXPIRED_TEST' AND event_id=?",event);
        inbox.complete(expired,()->fail("An expired lease must not execute business mutations"));
        assertThrows(IllegalStateException.class,()->inbox.renew(expired));
        inbox.retry(expired);
        assertEquals("PROCESSING",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider='EXPIRED_TEST' AND event_id=?",String.class,event));
        var recovered=inbox.claim("EXPIRED_TEST").orElseThrow();assertNotEquals(expired.token(),recovered.token());
        inbox.renew(recovered);inbox.complete(recovered,()->{});
        assertEquals("DONE",db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE provider='EXPIRED_TEST' AND event_id=?",String.class,event));
    }
    @Test void verifiedRefundFreezesSpendingWithoutBlindBalanceDeductionAndRequiresAuditedReview(){
        String owner=owner(),remote=uniqueId("O"),capture=uniqueId("C"),id=insert(owner,remote),refund=uniqueId("R"),event=uniqueId("WH-");
        actual.settle(id,paid(id,remote,capture));
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);
        when(gateway.retrieveRefund(refund)).thenReturn(json.readTree("{\"id\":\""+refund+"\",\"links\":[{\"rel\":\"up\",\"href\":\"https://api-m.sandbox.paypal.com/v2/payments/captures/"+capture+"\"}]}"));
        service.webhook(headers(),body(event,"PAYMENT.CAPTURE.REFUNDED",refund,""));assertTrue(service.processNextEvent());
        assertEquals(100,credits.balance(owner));
        assertEquals("PAYMENT_RISK_REVIEW",assertThrows(DomainException.class,()->credits.debit(owner,1,key())).code());
        var risk=risks.listOpen(200).stream().filter(r->r.ownerId().equals(owner)).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,()->risks.resolveCase(risk.id(),"stale-event","RELEASE","offline-reviewer","offline evidence fully reconciled"));
        risks.resolveCase(risk.id(),event,"HOLD","offline-reviewer","offline evidence requires further investigation");
        assertThrows(DomainException.class,()->credits.debit(owner,1,key()));
        risks.resolveCase(risk.id(),event,"RELEASE","offline-reviewer","offline evidence confirms no unpaid entitlement");
        credits.debit(owner,1,key());assertEquals(99,credits.balance(owner));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_risk_review WHERE case_id=?",Integer.class,risk.id()));
        service.webhook(headers(),body(event,"PAYMENT.CAPTURE.REFUNDED",refund,""));
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM payment_risk_case WHERE id=?",String.class,risk.id()));
    }
    @Test void aCreateFailureIsClassifiedAndUnknownResultRecoversOnlyTheOriginalOrder(){
        var guest=identity.create();var signed=identity.signInWithGoogle(guest.token(),guest.creatorId(),key(),null,null);String owner=signed.creatorId();
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);
        when(gateway.create(anyString(),anyString(),any(),anyString(),anyString(),anyString())).thenThrow(new PayPalCheckoutGateway.ProviderBusy());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD")).getStatusCode().value());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status='FAILED' AND failure_code='CREATE_NOT_STARTED'",Integer.class,owner));
        doThrow(new PayPalCheckoutGateway.CreationRejected()).when(gateway).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());
        assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status='FAILED'",Integer.class,owner));
        doThrow(new IllegalStateException("unknown transport result")).when(gateway).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());
        assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD"));
        String order=db.queryForObject("SELECT id FROM payment_order WHERE creator_id=? AND status='CREATION_UNKNOWN'",String.class,owner);
        assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD"));
        String remote=uniqueId("O"),capture=uniqueId("C");when(gateway.retrieve(remote)).thenReturn(paid(order,remote,capture));
        long before=credits.balance(owner);assertEquals("PAID",service.recover(owner,order,remote).get("status"));
        assertEquals(before+100,credits.balance(owner));service.recover(owner,order,remote);assertEquals(before+100,credits.balance(owner));
        verify(gateway,times(3)).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void disputeIdIsRecognizedAndClosingOneCaseCannotReleaseAnotherCase(){
        String owner=owner(),remote=uniqueId("O"),capture=uniqueId("C"),id=insert(owner,remote);actual.settle(id,paid(id,remote,capture));
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);
        var events=new ArrayList<String>();
        for(int n=0;n<2;n++){
            String dispute=uniqueId("PP-"),event=uniqueId("WH-");events.add(event);
            when(gateway.retrieveDispute(dispute)).thenReturn(json.readTree("{\"dispute_id\":\""+dispute+"\",\"disputed_transactions\":[{\"seller_transaction_id\":\""+capture+"\"}]}"));
            byte[] body=json.writeValueAsString(Map.of("id",event,"event_type","CUSTOMER.DISPUTE.CREATED","resource",Map.of("dispute_id",dispute))).getBytes(StandardCharsets.UTF_8);
            service.webhook(headers(),body);assertTrue(service.processNextEvent());
        }
        var cases=risks.listOpen(200).stream().filter(r->r.ownerId().equals(owner)).toList();assertEquals(2,cases.size());
        risks.resolveCase(cases.get(0).id(),cases.get(0).lastEventId(),"RELEASE","offline-reviewer","Verified provider outcome and remaining entitlement");
        assertThrows(DomainException.class,()->credits.debit(owner,1,key()));
        risks.resolveCase(cases.get(1).id(),cases.get(1).lastEventId(),"RELEASE","offline-reviewer","Verified provider outcome and remaining entitlement");
        credits.debit(owner,1,key());assertEquals(99,credits.balance(owner));
    }
    @Test void refundBeforeCompletionCorrelatesWithoutGrantingAlreadyRefundedCredits(){
        String owner=owner(),remote=uniqueId("O"),capture=uniqueId("C"),id=insert(owner,remote),refund=uniqueId("R");
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);
        when(gateway.retrieveRefund(refund)).thenReturn(json.readTree("{\"id\":\""+refund+"\",\"links\":[{\"rel\":\"up\",\"href\":\"https://api-m.sandbox.paypal.com/v2/payments/captures/"+capture+"\"}]}"));
        when(gateway.retrieveCapture(capture)).thenReturn(json.readTree("{\"id\":\""+capture+"\",\"supplementary_data\":{\"related_ids\":{\"order_id\":\""+remote+"\"}}}"));
        when(gateway.retrieve(remote)).thenReturn(new PayPalCheckoutGateway.Snapshot(remote,id,"COMPLETED","MERCHANT",capture,"REFUNDED",new BigDecimal("9.90"),"USD"));
        service.webhook(headers(),body(uniqueId("WH-"),"PAYMENT.CAPTURE.REFUNDED",refund,""));assertTrue(service.processNextEvent());
        service.webhook(headers(),body(uniqueId("WH-"),"PAYMENT.CAPTURE.COMPLETED",capture,remote));assertTrue(service.processNextEvent());
        assertEquals(0,credits.balance(owner));assertEquals(1,db.queryForObject("SELECT payment_risk_frozen FROM creator WHERE id=?",Integer.class,owner));
        assertEquals(capture,db.queryForObject("SELECT provider_capture_id FROM payment_order WHERE id=?",String.class,id));
    }
    @Test void reconciliationFindsMissedRefundsAndDoesNotReopenAnAlreadyReviewedObservation() throws Exception {
        String owner=owner(),remote=uniqueId("O"),capture=uniqueId("C"),id=insert(owner,remote);actual.settle(id,paid(id,remote,capture));
        db.update("UPDATE payment_order SET created_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE id=?",id);
        String expiredRemote=uniqueId("O"),expiredCapture=uniqueId("C"),expired=insert(owner(),expiredRemote);actual.settle(expired,paid(expired,expiredRemote,expiredCapture));
        db.update("UPDATE payment_order SET created_at=TIMESTAMPADD(DAY,-31,CURRENT_TIMESTAMP) WHERE id=?",expired);
        var gateway=mock(PayPalCheckoutGateway.class);var service=service(gateway);
        var status=new java.util.concurrent.atomic.AtomicReference<>("PARTIALLY_REFUNDED");
        when(gateway.retrieveCapture(capture)).thenAnswer(ignored->json.readTree("{\"id\":\""+capture+"\",\"status\":\""+status.get()+"\",\"amount\":{\"currency_code\":\"USD\",\"value\":\"9.90\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\""+remote+"\"}}}"));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var scans=new ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<2;n++)scans.add(pool.submit(()->{try{start.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}service.reconcileOrders();}));
            start.countDown();for(var scan:scans)scan.get(10,java.util.concurrent.TimeUnit.SECONDS);
        }finally{start.countDown();pool.shutdownNow();}
        verify(gateway,times(1)).retrieveCapture(capture);verify(gateway,never()).retrieveCapture(expiredCapture);
        assertEquals(100,credits.balance(owner));assertTrue(service.processNextEvent());
        var risk=risks.listOpen(200).stream().filter(r->r.ownerId().equals(owner)).findFirst().orElseThrow();
        assertThrows(DomainException.class,()->credits.debit(owner,1,key()));
        risks.resolveCase(risk.id(),risk.lastEventId(),"RELEASE","offline-reviewer","Provider refund and remaining entitlement were reconciled");
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",id);
        service.reconcileOrders();
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM payment_risk_case WHERE id=?",String.class,risk.id()));
        assertEquals(0,db.queryForObject("SELECT payment_risk_frozen FROM creator WHERE id=?",Integer.class,owner));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND event_type='PAYMENT.CAPTURE.STATUS_RISK' AND payload LIKE ?",Integer.class,"%"+capture+"%"));
        status.set("REFUNDED");db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",id);
        service.reconcileOrders();assertTrue(service.processNextEvent());
        assertEquals("REVIEW",db.queryForObject("SELECT status FROM payment_risk_case WHERE id=?",String.class,risk.id()));
        assertEquals(100,credits.balance(owner));verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void financialHttpBoundaryRequiresItsOwnRoleAndCsrf() throws Exception {
        var http=java.net.http.HttpClient.newHttpClient();String base="http://127.0.0.1:"+port;
        var anonymous=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/payment-risk/cases")).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(401,anonymous.statusCode());
        var normal=identity.create();
        var denied=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/payment-risk/cases")).header("Cookie","QF_SESSION="+normal.token()).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(403,denied.statusCode());
        String finance="00000000-0000-0000-0000-000000000001",token=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)",finance);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        db.update("INSERT INTO creator_session(token_hash,creator_id,expires_at) VALUES(?,?,TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP))",hash,finance);
        var authorized=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/payment-risk/cases")).header("Cookie","QF_SESSION="+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(200,authorized.statusCode());
        var noCsrf=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/app/payment-risk/cases/"+key()+"/review")).header("Cookie","QF_SESSION="+token).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"expectedEventId\":\"offline\",\"decision\":\"RELEASE\",\"reason\":\"Verified test evidence only\"}")).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
        assertEquals(403,noCsrf.statusCode());
    }
}
