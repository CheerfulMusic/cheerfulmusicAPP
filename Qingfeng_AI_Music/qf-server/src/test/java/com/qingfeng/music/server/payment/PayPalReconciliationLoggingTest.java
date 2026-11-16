package com.qingfeng.music.server.payment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Operational logging/routing with synthetic provider failures only; no supplier or MySQL acceptance. */
class PayPalReconciliationLoggingTest {
    @Test void oneBoundedScanAggregatesPendingCaptureAndDeferredProofFailuresWithoutSensitiveTextOrFinancialWrites(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:paypal_reconcile_logging_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");var db=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),package_id VARCHAR(80),amount DECIMAL(18,2),currency VARCHAR(3),credits BIGINT,status VARCHAR(24),created_at TIMESTAMP(6),provider VARCHAR(24),provider_link_id VARCHAR(128),provider_capture_id VARCHAR(128),failure_code VARCHAR(80),reconcile_after TIMESTAMP(6))");
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),event_type VARCHAR(96),payload VARCHAR(30000),payload_hash VARCHAR(64),status VARCHAR(24),attempts INT,next_attempt_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,lease_token VARCHAR(36),lease_until TIMESTAMP,last_error VARCHAR(128),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        db.execute("CREATE TABLE payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),attempt INT,result VARCHAR(24),evidence_id VARCHAR(512),created_at TIMESTAMP)");
        db.execute("CREATE TABLE payment_inbox_review(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),reviewer VARCHAR(128),reason VARCHAR(1000),action VARCHAR(24),created_at TIMESTAMP)");
        String owner=UUID.randomUUID().toString();var orders=new java.util.LinkedHashMap<String,String>();
        for(int n=1;n<=5;n++){
            String id=UUID.randomUUID().toString(),remote="REMOTE"+n;orders.put(remote,id);
            db.update("INSERT INTO payment_order VALUES(?,?,'starter-USD',9.90,'USD',100,?,CURRENT_TIMESTAMP,'PAYPAL_SANDBOX',?,?,NULL,NULL)",id,owner,n<=2?"PENDING":"PAID",remote,n<=2?null:"CAPTURE"+n);
        }
        var ledger=mock(JdbcFencedCreditsLedger.class);var inbox=spy(new PaymentInbox(db,tx));var gateway=mock(PayPalCheckoutGateway.class);
        var env=new MockEnvironment();var service=spy(new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env));
        ReflectionTestUtils.setField(service,"enabled",true);ReflectionTestUtils.setField(service,"environment","sandbox");
        ReflectionTestUtils.setField(service,"clientId","fixture-client");ReflectionTestUtils.setField(service,"clientSecret","fixture-secret");
        ReflectionTestUtils.setField(service,"merchantId","MERCHANT123");ReflectionTestUtils.setField(service,"webhookId","fixture-webhook");
        service.infrastructure(inbox,mock(PaymentRiskService.class));doReturn(gateway).when(service).gateway();
        String sensitive="sk_live_private whsec_private owner@example.test\nprivate-report-and-order-payload";
        when(gateway.retrieve("REMOTE1")).thenThrow(new IllegalStateException(sensitive));
        when(gateway.retrieve("REMOTE2")).thenThrow(new IllegalStateException(sensitive));
        var deferred=PayPalFundsProofTest.node(Map.of("id","CAPTURE3","status","PARTIALLY_REFUNDED","amount",PayPalFundsProofTest.money("9.90"),"create_time",PayPalFundsProofTest.CREATED,"supplementary_data",Map.of("related_ids",Map.of("order_id","REMOTE3"))));
        when(gateway.retrieveCapture("CAPTURE3")).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return deferred.deepCopy();});
        // Anonymous exception has no simple class name: the log must use the bounded safe fallback.
        when(gateway.retrieveCapture("CAPTURE4")).thenThrow(new RuntimeException(sensitive){});
        when(gateway.retrieveCapture("CAPTURE5")).thenReturn(PayPalFundsProofTest.node(Map.of("id","CAPTURE5","status","COMPLETED","amount",PayPalFundsProofTest.money("9.90"),"supplementary_data",Map.of("related_ids",Map.of("order_id","REMOTE5")))));
        when(gateway.financialReport(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());throw new PayPalReportingGateway.Unavailable(sensitive);});
        var logger=(Logger)LoggerFactory.getLogger(PayPalPaymentService.class);var previous=logger.getLevel();var warnings=new ListAppender<ILoggingEvent>();
        warnings.start();logger.addAppender(warnings);logger.setLevel(Level.WARN);
        try{
            assertDoesNotThrow(service::reconcileOrders);
            assertEquals(1,warnings.list.size(),"A scan with several failed rows emits one aggregated warning");
            var warning=warnings.list.get(0);assertEquals(Level.WARN,warning.getLevel());assertNull(warning.getThrowableProxy());
            assertEquals("payment_reconciliation_incomplete provider=PAYPAL_SANDBOX failures={CAPTURE_LOOKUP/RuntimeException=1, FINANCIAL_PROOF_DEFERRED/Unavailable=1, ORDER_LOOKUP/IllegalStateException=2}",warning.getFormattedMessage());
            String message=warning.getFormattedMessage();
            for(String secret:new String[]{"sk_live_private","whsec_private","owner@example.test","private-report-and-order-payload",owner,"CAPTURE3","REMOTE3","\n"})assertFalse(message.contains(secret));
            for(String id:orders.values())assertFalse(message.contains(id));
            verify(inbox,times(1)).acceptVerified(eq("PAYPAL_SANDBOX"),eq("MERCHANT123"),eq("sandbox"),startsWith("reconcile-"),eq("PAYMENT.CAPTURE.STATUS_RISK"),anyString());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM payment_webhook_inbox WHERE provider='PAYPAL_SANDBOX' AND merchant_scope='MERCHANT123' AND env_scope='sandbox' AND status='PENDING' AND attempts=0",Integer.class),"A first risk observation with unavailable proof must really be durable");
            assertEquals(PayPalFundsProofTest.JSON.writeValueAsString(new java.util.TreeMap<>(Map.of("resourceId","CAPTURE3","orderId","REMOTE3"))),db.queryForObject("SELECT payload FROM payment_webhook_inbox",String.class),"First fallback retains the historical two-field pointer payload");
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE status='PENDING'",Integer.class));
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE status='PAID'",Integer.class));
            verifyNoInteractions(ledger);verify(gateway,never()).capture(anyString(),anyString());
            // Rows remain scheduled for retry; healthy subsequent reads do not keep warning or grant credits.
            db.update("UPDATE payment_order SET reconcile_after=NULL");
            for(int n=1;n<=2;n++){
                String remote="REMOTE"+n;
                doReturn(new PayPalCheckoutGateway.Snapshot(remote,orders.get(remote),"CREATED","MERCHANT123","","",new BigDecimal("9.90"),"USD")).when(gateway).retrieve(remote);
            }
            deferred.put("status","COMPLETED");
            doReturn(PayPalFundsProofTest.node(Map.of("id","CAPTURE4","status","COMPLETED","amount",PayPalFundsProofTest.money("9.90"),"supplementary_data",Map.of("related_ids",Map.of("order_id","REMOTE4"))))).when(gateway).retrieveCapture("CAPTURE4");
            assertDoesNotThrow(service::reconcileOrders);assertEquals(1,warnings.list.size(),"Recovery must stop the incomplete-scan warning");
            verifyNoInteractions(ledger);verify(gateway,times(2)).retrieve("REMOTE1");verify(gateway,times(2)).retrieve("REMOTE2");
        }finally{
            logger.detachAppender(warnings);warnings.stop();logger.setLevel(previous);db.execute("SHUTDOWN");
        }
    }
}
