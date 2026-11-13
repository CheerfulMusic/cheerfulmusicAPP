package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 order/Inbox transactions with mocked supplier facts and entitlement writer.
 * Not InnoDB concurrency, Sandbox payment, or Live acceptance. No network requests. */
class StripeOrderReconciliationTest {
    static final String PROVIDER="STRIPE_SANDBOX",MERCHANT="acct_reconcilefixture1234";
    final ObjectMapper json=new ObjectMapper();
    JdbcTemplate db;TransactionTemplate tx;PaymentInbox inbox;StripePaymentService service;
    StripeCheckoutGateway gateway;JdbcFencedCreditsLedger ledger;
    final Map<String,JsonNode> facts=new HashMap<>();
    String createdOrder;boolean failCreate,matureCreation;
    @BeforeEach void start(){
        var ds=new DriverManagerDataSource("jdbc:h2:mem:reconcile_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver");db=spy(new JdbcTemplate(ds));tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");
        db.execute("CREATE TABLE creator_google_identity(creator_id VARCHAR(36))");db.execute("CREATE TABLE creator_oidc_identity(creator_id VARCHAR(36))");
        db.execute("CREATE TABLE payment_order(id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36),package_id VARCHAR(80),amount DECIMAL(18,2),currency VARCHAR(3),credits BIGINT,status VARCHAR(24),created_at TIMESTAMP(6),provider VARCHAR(24),provider_link_id VARCHAR(128) UNIQUE,provider_capture_id VARCHAR(128),failure_code VARCHAR(80),reconcile_after TIMESTAMP(6))");
        db.execute("CREATE TABLE payment_webhook_inbox(provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),event_type VARCHAR(96),payload TEXT,payload_hash CHAR(64),status VARCHAR(16),attempts INT,next_attempt_at TIMESTAMP(6),lease_token VARCHAR(36),lease_until TIMESTAMP(6),last_error VARCHAR(128),created_at TIMESTAMP(6),updated_at TIMESTAMP(6),PRIMARY KEY(provider,merchant_scope,env_scope,event_id))");
        db.execute("CREATE TABLE payment_inbox_attempt(id VARCHAR(36),provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),attempt INT,result VARCHAR(16),evidence_id VARCHAR(512),created_at TIMESTAMP(6))");
        db.execute("CREATE TABLE payment_inbox_review(id VARCHAR(36),provider VARCHAR(24),merchant_scope VARCHAR(128),env_scope VARCHAR(16),event_id VARCHAR(128),reviewer VARCHAR(128),reason VARCHAR(1000),action VARCHAR(16),created_at TIMESTAMP(6))");
        for(var check:CreditSchemaReadiness.CHECKS.values())doReturn(check.expected()).when(db).queryForObject(check.sql(),Integer.class);
        gateway=mock(StripeCheckoutGateway.class);ledger=mock(JdbcFencedCreditsLedger.class);inbox=new PaymentInbox(db,tx);
        when(gateway.configured()).thenReturn(true);when(gateway.provider()).thenReturn(PROVIDER);when(gateway.environment()).thenReturn("sandbox");when(gateway.merchantId()).thenReturn(MERCHANT);when(gateway.isLive()).thenReturn(false);
        when(gateway.createSession(anyString(),anyString(),any(BigDecimal.class),anyString())).thenAnswer(c->{
            String order=c.getArgument(0);createdOrder=order;
            assertEquals(1,count("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id=?","reconcile-order-"+order),"Recovery pointer must precede POST");
            var fact=session("cs_test_"+order.replace("-",""),order,c.getArgument(2),c.getArgument(3),"open","unpaid");facts.put(fact.path("id").asString(),fact);
            if(failCreate)throw new StripeCheckoutGateway.StripeRemoteException(0);return fact;
        });
        when(gateway.retrieveSession(anyString())).thenAnswer(c->{var fact=facts.get(c.getArgument(0));if(fact==null)throw new StripeCheckoutGateway.StripeRemoteException(404);return fact;});
        when(gateway.sessionsCreatedBetween(any(),nullable(String.class))).thenAnswer(c->list(false,List.of()));service=restarted();
    }
    @AfterEach void stop(){if(db!=null)db.execute("SHUTDOWN");}
    StripePaymentService restarted(){var env=new MockEnvironment();var s=new StripePaymentService(db,tx,ledger,new PaymentCatalog(env),gateway,json,new PaymentInbox(db,tx),env);ReflectionTestUtils.setField(s,"enabled",true);ReflectionTestUtils.setField(s,"acceptingNewOrders",true);if(matureCreation)ReflectionTestUtils.setField(s,"reconciliationClock",java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofMinutes(6)));return s;}
    CreatorPrincipal owner(){String id=UUID.randomUUID().toString();db.update("INSERT INTO creator VALUES(?)",id);db.update("INSERT INTO creator_google_identity VALUES(?)",id);return new CreatorPrincipal(id);}
    String create(){return (String)service.create(owner(),"starter-USD").get("orderId");}
    String failedCreation(){failCreate=true;assertThrows(RuntimeException.class,()->create());matureCreation=true;ReflectionTestUtils.setField(service,"reconciliationClock",java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofMinutes(6)));return createdOrder;}
    long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    Map<String,Object> stored(String id){return db.queryForMap("SELECT * FROM payment_order WHERE id=?",id);}
    JsonNode session(String session,String order,BigDecimal amount,String currency,String state,String payment){return json.valueToTree(Map.ofEntries(Map.entry("id",session),Map.entry("object","checkout.session"),Map.entry("mode","payment"),Map.entry("url","https://checkout.stripe.com/c/pay/"+session),Map.entry("client_reference_id",order),Map.entry("metadata",Map.of("order_id",order)),Map.entry("amount_total",amount.movePointRight(2).longValueExact()),Map.entry("currency",currency.toLowerCase(Locale.ROOT)),Map.entry("livemode",false),Map.entry("status",state),Map.entry("payment_status",payment),Map.entry("payment_intent","pi_"+session.substring(3))));}
    JsonNode list(boolean more,List<JsonNode> data){return json.valueToTree(Map.of("object","list","has_more",more,"data",data));}
    String sessionId(String order){return "cs_test_"+order.replace("-","");}
    void paid(String order){String id=sessionId(order);facts.put(id,session(id,order,new BigDecimal("9.90"),"USD","complete","paid"));}
    void due(){db.update("UPDATE payment_webhook_inbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP),lease_until=NULL");}
    JsonNode pointer(String order){return json.readTree(db.queryForObject("SELECT payload FROM payment_webhook_inbox WHERE event_id=?",String.class,"reconcile-order-"+order));}

    @Test void recoveryWorkerRacingInFlightCreateDoesNotQueryOrChangeCreatingStatus(){
        when(gateway.createSession(anyString(),anyString(),any(BigDecimal.class),anyString())).thenAnswer(c->{
            String order=c.getArgument(0);assertTrue(service.processNextEvent());assertEquals("CREATING",stored(order).get("status"));
            assertNull(stored(order).get("failure_code"));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='WAITING'"));
            var fact=session(sessionId(order),order,c.getArgument(2),c.getArgument(3),"open","unpaid");facts.put(sessionId(order),fact);return fact;
        });
        String order=create();assertEquals("PENDING",stored(order).get("status"));assertEquals(sessionId(order),stored(order).get("provider_link_id"));verify(gateway,never()).sessionsCreatedBetween(any(),any());verifyNoInteractions(ledger);
    }
    @Test void reserveAtomicallyPersistsOriginalMerchantAndFinancialSnapshotBeforePost(){
        String order=create();var p=pointer(order);assertTrue(p.path("original_scope").asBoolean());assertEquals(MERCHANT,p.path("merchant").asString());assertEquals("sandbox",p.path("environment").asString());assertEquals("9.90",p.path("amount").asString());assertEquals(100,p.path("credits").asLong());assertEquals("USD",p.path("currency").asString());assertEquals(stored(order).get("creator_id"),p.path("owner").asString());
    }
    @Test void failedRecoveryPointerInsertRollsBackOrderBeforeAnySupplierPost(){
        var fail=mock(PaymentInbox.class);doThrow(new IllegalStateException("synthetic durable store unavailable")).when(fail).acceptVerified(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        var env=new MockEnvironment();var s=new StripePaymentService(db,tx,ledger,new PaymentCatalog(env),gateway,json,fail,env);ReflectionTestUtils.setField(s,"enabled",true);ReflectionTestUtils.setField(s,"acceptingNewOrders",true);
        assertThrows(RuntimeException.class,()->s.create(owner(),"starter-USD"));assertEquals(0,count("SELECT COUNT(*) FROM payment_order"));verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());
    }
    @Test void knownSessionMissingWebhookRecoversAfterServiceRestartAndCreditsExactlyOnce(){
        String order=create();paid(order);service=restarted();assertTrue(service.processNextEvent());assertEquals("PAID",stored(order).get("status"));assertFalse(restarted().processNextEvent());
        verify(ledger,times(1)).credit(anyString(),eq(100L),eq("payment:"+order));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='DONE'"));
    }
    @Test void awaitingPaymentDoesNotSpendTechnicalRetryBudgetAndSurvivesRestart(){
        String order=create();assertTrue(service.processNextEvent());assertEquals("PENDING",stored(order).get("status"));assertEquals(0,count("SELECT attempts FROM payment_webhook_inbox"));verifyNoInteractions(ledger);
        due();paid(order);assertTrue(restarted().processNextEvent());assertEquals("PAID",stored(order).get("status"));verify(ledger,times(1)).credit(anyString(),eq(100L),eq("payment:"+order));
    }
    @Test void unknownCreateWithNoMatchNeverRepostsOrClaimsUnpaid(){
        String order=failedCreation();assertTrue(service.processNextEvent());assertEquals("CREATION_UNKNOWN",stored(order).get("status"));assertEquals("STRIPE_CREATE_RESULT_UNKNOWN",stored(order).get("failure_code"));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='WAITING'"));
        due();assertTrue(restarted().processNextEvent());verify(gateway,times(1)).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }
    @Test void unknownCreateFindsOneCompleteAuthoritativeGetAssociationWithoutPostReplay(){
        String order=failedCreation();paid(order);when(gateway.sessionsCreatedBetween(any(),nullable(String.class))).thenReturn(list(false,List.of(facts.get(sessionId(order)))));
        assertTrue(service.processNextEvent());assertEquals("PAID",stored(order).get("status"));assertEquals(sessionId(order),stored(order).get("provider_link_id"));verify(ledger).credit(anyString(),eq(100L),eq("payment:"+order));verify(gateway,times(1)).createSession(anyString(),anyString(),any(),anyString());
    }
    @Test void incompleteListingCannotEstablishUniquePaymentEvenIfFirstPageMatches(){
        String order=failedCreation();paid(order);var first=facts.get(sessionId(order));
        var pages=new java.util.concurrent.atomic.AtomicInteger();
        when(gateway.sessionsCreatedBetween(any(),nullable(String.class))).thenAnswer(c->{int page=pages.incrementAndGet();return page==1?list(true,List.of(first)):list(true,List.of(session("cs_cursor_page_"+page,"unrelated",new BigDecimal("9.90"),"USD","open","unpaid")));});
        assertTrue(service.processNextEvent());assertEquals(3,pages.get());assertEquals("CREATION_UNKNOWN",stored(order).get("status"));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='WAITING'"));verifyNoInteractions(ledger);verify(gateway,never()).retrieveSession(anyString());
    }
    @Test void twoSessionsForOneOrderAreAmbiguousAndNeverGrantCredits(){
        String order=failedCreation();paid(order);var second=session("cs_second_fixture",order,new BigDecimal("9.90"),"USD","complete","paid");when(gateway.sessionsCreatedBetween(any(),nullable(String.class))).thenReturn(list(false,List.of(facts.get(sessionId(order)),second)));
        assertTrue(service.processNextEvent());assertNotEquals("PAID",stored(order).get("status"));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='FAILED'"));verifyNoInteractions(ledger);
    }
    @Test void provider404KeepsOrderAndDurableAuditedRetryRatherThanCancelling(){
        String order=create();facts.clear();assertTrue(service.processNextEvent());assertEquals("PENDING",stored(order).get("status"));assertEquals("STRIPE_RECONCILE_PROVIDER_UNKNOWN",stored(order).get("failure_code"));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='FAILED'"));verifyNoInteractions(ledger);
    }
    @Test void tamperedSnapshotAmountCannotReachSettlement(){
        String order=create();paid(order);db.update("UPDATE payment_order SET credits=99999 WHERE id=?",order);assertTrue(service.processNextEvent());assertNotEquals("PAID",stored(order).get("status"));verify(gateway,never()).retrieveSession(anyString());verifyNoInteractions(ledger);
    }
    @Test void merchantRotationDoesNotClaimOriginalMerchantRecoveryPointer(){
        String order=create();paid(order);when(gateway.merchantId()).thenReturn("acct_otherfixture123456");assertFalse(restarted().processNextEvent());assertEquals("PENDING",stored(order).get("status"));verify(gateway,never()).retrieveSession(anyString());verifyNoInteractions(ledger);
    }
    @Test void wrongEnvironmentOrCurrencySupplierFactsCannotSettle(){
        String order=create();facts.put(sessionId(order),session(sessionId(order),order,new BigDecimal("9.90"),"CNY","complete","paid"));assertTrue(service.processNextEvent());assertNotEquals("PAID",stored(order).get("status"));verifyNoInteractions(ledger);
    }
    @Test void legacyMissingOriginalMerchantIsQueuedForReviewWithoutAnyProviderRead(){
        String owner=owner().id(),order=UUID.randomUUID().toString();db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_link_id) VALUES(?,?,?,9.90,'USD',100,'PENDING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP),?,?)",order,owner,"starter-USD",PROVIDER,"cs_historical_unproved");
        service.reconcileOrders();assertFalse(pointer(order).path("original_scope").asBoolean());assertTrue(service.processNextEvent());assertEquals("STRIPE_ORDER_SCOPE_REVIEW_REQUIRED",stored(order).get("failure_code"));assertEquals("PENDING",stored(order).get("status"));verify(gateway,never()).retrieveSession(anyString());verifyNoInteractions(ledger);
    }
    @Test void scannerIsBoundedToTenAndNeverCreatesOrCapturesAnyRemoteOrder(){
        for(int n=0;n<12;n++){String owner=owner().id();db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider) VALUES(?,?,?,9.90,'USD',100,'CREATING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP),?)",UUID.randomUUID().toString(),owner,"starter-USD",PROVIDER);}
        service.reconcileOrders();assertEquals(10,count("SELECT COUNT(*) FROM payment_webhook_inbox"));service.reconcileOrders();assertEquals(12,count("SELECT COUNT(*) FROM payment_webhook_inbox"));verifyNoInteractions(ledger);verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verify(gateway,never()).sessionsCreatedBetween(any(),any());
    }
    @Test void overdueUnknownOrderEscalatesToAuditedManualInsteadOfLoopingForever(){
        failedCreation();db.update("UPDATE payment_webhook_inbox SET created_at=TIMESTAMPADD(DAY,-8,CURRENT_TIMESTAMP)");assertTrue(service.processNextEvent());assertEquals("MANUAL",db.queryForObject("SELECT status FROM payment_webhook_inbox",String.class));assertEquals(1,count("SELECT COUNT(*) FROM payment_inbox_attempt WHERE result='MANUAL'"));assertFalse(restarted().processNextEvent());verifyNoInteractions(ledger);
    }
}
