package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Reachable application/inbox callback tests with synthetic authenticated GET facts.
 * H2 verifies routing/rollback only; real ledger/lock acceptance is separate InnoDB coverage. */
class PayPalFundsServiceTest {
    JdbcTemplate db;TransactionTemplate tx;PayPalPaymentService service;PayPalCheckoutGateway gateway;
    PaymentInbox inbox;PaymentRiskService risks;JdbcFencedCreditsLedger ledger;
    final String owner=UUID.randomUUID().toString(),order=UUID.randomUUID().toString();
    PayPalFundsProofTest.Facts facts;
    @BeforeEach void setup(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:paypal_funds_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),package_id VARCHAR(80),amount DECIMAL(18,2),currency VARCHAR(3),credits BIGINT,status VARCHAR(24),created_at TIMESTAMP(6),provider VARCHAR(24),provider_link_id VARCHAR(128),provider_capture_id VARCHAR(128),failure_code VARCHAR(80))");
        db.execute("CREATE TABLE p0_credit_batch(reference VARCHAR(128) PRIMARY KEY,status VARCHAR(24))");
        db.execute("CREATE TABLE p0_credit_refund(order_id VARCHAR(36))");db.execute("CREATE TABLE p0_credit_dispute(order_id VARCHAR(36),external_id VARCHAR(128))");
        db.execute("CREATE TABLE p0_stripe_dispute_proof(provider VARCHAR(24),merchant VARCHAR(64),environment VARCHAR(24),dispute_id VARCHAR(128),order_id VARCHAR(36),capture_id VARCHAR(128),owner_id VARCHAR(36),loss_minor BIGINT,withdrawal_id VARCHAR(128),charge_balance_id VARCHAR(128),settlement_currency VARCHAR(3),settlement_principal_minor BIGINT,evidence_sha256 VARCHAR(64),created_at TIMESTAMP(6),PRIMARY KEY(provider,merchant,environment,dispute_id),UNIQUE(provider,merchant,environment,withdrawal_id))");
        db.execute("CREATE TABLE payment_risk_case(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),resource_id VARCHAR(128),event_type VARCHAR(96),order_id VARCHAR(36),owner_id VARCHAR(36),status VARCHAR(24),last_event_id VARCHAR(128),decision VARCHAR(40),reviewed_by VARCHAR(128),review_reason VARCHAR(1000),created_at TIMESTAMP(6),updated_at TIMESTAMP(6))");
        db.execute("CREATE TABLE payment_risk_review(id VARCHAR(36) PRIMARY KEY,case_id VARCHAR(36),expected_event_id VARCHAR(128),reviewer VARCHAR(128),decision VARCHAR(40),reason VARCHAR(1000),created_at TIMESTAMP(6))");
        db.update("INSERT INTO creator VALUES(?)",owner);
        db.update("INSERT INTO payment_order VALUES(?,?,'starter-USD',9.90,'USD',100,'PAID',CURRENT_TIMESTAMP,'PAYPAL_SANDBOX','REMOTE1','CAPTURE1',NULL)",order,owner);
        db.update("INSERT INTO p0_credit_batch VALUES(?,'ACTIVE')","payment:"+order);
        gateway=mock(PayPalCheckoutGateway.class);inbox=mock(PaymentInbox.class);ledger=mock(JdbcFencedCreditsLedger.class);risks=new PaymentRiskService(db,tx,ledger);
        var env=new MockEnvironment();service=spy(new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env));
        ReflectionTestUtils.setField(service,"enabled",true);ReflectionTestUtils.setField(service,"environment","sandbox");ReflectionTestUtils.setField(service,"clientId","fixture-client");ReflectionTestUtils.setField(service,"clientSecret","fixture-secret");ReflectionTestUtils.setField(service,"merchantId","MERCHANT123");ReflectionTestUtils.setField(service,"webhookId","fixture-webhook");service.infrastructure(inbox,risks);doReturn(gateway).when(service).gateway();
        facts=new PayPalFundsProofTest().new Facts();facts.capture.put("custom_id",order);
        when(gateway.retrieveCapture("CAPTURE1")).thenAnswer(c->{outsideTransaction();return facts.capture.deepCopy();});
        when(gateway.retrieveDispute("PP-D-1")).thenAnswer(c->{outsideTransaction();return facts.dispute.deepCopy();});
        when(gateway.financialReport(any(),any())).thenAnswer(c->{outsideTransaction();assertTrue(((java.time.Instant)c.getArgument(1)).isBefore(java.time.Instant.now().minusSeconds(3*3600)));return facts.report.deepCopy();});
        when(gateway.disputesForCapture("CAPTURE1")).thenAnswer(c->{outsideTransaction();return facts.list.deepCopy();});
        doAnswer(c->{tx.executeWithoutResult(t->((Runnable)c.getArgument(2)).run());return null;}).when(inbox).waitForProvider(any(),anyString(),any(),anyString());
        doAnswer(c->{tx.executeWithoutResult(t->{db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",owner);((Runnable)c.getArgument(2)).run();});return null;}).when(inbox).completeOwned(any(),anyString(),any());
    }
    @AfterEach void stop(){db.execute("SHUTDOWN");}
    private void outsideTransaction(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"supplier GET must never hold financial locks");}
    private PaymentInbox.Delivery event(String type,String resource){var d=new PaymentInbox.Delivery("PAYPAL_SANDBOX","evt-"+UUID.randomUUID(),type,PayPalFundsProofTest.JSON.writeValueAsString(Map.of("resourceId",resource)),"fixture-lease",1,"MERCHANT123","sandbox");when(inbox.claim("PAYPAL_SANDBOX","MERCHANT123","sandbox")).thenReturn(Optional.of(d));return d;}
    private Map<String,String> review(){risks.recordVerified("PAYPAL_SANDBOX","CAPTURE1","PAYMENT.CAPTURE.STATUS_RISK",order,"prior-event");db.update("UPDATE p0_credit_batch SET status='REVIEW' WHERE reference=?","payment:"+order);var row=db.queryForMap("SELECT id,last_event_id FROM payment_risk_case WHERE order_id=?",order);return Map.of((String)row.get("id"),(String)row.get("last_event_id"));}
    private void refund(){facts.capture.put("status","PARTIALLY_REFUNDED");facts.add(PayPalFundsProofTest.row("REFUND1","T1107","-1.98","S","CAPTURE1"));
        when(gateway.retrieveRefund("REFUND1")).thenAnswer(c->{outsideTransaction();return PayPalFundsProofTest.node(Map.of("id","REFUND1","status","COMPLETED","amount",PayPalFundsProofTest.money("1.98"),"supplementary_data",Map.of("related_ids",Map.of("capture_id","CAPTURE1"))));});}

    @Test void missingReportingPermissionLeavesLossPendingAndNeverPermanentlyDebitsOrClearsReview(){
        doThrow(new PayPalReportingGateway.Unavailable("REPORTING_PERMISSION_REQUIRED")).when(gateway).financialReport(any(),any());var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());
        verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-reporting_permission_required"));
        verify(ledger).paymentDispute("PAYPAL","MERCHANT123","sandbox","PP-D-1",owner,order,"CAPTURE1",990,"USD",java.time.Instant.parse(PayPalFundsProofTest.UPDATED).toEpochMilli(),"LOST",null);
        verify(ledger,never()).releasePaymentReview(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyMap(),anyString());
        assertEquals("OPEN",db.queryForObject("SELECT status FROM payment_risk_case WHERE order_id=?",String.class,order));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM p0_stripe_dispute_proof",Integer.class));
    }
    @Test void authoritativePartialLossPersistsNativePrincipalAndCallsActualLedgerPath(){
        facts.withdrawal();facts.nativeRow(1).set("transaction_amount",PayPalFundsProofTest.node(PayPalFundsProofTest.money("-3.30")));facts.dispute.set("fund_movements",PayPalFundsProofTest.JSON.valueToTree(List.of(PayPalFundsProofTest.movement("DEBIT","3.30"))));
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());
        verify(ledger).settlePayPalReportedLoss(eq("MERCHANT123"),eq("sandbox"),eq("PP-D-1"),eq(owner),eq(order),eq("CAPTURE1"),eq(990L),eq("USD"),eq(java.time.Instant.parse(PayPalFundsProofTest.UPDATED).toEpochMilli()),eq(330L),matches("[a-f0-9]{64}"));
        assertEquals(330,db.queryForObject("SELECT loss_minor FROM p0_stripe_dispute_proof WHERE provider='PAYPAL'",Long.class));assertEquals("WITHDRAWAL1",db.queryForObject("SELECT withdrawal_id FROM p0_stripe_dispute_proof",String.class));
        verify(inbox).completeOwned(eq(d),eq(owner),any());verify(inbox,never()).retry(any());verify(gateway,times(2)).retrieveCapture("CAPTURE1");
    }
    @Test void wonAfterRecordedLossRequiresNativeExactReturnAndPreservesProviderNamespace(){
        facts.withdrawal();event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());clearInvocations(ledger,inbox);
        facts.won();facts.add(PayPalFundsProofTest.row("RETURN1","T1202","9.90","S","WITHDRAWAL1"));facts.dispute.set("fund_movements",PayPalFundsProofTest.JSON.valueToTree(List.of(PayPalFundsProofTest.movement("DEBIT","9.90"),PayPalFundsProofTest.movement("CREDIT","9.90"))));
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());
        verify(ledger).compensateReturnedFunds(eq("PAYPAL"),eq("MERCHANT123"),eq("sandbox"),eq("RETURN1"),eq("PP-D-1"),eq(owner),eq(order),eq("CAPTURE1"),eq(990L),eq("USD"),matches("[a-f0-9]{64}"));
        verify(inbox).completeOwned(eq(d),eq(owner),any());verify(ledger,never()).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),eq("WON"),any());
    }
    @Test void recoveredCaptureAppliesIndependentlyVerifiedRefundAndReleasesExactRiskSnapshotInOneTransaction(){
        facts.noDisputes();refund();var snapshot=review();
        doAnswer(c->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(ledger).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        var d=event("PAYMENT.CAPTURE.STATUS_RISK","CAPTURE1");assertTrue(service.processNextEvent());
        var sequence=inOrder(ledger);sequence.verify(ledger).paymentRefund("PAYPAL","MERCHANT123","sandbox","REFUND1",owner,order,"CAPTURE1",198,"USD");
        sequence.verify(ledger).releasePaymentReview(eq("PAYPAL"),eq("MERCHANT123"),eq("sandbox"),eq(owner),eq(order),eq("CAPTURE1"),eq("USD"),eq(198L),eq(0L),eq(snapshot),matches("[a-f0-9]{64}"));
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM payment_risk_case WHERE order_id=?",String.class,order));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_risk_review",Integer.class));verify(inbox).completeOwned(eq(d),eq(owner),any());
    }
    @Test void mismatchedRefundResourceCannotReleaseEvenWhenReportingClaimsSuccess(){
        facts.noDisputes();refund();review();when(gateway.retrieveRefund("REFUND1")).thenReturn(PayPalFundsProofTest.node(Map.of("id","REFUND1","status","COMPLETED","amount",PayPalFundsProofTest.money("1.99"),"supplementary_data",Map.of("related_ids",Map.of("capture_id","CAPTURE1")))));
        var d=event("PAYMENT.CAPTURE.STATUS_RISK","CAPTURE1");assertTrue(service.processNextEvent());verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-financial_proof_incomplete"));
        verify(ledger,never()).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM payment_risk_review",Integer.class));
    }
    @Test void changedCaptureBetweenGetReadsRejectsOtherwiseValidLoss(){
        facts.withdrawal();var reads=new AtomicInteger();when(gateway.retrieveCapture("CAPTURE1")).thenAnswer(c->{outsideTransaction();var result=facts.capture.deepCopy();if(reads.incrementAndGet()>1)((ObjectNode)result).put("status","PARTIALLY_REFUNDED");return result;});
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-financial_proof_incomplete"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM p0_stripe_dispute_proof",Integer.class));verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),eq("LOST"),isNull());
    }
    @Test void failedLedgerMutationRollsBackPersistedPrincipalAndKeepsEventRetryable(){
        facts.withdrawal();doThrow(new IllegalStateException("isolated ledger failure")).when(ledger).settlePayPalReportedLoss(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),eq(990L),anyString());
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());verify(inbox).retry(d);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM p0_stripe_dispute_proof",Integer.class));
    }
    @Test void wonWithoutMerchantLossReleasesOnlyAfterCompleteReportAndKeepsPermanentAdjustmentAbsent(){
        facts.won();facts.noMovements();var snapshot=review();var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());
        verify(ledger).paymentDispute("PAYPAL","MERCHANT123","sandbox","PP-D-1",owner,order,"CAPTURE1",990,"USD",java.time.Instant.parse(PayPalFundsProofTest.UPDATED).toEpochMilli(),"WON",null);
        verify(ledger).releasePaymentReview(eq("PAYPAL"),eq("MERCHANT123"),eq("sandbox"),eq(owner),eq(order),eq("CAPTURE1"),eq("USD"),eq(0L),eq(0L),eq(snapshot),matches("[a-f0-9]{64}"));
        verify(ledger,never()).compensateReturnedFunds(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString());verify(inbox).completeOwned(eq(d),eq(owner),any());
    }
    @Test void finalWinWithoutNativeReturnNeverRestoresPreviouslyRecordedLoss(){
        facts.withdrawal();event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());clearInvocations(ledger,inbox);
        facts.won();var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-financial_proof_incomplete"));
        verify(ledger,never()).compensateReturnedFunds(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString());assertEquals(990,db.queryForObject("SELECT loss_minor FROM p0_stripe_dispute_proof",Long.class));
    }
    @Test void existingHumanHoldIsPreservedAndNeverStartsAutomaticFinancialReporting(){
        facts.noDisputes();var cases=review();cases.forEach((id,event)->risks.resolveCase(id,event,"HOLD","human-reviewer","Keep held until separate business evidence has been reviewed"));
        var d=event("PAYMENT.CAPTURE.STATUS_RISK","CAPTURE1");assertTrue(service.processNextEvent());
        verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-manual_hold_requires_review"));verify(gateway,never()).financialReport(any(),any());
        assertEquals("HOLD",db.queryForObject("SELECT decision FROM payment_risk_case WHERE id=?",String.class,cases.keySet().iterator().next()));
        verify(ledger,never()).releasePaymentReview(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyMap(),anyString());
    }
    @Test void humanHoldCreatedDuringProviderReadsPreventsAnyAutomaticLossOrRelease(){
        facts.withdrawal();var cases=review();var entry=cases.entrySet().iterator().next();
        doAnswer(c->{outsideTransaction();risks.resolveCase(entry.getKey(),entry.getValue(),"HOLD","human-reviewer","Manual hold committed while authenticated reporting GET was in progress");return facts.report.deepCopy();}).when(gateway).financialReport(any(),any());
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());
        verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-manual_hold_requires_review"));
        verify(ledger,never()).settlePayPalReportedLoss(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),anyLong(),anyString());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM p0_stripe_dispute_proof",Integer.class));
        assertEquals("HOLD",db.queryForObject("SELECT decision FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));assertEquals("human-reviewer",db.queryForObject("SELECT reviewed_by FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));
    }
    @Test void changedHumanDecisionAndStatusDuringGetInvalidateTheOriginalReviewSnapshot(){
        facts.withdrawal();var cases=review();var entry=cases.entrySet().iterator().next();
        doAnswer(c->{outsideTransaction();risks.resolveCase(entry.getKey(),entry.getValue(),"RELEASE","human-reviewer","Independent human decision committed during provider report retrieval");return facts.report.deepCopy();}).when(gateway).financialReport(any(),any());
        var d=event("CUSTOMER.DISPUTE.RESOLVED","PP-D-1");assertTrue(service.processNextEvent());verify(inbox).waitForProvider(eq(d),eq(owner),any(),eq("paypal-funds-review_case_changed"));
        verify(ledger,never()).settlePayPalReportedLoss(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyLong(),anyLong(),anyString());
        assertEquals("CLOSED",db.queryForObject("SELECT status FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));assertEquals("human-reviewer",db.queryForObject("SELECT reviewed_by FROM payment_risk_case WHERE id=?",String.class,entry.getKey()));
    }
}
