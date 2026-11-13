package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual local order SQL/transaction and service routing. Inbox, supplier and ledger mocked.
 * This suite does not establish MySQL, payment, refund principal or Live acceptance. */
class PayPalCaptureRecoveryServiceTest {
    final ObjectMapper json=new ObjectMapper();JdbcTemplate db;TransactionTemplate tx;
    PayPalPaymentService service;PayPalCheckoutGateway gateway;PaymentInbox inbox;PaymentRiskService risks;JdbcFencedCreditsLedger ledger;
    String owner,order;String captureState="PARTIALLY_REFUNDED";boolean completeEvidence=true;
    @BeforeEach void start(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:paypal_recovery_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");ds.setDriverClassName("org.h2.Driver");db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),package_id VARCHAR(80),amount DECIMAL(18,2),currency VARCHAR(3),credits BIGINT,status VARCHAR(24),created_at TIMESTAMP(6),provider VARCHAR(24),provider_link_id VARCHAR(128),provider_capture_id VARCHAR(128),failure_code VARCHAR(80))");
        db.execute("CREATE TABLE p0_credit_batch(reference VARCHAR(128) PRIMARY KEY,status VARCHAR(24))");
        db.execute("CREATE TABLE p0_stripe_dispute_proof(provider VARCHAR(24),merchant VARCHAR(64),environment VARCHAR(24),order_id VARCHAR(36),dispute_id VARCHAR(128))");
        owner=UUID.randomUUID().toString();order=UUID.randomUUID().toString();db.update("INSERT INTO creator VALUES(?)",owner);
        db.update("INSERT INTO payment_order VALUES(?,?,?,9.90,'USD',100,'PENDING',CURRENT_TIMESTAMP,'PAYPAL_SANDBOX','REMOTE123',NULL,NULL)",order,owner,"starter-USD");
        gateway=mock(PayPalCheckoutGateway.class);inbox=mock(PaymentInbox.class);risks=mock(PaymentRiskService.class);ledger=mock(JdbcFencedCreditsLedger.class);
        var env=new MockEnvironment();service=spy(new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env));
        ReflectionTestUtils.setField(service,"enabled",true);ReflectionTestUtils.setField(service,"environment","sandbox");ReflectionTestUtils.setField(service,"clientId","offline-client");ReflectionTestUtils.setField(service,"clientSecret","offline-secret");ReflectionTestUtils.setField(service,"merchantId","MERCHANT123");ReflectionTestUtils.setField(service,"webhookId","offline-webhook");service.infrastructure(inbox,risks);doReturn(gateway).when(service).gateway();
        when(gateway.retrieve("REMOTE123")).thenAnswer(c->snapshot());when(gateway.retrieveCapture("CAPTURE123")).thenAnswer(c->capture());
        when(gateway.retrieveRefund("REFUND123")).thenReturn(json.readTree("{\"id\":\"REFUND123\",\"status\":\"COMPLETED\",\"amount\":{\"value\":\"1.98\",\"currency_code\":\"USD\"},\"links\":[{\"rel\":\"up\",\"href\":\"https://api-m.sandbox.paypal.com/v2/payments/captures/CAPTURE123\"}]}"));
        doAnswer(c->{tx.executeWithoutResult(t->((Runnable)c.getArgument(2)).run());return null;}).when(inbox).waitForProvider(any(),anyString(),any(),anyString());
        doAnswer(c->{tx.executeWithoutResult(t->((Runnable)c.getArgument(2)).run());return null;}).when(inbox).completeOwned(any(),anyCollection(),any());
        doAnswer(c->{tx.executeWithoutResult(t->((Runnable)c.getArgument(2)).run());return null;}).when(inbox).completeOwned(any(),anyString(),any());
    }
    @AfterEach void stop(){if(db!=null)db.execute("SHUTDOWN");}
    PayPalCheckoutGateway.Snapshot snapshot(){return new PayPalCheckoutGateway.Snapshot("REMOTE123",order,"COMPLETED","MERCHANT123","CAPTURE123",captureState,new BigDecimal("9.90"),"USD");}
    JsonNode capture(){return json.readTree("{\"id\":\"CAPTURE123\",\"status\":\""+captureState+"\",\"final_capture\":true,\"amount\":{\"value\":\"9.90\",\"currency_code\":\"USD\"},\"payee\":{\"merchant_id\":\""+(completeEvidence?"MERCHANT123":"WRONG")+"\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\"REMOTE123\"}}}");}
    PaymentInbox.Delivery event(String type,String resource){String event="evt-"+UUID.randomUUID();var d=new PaymentInbox.Delivery("PAYPAL_SANDBOX",event,type,json.writeValueAsString(Map.of("resourceId",resource,"orderId","REMOTE123")),"offline-lease",1,"MERCHANT123","sandbox");when(inbox.claim("PAYPAL_SANDBOX","MERCHANT123","sandbox")).thenReturn(Optional.of(d));return d;}
    String status(){return db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,order);}

    @Test void refundBeforeLocalPaymentIndependentlyProvesOriginalCaptureAndAppliesOnlyVerifiedRefund(){
        event("PAYMENT.CAPTURE.REFUNDED","REFUND123");assertTrue(service.processNextEvent());assertEquals("PAID",status());assertEquals("CAPTURE123",db.queryForObject("SELECT provider_capture_id FROM payment_order WHERE id=?",String.class,order));
        var sequence=inOrder(ledger);sequence.verify(ledger).credit(owner,100,"payment:"+order);sequence.verify(ledger).bindPayment("PAYPAL","MERCHANT123","sandbox",owner,order,"CAPTURE123","USD");sequence.verify(ledger).holdPaymentForReview(eq("PAYPAL"),eq("MERCHANT123"),eq("sandbox"),eq(owner),eq(order),eq("CAPTURE123"),eq("USD"),contains("paypal-original-capture"));sequence.verify(ledger).paymentRefund("PAYPAL","MERCHANT123","sandbox","REFUND123",owner,order,"CAPTURE123",198,"USD");
        verify(gateway,atLeastOnce()).retrieveCapture("CAPTURE123");verify(gateway,atLeastOnce()).retrieve("REMOTE123");verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void repeatedRefundAndLateRefundedCompletionNeverGrantOriginalCreditsAgainOrClearReview(){
        event("PAYMENT.CAPTURE.REFUNDED","REFUND123");service.processNextEvent();event("PAYMENT.CAPTURE.REFUNDED","REFUND123");service.processNextEvent();event("PAYMENT.CAPTURE.COMPLETED","CAPTURE123");service.processNextEvent();
        assertEquals("PAID",status());verify(ledger,times(1)).credit(owner,100,"payment:"+order);verify(ledger,atLeastOnce()).holdPaymentForReview(anyString(),anyString(),anyString(),eq(owner),eq(order),anyString(),anyString(),anyString());verify(ledger,never()).compensateReturnedFunds(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString());
    }
    @Test void lateCompletionWithRefundedCaptureRestoresReceiptAndRetainsAuditedReviewWaiting(){
        var d=event("PAYMENT.CAPTURE.COMPLETED","CAPTURE123");assertTrue(service.processNextEvent());assertEquals("PAID",status());verify(ledger).credit(owner,100,"payment:"+order);verify(ledger,never()).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-capture-risk-needs-funds-evidence"));
    }
    @Test void wrongPayeeOrMissingProofNeverFallsBackToRefundAsPaymentEvidence(){
        completeEvidence=false;var d=event("PAYMENT.CAPTURE.REFUNDED","REFUND123");assertTrue(service.processNextEvent());assertEquals("PENDING",status());verify(inbox).retry(d);verifyNoInteractions(ledger);
    }
    @Test void reversedCaptureIsNotRecoveryEvidenceAndNeverCreatesOriginalCredits(){
        captureState="REVERSED";var d=event("PAYMENT.CAPTURE.REFUNDED","REFUND123");assertTrue(service.processNextEvent());assertEquals("PENDING",status());verify(inbox).retry(d);verifyNoInteractions(ledger);
    }
    @Test void failedFinancialApplyRollsBackLocalRecoveredOrderAndReturnsEventToRetry(){
        doThrow(new IllegalStateException("synthetic financial write failure")).when(ledger).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        var d=event("PAYMENT.CAPTURE.REFUNDED","REFUND123");assertTrue(service.processNextEvent());assertEquals("PENDING",status());assertNull(db.queryForObject("SELECT provider_capture_id FROM payment_order WHERE id=?",String.class,order));verify(inbox).retry(d);
    }
}
