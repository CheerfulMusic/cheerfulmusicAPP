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


    private void transientFencedNewOrderCompletionRecovery(RuntimeException failure){
        db.execute("CREATE TABLE p0_credit_writer_policy(id INT PRIMARY KEY,protocol INT)");db.update("INSERT INTO p0_credit_writer_policy VALUES(1,3)");
        db.execute("CREATE TABLE p0_credit_cutover_state(id INT PRIMARY KEY,status VARCHAR(24),activated_at TIMESTAMP(6))");
        db.update("INSERT INTO p0_credit_cutover_state VALUES(1,'ACTIVE',?)",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)));
        db.update("UPDATE payment_order SET status='PENDING' WHERE id=?",order);ReflectionTestUtils.setField(service,"legacyOrdersReadOnly",true);
        var paid=new PayPalCheckoutGateway.Snapshot("REMOTE1",order,"COMPLETED","MERCHANT123","CAPTURE1","COMPLETED",new java.math.BigDecimal("9.90"),"USD");
        when(gateway.retrieve("REMOTE1")).thenThrow(failure).thenReturn(paid);
        var d=event("PAYMENT.CAPTURE.COMPLETED","CAPTURE1");assertTrue(service.processNextEvent());verify(inbox).retry(d);verify(inbox,never()).markManual(any(),anyString());
        assertEquals("PENDING",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,order));verify(ledger,never()).credit(anyString(),anyLong(),anyString());
        assertTrue(service.processNextEvent());verify(inbox).completeOwned(eq(d),eq(owner),any());
        verify(ledger,times(1)).credit(owner,100,"payment:"+order);verify(ledger,times(1)).bindPayment("PAYPAL","MERCHANT123","sandbox",owner,order,"CAPTURE1","USD");
        assertEquals("PAID",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,order));
        verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void fencedNewOrderWithLegacyBoundaryRetriesProvider503BeforeAnyCredit(){transientFencedNewOrderCompletionRecovery(new PayPalCheckoutGateway.ProviderHttpException(503));}
    @Test void fencedNewOrderWithLegacyBoundaryRetriesTransportFailureBeforeAnyCredit(){transientFencedNewOrderCompletionRecovery(new IllegalStateException("synthetic unavailable transport"));}


    /** Real scoped inbox/transactions and authoritative proof routing; the ledger boundary remains a mock.
     * This verifies detection/queueing of distinct refund facts, not a replacement for InnoDB balance acceptance. */
    @Test void aSecondReportedPartialRefundWithUnchangedCaptureCreatesOneNewObservationAndSameProofCannotReopenReviewedCase(){
        db.execute("ALTER TABLE payment_order ADD reconcile_after TIMESTAMP(6)");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund",Long.class));
        db.execute("DROP TABLE p0_credit_refund");
        db.execute("CREATE TABLE p0_credit_refund(provider VARCHAR(24),merchant VARCHAR(128),environment VARCHAR(16),external_id VARCHAR(128),owner_id VARCHAR(36),order_id VARCHAR(36),capture_id VARCHAR(128),amount_minor BIGINT,currency VARCHAR(3),PRIMARY KEY(provider,merchant,environment,external_id))");
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),event_type VARCHAR(96),payload VARCHAR(30000),payload_hash VARCHAR(64),status VARCHAR(24),attempts INT,next_attempt_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,lease_token VARCHAR(36),lease_until TIMESTAMP,last_error VARCHAR(128),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        db.execute("CREATE TABLE payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),attempt INT,result VARCHAR(24),evidence_id VARCHAR(512),created_at TIMESTAMP)");
        db.execute("CREATE TABLE payment_inbox_review(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),reviewer VARCHAR(128),reason VARCHAR(1000),action VARCHAR(24),created_at TIMESTAMP)");
        inbox=new PaymentInbox(db,tx);service.infrastructure(inbox,risks);
        // Record only the production method's identity/amount arguments, without reimplementing credit arithmetic.
        doAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            var expected=Map.<String,Object>of("provider",call.getArgument(0),"merchant",call.getArgument(1),"environment",call.getArgument(2),"external_id",call.getArgument(3),"owner_id",call.getArgument(4),"order_id",call.getArgument(5),"capture_id",call.getArgument(6),"amount_minor",((Number)call.getArgument(7)).longValue(),"currency",call.getArgument(8));
            try{db.update("INSERT INTO p0_credit_refund VALUES(?,?,?,?,?,?,?,?,?)",call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6),call.getArgument(7),call.getArgument(8));}
            catch(org.springframework.dao.DuplicateKeyException duplicate){assertEquals(expected,db.queryForMap("SELECT * FROM p0_credit_refund WHERE provider=? AND merchant=? AND environment=? AND external_id=?",call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3)));}
            return null;
        }).when(ledger).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());db.update("UPDATE p0_credit_batch SET status='ACTIVE' WHERE reference=?","payment:"+order);return null;})
            .when(ledger).releasePaymentReview(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyMap(),anyString());
        facts.noDisputes();refund();var risk=review().entrySet().iterator().next();
        var capture=facts.capture.deepCopy();
        service.reconcileOrders();assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class));
        assertTrue(service.processNextEvent());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE status='DONE'",Long.class));
        assertEquals(198,db.queryForObject("SELECT SUM(amount_minor) FROM p0_credit_refund WHERE order_id=?",Long.class,order));
        verify(ledger).paymentRefund("PAYPAL","MERCHANT123","sandbox","REFUND1",owner,order,"CAPTURE1",198,"USD");
        risks.resolveCase(risk.getKey(),risk.getValue(),"RELEASE","offline-reviewer","Synthetic reporting and already applied refund were independently checked");
        var humanReviewed=db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey());
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertFalse(service.processNextEvent());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class));
        assertEquals(humanReviewed,db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey()),"Unchanged funds must not reopen or overwrite the human decision");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund",Long.class));
        // A second successful refund can be visible in Reporting without changing capture status/update_time.
        facts.add(PayPalFundsProofTest.row("REFUND2","T1107","-0.99","S","CAPTURE1"));
        when(gateway.retrieveRefund("REFUND2")).thenAnswer(call->{outsideTransaction();return PayPalFundsProofTest.node(Map.of("id","REFUND2","status","COMPLETED","amount",PayPalFundsProofTest.money("0.99"),"supplementary_data",Map.of("related_ids",Map.of("capture_id","CAPTURE1"))));});
        assertEquals(capture,facts.capture);assertEquals("PARTIALLY_REFUNDED",facts.capture.path("status").asString());
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class),"New native refund facts must create a different observation even when capture bytes are unchanged");
        assertTrue(service.processNextEvent());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE status='DONE'",Long.class));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM p0_credit_refund",Long.class));assertEquals(297,db.queryForObject("SELECT SUM(amount_minor) FROM p0_credit_refund WHERE order_id=?",Long.class,order));
        verify(ledger,times(1)).paymentRefund("PAYPAL","MERCHANT123","sandbox","REFUND2",owner,order,"CAPTURE1",99,"USD");
        var reconciled=db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey());assertEquals("CLOSED",reconciled.get("status"));
        var refundRows=db.queryForList("SELECT * FROM p0_credit_refund ORDER BY external_id");
        // Moving a report watermark is not a new money fact and must not produce another event.
        facts.report.put("last_refreshed_datetime","2026-11-13T00:01:00Z");
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertFalse(service.processNextEvent());assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class));
        assertEquals(refundRows,db.queryForList("SELECT * FROM p0_credit_refund ORDER BY external_id"));
        assertEquals(reconciled,db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey()));assertEquals(capture,facts.capture);
        for(String payload:db.query("SELECT payload FROM payment_webhook_inbox WHERE status='DONE'",(rs,n)->rs.getString(1))){
            var observation=PayPalFundsProofTest.JSON.readTree(payload);
            assertEquals("PARTIALLY_REFUNDED",observation.path("captureStatus").asString());assertEquals("FUNDS_PROOF",observation.path("observationKind").asString());
        }
        // Losing Reporting access after successful proof processing is not a new funds fact.
        // A fresh fallback identity must not reopen the completed, manually reviewed observation.
        doThrow(new PayPalReportingGateway.Unavailable("REPORTING_PERMISSION_REQUIRED")).when(gateway).financialReport(any(),any());
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class),"Reporting degradation must not create a new status-only observation after an identical proof observation completed");
        assertFalse(service.processNextEvent());assertEquals(reconciled,db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey()));
        verify(ledger,never()).holdPaymentForReview(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        assertEquals(refundRows,db.queryForList("SELECT * FROM p0_credit_refund ORDER BY external_id"));
        // The prior PARTIAL proof does not suppress a genuinely different full-refund status.
        // Incomplete funds proof keeps the new risk pending without manufacturing an adjustment.
        facts.capture.put("status","REFUNDED");
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class));
        assertTrue(service.processNextEvent());assertEquals("REVIEW",db.queryForObject("SELECT status FROM payment_risk_case WHERE id=?",String.class,risk.getKey()));
        var fullRefundRisk=db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey());
        assertNotEquals(reconciled.get("last_event_id"),fullRefundRisk.get("last_event_id"));
        assertEquals(PayPalFundsProofTest.JSON.writeValueAsString(new TreeMap<>(Map.of("resourceId","CAPTURE1","orderId","REMOTE1"))),db.queryForObject("SELECT payload FROM payment_webhook_inbox WHERE event_id=?",String.class,fullRefundRisk.get("last_event_id")),"Old status-only pointer payload bytes must remain compatible");
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE status='DONE'",Long.class));
        verify(ledger,times(1)).holdPaymentForReview("PAYPAL","MERCHANT123","sandbox",owner,order,"CAPTURE1","USD",(String)fullRefundRisk.get("last_event_id"));
        assertEquals(refundRows,db.queryForList("SELECT * FROM p0_credit_refund ORDER BY external_id"));
        // REVERSED remains a separate status risk, independent of any earlier funds proof.
        facts.capture.put("status","REVERSED");
        db.update("UPDATE payment_order SET reconcile_after=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",order);
        service.reconcileOrders();assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox",Long.class));
        assertTrue(service.processNextEvent());var reversedRisk=db.queryForMap("SELECT * FROM payment_risk_case WHERE id=?",risk.getKey());
        assertEquals("REVIEW",reversedRisk.get("status"));assertNotEquals(fullRefundRisk.get("last_event_id"),reversedRisk.get("last_event_id"));
        verify(ledger,times(1)).holdPaymentForReview("PAYPAL","MERCHANT123","sandbox",owner,order,"CAPTURE1","USD",(String)reversedRisk.get("last_event_id"));
        assertEquals(refundRows,db.queryForList("SELECT * FROM p0_credit_refund ORDER BY external_id"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE status='DONE'",Long.class));
        verify(gateway,never()).capture(anyString(),anyString());
    }

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
