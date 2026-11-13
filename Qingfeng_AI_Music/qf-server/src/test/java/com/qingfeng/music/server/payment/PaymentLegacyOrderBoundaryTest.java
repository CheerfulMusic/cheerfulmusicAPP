package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 service/SQL contract only; no supplier requests, production data, or MySQL acceptance. */
class PaymentLegacyOrderBoundaryTest {
    JdbcTemplate db;TransactionTemplate tx;PaymentInbox inbox;JdbcFencedCreditsLedger ledger;
    PayPalPaymentService paypal;PayPalCheckoutGateway paypalGateway;PaymentService airwallex;PaymentGateway airwallexGateway;
    final ObjectMapper json=new ObjectMapper();
    static final String OWNER="owner",ORDER="old-paypal",AIR="old-airwallex",MERCHANT="MERCHANT123";
    Timestamp activation;
    @BeforeEach void syntheticDatabase(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:legacy_payment_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));inbox=new PaymentInbox(db,tx);ledger=mock(JdbcFencedCreditsLedger.class);
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");db.update("INSERT INTO creator VALUES(?)",OWNER);
        db.execute("CREATE TABLE p0_credit_writer_policy(id INT PRIMARY KEY,protocol INT)");db.update("INSERT INTO p0_credit_writer_policy VALUES(1,3)");
        db.execute("CREATE TABLE p0_credit_cutover_state(id INT PRIMARY KEY,status VARCHAR(24),activated_at TIMESTAMP(6))");
        activation=Timestamp.from(Instant.now().minusSeconds(30));db.update("INSERT INTO p0_credit_cutover_state VALUES(1,'ACTIVE',?)",activation);
        db.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),package_id VARCHAR(80),amount DECIMAL(18,2),currency VARCHAR(3),credits BIGINT,status VARCHAR(24),created_at TIMESTAMP(6),provider VARCHAR(24),provider_link_id VARCHAR(128),provider_capture_id VARCHAR(128),failure_code VARCHAR(80),reconcile_after TIMESTAMP(6))");
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),event_type VARCHAR(96),payload VARCHAR(30000),payload_hash VARCHAR(64),status VARCHAR(24),attempts INT,next_attempt_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP,lease_token VARCHAR(36),lease_until TIMESTAMP,last_error VARCHAR(128),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        db.execute("CREATE TABLE payment_inbox_attempt(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),attempt INT,result VARCHAR(24),evidence_id VARCHAR(512),created_at TIMESTAMP)");
        db.execute("CREATE TABLE payment_inbox_review(id VARCHAR(36) PRIMARY KEY,provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),reviewer VARCHAR(128),reason VARCHAR(1000),action VARCHAR(24),created_at TIMESTAMP)");
        insert(ORDER,"PAYPAL_SANDBOX",Timestamp.from(activation.toInstant().minusSeconds(1)),"PENDING","REMOTE123");
        insert(AIR,"AIRWALLEX",Timestamp.from(activation.toInstant().minusSeconds(1)),"PENDING","LINK123");
        var env=new MockEnvironment();paypal=spy(new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env));paypalGateway=mock(PayPalCheckoutGateway.class);
        for(var field:Map.<String,Object>of("enabled",true,"legacyOrdersReadOnly",true,"environment","sandbox","clientId","offline","clientSecret","offline","merchantId",MERCHANT,"webhookId","offline").entrySet())ReflectionTestUtils.setField(paypal,field.getKey(),field.getValue());
        paypal.infrastructure(inbox,mock(PaymentRiskService.class));doReturn(paypalGateway).when(paypal).gateway();when(paypalGateway.verifyWebhook(any(),any())).thenReturn(true);
        airwallexGateway=mock(PaymentGateway.class);airwallex=new PaymentService(db,tx,ledger,airwallexGateway,new PaymentCatalog(env));airwallex.inbox(inbox);
        ReflectionTestUtils.setField(airwallex,"enabled",true);ReflectionTestUtils.setField(airwallex,"legacyOrdersReadOnly",true);when(airwallexGateway.configured()).thenReturn(true);
    }
    @AfterEach void close(){db.execute("SHUTDOWN");}
    void insert(String id,String provider,Timestamp created,String status,String remote){db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_link_id) VALUES(?,?,'starter-USD',9.90,'USD',100,?,?,?,?)",id,OWNER,status,created,provider,remote);}
    Map<String,Object> row(String order){return db.queryForMap("SELECT * FROM payment_order WHERE id=?",order);}
    long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    String state(String event){return db.queryForObject("SELECT status FROM payment_webhook_inbox WHERE event_id=?",String.class,event);}
    byte[] completed(String event,String remote){return json.writeValueAsBytes(Map.of("id",event,"event_type","PAYMENT.CAPTURE.COMPLETED","resource",Map.of("id","CAPTURE123","supplementary_data",Map.of("related_ids",Map.of("order_id",remote)))));}

    @Test void disabledBoundaryNeverNeedsCutoverTables(){
        var empty=mock(JdbcTemplate.class);assertFalse(new PaymentLegacyOrderBoundary(empty,false).readOnly(Map.of()));verifyNoInteractions(empty);
    }
    @Test void equalityAndUnknownTimestampsFailClosedAndNewOrderCanRemainMutable(){
        var boundary=new PaymentLegacyOrderBoundary(db,true);assertTrue(boundary.readOnly(row(ORDER)));
        db.update("UPDATE payment_order SET created_at=? WHERE id=?",activation,ORDER);assertTrue(boundary.readOnly(row(ORDER)));
        db.update("UPDATE payment_order SET created_at=? WHERE id=?",Timestamp.from(activation.toInstant().plusNanos(1000)),ORDER);assertFalse(boundary.readOnly(row(ORDER)));
        db.update("UPDATE payment_order SET created_at=NULL WHERE id=?",ORDER);assertThrows(ResponseStatusException.class,()->boundary.readOnly(row(ORDER)));
    }
    @Test void missingOrInactiveActivationAndStaleProtocolCannotEnableMutations(){
        var boundary=new PaymentLegacyOrderBoundary(db,true);
        db.update("UPDATE p0_credit_cutover_state SET activated_at=NULL");assertThrows(ResponseStatusException.class,()->boundary.requireMutable(row(ORDER)));
        db.update("UPDATE p0_credit_cutover_state SET activated_at=?,status='PREPARING'",activation);assertThrows(ResponseStatusException.class,()->boundary.requireMutable(row(ORDER)));
        db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE'");db.update("UPDATE p0_credit_writer_policy SET protocol=1");assertThrows(ResponseStatusException.class,()->boundary.requireMutable(row(ORDER)));
    }
    @Test void paypalOldReadsStayLocalAndEveryExplicitMutationRejectsBeforeProviderUse(){
        var before=row(ORDER);assertEquals("PENDING",paypal.order(OWNER,ORDER).get("status"));
        assertThrows(PaymentLegacyOrderBoundary.ReadOnlyOrder.class,()->paypal.capture(OWNER,ORDER));
        assertThrows(PaymentLegacyOrderBoundary.ReadOnlyOrder.class,()->paypal.recover(OWNER,ORDER,"REMOTE123"));
        assertThrows(PaymentLegacyOrderBoundary.ReadOnlyOrder.class,()->paypal.resume(OWNER,ORDER));
        assertThrows(PaymentLegacyOrderBoundary.ReadOnlyOrder.class,()->paypal.close(OWNER,ORDER));
        assertEquals(before,row(ORDER));verifyNoInteractions(paypalGateway,ledger);
    }
    @Test void paypalSchedulersLeaveEveryOldStateAndDueTimeUntouched(){
        db.update("UPDATE payment_order SET created_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP) WHERE id=?",ORDER);
        for(String status:List.of("CREATING","PENDING","CANCELLED","PAID")){
            db.update("UPDATE payment_order SET status=?,provider_link_id=?,provider_capture_id=? WHERE id=?",status,status.equals("CREATING")?null:"REMOTE123",status.equals("PAID")?"CAPTURE123":null,ORDER);
            var before=row(ORDER);paypal.reconcileOrders();assertEquals(before,row(ORDER));
        }
        verifyNoInteractions(paypalGateway,ledger);assertEquals(0,count("payment_webhook_inbox"));
    }
    @Test void paypalVerifiedDuplicateCompletionIsPreservedAsOneManualPointerWithoutOldOrderOrCreditChanges(){
        var before=row(ORDER);byte[] body=completed("WH-LEGACY-123","REMOTE123");paypal.webhook(null,body);paypal.webhook(null,body);
        assertEquals("PENDING",state("WH-LEGACY-123"));assertTrue(paypal.processNextEvent());
        assertEquals("MANUAL",state("WH-LEGACY-123"));assertFalse(paypal.processNextEvent());
        assertEquals(before,row(ORDER));assertEquals(1,count("payment_webhook_inbox"));assertEquals(1,count("payment_inbox_attempt"));
        assertEquals("LEGACY_ORDER_READ_ONLY",db.queryForObject("SELECT evidence_id FROM payment_inbox_attempt",String.class));
        verify(paypalGateway,times(2)).verifyWebhook(any(),eq(body));verify(paypalGateway,never()).retrieve(anyString());verify(paypalGateway,never()).capture(anyString(),anyString());verifyNoInteractions(ledger);
    }
    @Test void badPaypalSignatureCannotProduceAManualPointer(){
        when(paypalGateway.verifyWebhook(any(),any())).thenReturn(false);assertThrows(ResponseStatusException.class,()->paypal.webhook(null,completed("WH-BAD-123","REMOTE123")));
        assertEquals(0,count("payment_webhook_inbox"));assertEquals(0,count("payment_inbox_attempt"));verifyNoInteractions(ledger);
    }
    @Test void anUncorrelatedPaypalCompletionRemainsUnknownManualRatherThanDoneOrUnpaid(){
        paypal.webhook(null,completed("WH-UNKNOWN-123","REMOTE_UNKNOWN"));when(paypalGateway.retrieve("REMOTE_UNKNOWN")).thenThrow(new IllegalStateException("synthetic unknown provider result"));
        assertTrue(paypal.processNextEvent());assertEquals("MANUAL",state("WH-UNKNOWN-123"));assertEquals("PENDING",row(ORDER).get("status"));verifyNoInteractions(ledger);
    }
    @Test void airwallexOldReadAndRecoveryNeverRetrieveOrSettleTheHistoricalOrder(){
        var before=row(AIR);var owner=new CreatorPrincipal(OWNER);assertEquals("PENDING",airwallex.order(owner,AIR).get("status"));
        assertThrows(PaymentLegacyOrderBoundary.ReadOnlyOrder.class,()->airwallex.recover(owner,AIR,"LINK123"));
        assertEquals(before,row(AIR));verify(airwallexGateway,never()).retrieve(anyString());verifyNoInteractions(ledger);
    }
    @Test void airwallexVerifiedDuplicateLegacyNotificationIsArchivedOnceAndUnknownOrdersAreAlsoPreserved(){
        var before=row(AIR);byte[] body="synthetic verified notification".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(airwallexGateway.notification("offline-time","offline-signature",body)).thenReturn(new PaymentGateway.Notification(AIR,"LINK123"));
        airwallex.webhook("offline-time","offline-signature",body);airwallex.webhook("offline-time","offline-signature",body);
        assertEquals(before,row(AIR));assertEquals(1,count("payment_webhook_inbox"));assertEquals(1,count("payment_inbox_attempt"));
        assertEquals("MANUAL",db.queryForObject("SELECT status FROM payment_webhook_inbox",String.class));
        when(airwallexGateway.notification("offline-time","offline-signature",new byte[]{1})).thenReturn(new PaymentGateway.Notification("unknown-order","UNKNOWN_LINK"));
        airwallex.webhook("offline-time","offline-signature",new byte[]{1});assertEquals(2,count("payment_webhook_inbox"));assertEquals(2,count("payment_inbox_attempt"));
        verify(airwallexGateway,never()).retrieve(anyString());verifyNoInteractions(ledger);
    }
    @Test void expiredManualLeaseCannotReplaceAnotherWorkersDecisionOrWriteAudit(){
        inbox.acceptVerified("PAYPAL_SANDBOX",MERCHANT,"sandbox","evt_lease","PAYMENT.CAPTURE.COMPLETED","{}");
        var claimed=inbox.claim("PAYPAL_SANDBOX",MERCHANT,"sandbox").orElseThrow();
        db.update("UPDATE payment_webhook_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)");
        assertThrows(IllegalStateException.class,()->inbox.markManual(claimed,"LEGACY_ORDER_READ_ONLY"));
        assertEquals("PROCESSING",state("evt_lease"));assertEquals(0,count("payment_inbox_attempt"));
    }
}
