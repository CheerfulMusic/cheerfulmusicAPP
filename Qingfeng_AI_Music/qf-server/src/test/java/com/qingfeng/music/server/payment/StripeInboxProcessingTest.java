package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked service wiring tests: actual provider signature parsing has separate gateway tests. */
class StripeInboxProcessingTest {
    static final String PROVIDER="STRIPE_SANDBOX",MERCHANT="acct_offline12345678",ENV="sandbox",EVENT="evt_offline12345678",RESOURCE="re_offline12345678",OWNER="owner-offline",ORDER="order-offline",INTENT="pi_offline12345678";
    final ObjectMapper json=new ObjectMapper();
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final JdbcFencedCreditsLedger ledger=mock(JdbcFencedCreditsLedger.class);
    final StripeCheckoutGateway gateway=mock(StripeCheckoutGateway.class);
    final PaymentInbox inbox=mock(PaymentInbox.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    StripePaymentService service;
    Map<String,Object> row;
    @BeforeEach void fixture(){
        service=new StripePaymentService(db,tx,ledger,new PaymentCatalog(new MockEnvironment()),gateway,json,inbox);
        when(gateway.provider()).thenReturn(PROVIDER);when(gateway.merchantId()).thenReturn(MERCHANT);when(gateway.environment()).thenReturn(ENV);when(gateway.configured()).thenReturn(true);when(gateway.isLive()).thenReturn(false);
        ReflectionTestUtils.setField(service,"enabled",true);
        for(var check:CreditSchemaReadiness.CHECKS.values())when(db.queryForObject(check.sql(),Integer.class)).thenReturn(check.expected());
        when(db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE provider IN ('STRIPE_SANDBOX','STRIPE_LIVE') AND provider<>? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,PROVIDER)).thenReturn(0);
        row=new HashMap<>(Map.of("id",ORDER,"creator_id",OWNER,"amount",new BigDecimal("9.90"),"currency","USD","credits",100L,"status","PAID","provider_capture_id",INTENT,"provider",PROVIDER));
        when(db.queryForList("SELECT * FROM payment_order WHERE provider=? AND provider_capture_id=?",PROVIDER,INTENT)).thenReturn(List.of(row));
    }
    PaymentInbox.Delivery delivery(String merchant,String event,String kind){
        String pointer=json.writeValueAsString(Map.of("provider","STRIPE","merchant",merchant,"environment",ENV,"event",event,"resource",RESOURCE));
        return new PaymentInbox.Delivery(PROVIDER,EVENT,kind,pointer,"synthetic-lease",1,MERCHANT,ENV);
    }
    PaymentInbox.Delivery due(String kind){var d=delivery(MERCHANT,EVENT,kind);when(inbox.claim(PROVIDER,MERCHANT,ENV)).thenReturn(Optional.of(d));return d;}
    void refund(String status){when(gateway.retrieveRefund(RESOURCE)).thenReturn(json.valueToTree(Map.of("id",RESOURCE,"status",status,"payment_intent",INTENT,"currency","usd","amount",198)));}
    @Test void verifiedWebhookPersistsOnlyTheScopedPointerAndNeverRawCustomerData(){
        byte[] body="synthetic raw body with a customer@example.invalid field".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(gateway.verifyWebhook(body,"synthetic-signature")).thenReturn(json.valueToTree(Map.of("id",EVENT,"type","refund.updated","data",Map.of("object",Map.of("id",RESOURCE,"customer_email","customer@example.invalid")))));
        assertEquals(true,service.webhook("synthetic-signature",body).get("received"));
        var payload=ArgumentCaptor.forClass(String.class);verify(inbox).acceptVerified(eq(PROVIDER),eq(MERCHANT),eq(ENV),eq(EVENT),eq("refund.updated"),payload.capture());
        var saved=json.readTree(payload.getValue());assertEquals(5,saved.size());assertEquals(EVENT,saved.path("event").asString());assertEquals(RESOURCE,saved.path("resource").asString());
        assertEquals(MERCHANT,saved.path("merchant").asString());assertEquals(ENV,saved.path("environment").asString());assertFalse(payload.getValue().contains("customer@example.invalid"));
        assertEquals(json.writeValueAsString(new TreeMap<>(Map.of("provider","STRIPE","merchant",MERCHANT,"environment",ENV,"event",EVENT,"resource",RESOURCE))),payload.getValue());
        verifyNoInteractions(ledger);verify(gateway,never()).retrieveRefund(anyString());
    }
    @Test void successfulRefundUsesOwnerFirstCompletionAndMutationIsDeferredUntilLeaseTransaction(){
        var d=due("refund.updated");refund("succeeded");assertTrue(service.processNextEvent());
        var mutation=ArgumentCaptor.forClass(Runnable.class);verify(inbox).completeOwned(eq(d),eq(OWNER),mutation.capture(),eq("stripe-refund:"+RESOURCE));
        verifyNoInteractions(ledger);mutation.getValue().run();
        verify(ledger).paymentRefund("STRIPE",MERCHANT,ENV,RESOURCE,OWNER,ORDER,INTENT,198L,"USD");
        verify(inbox,never()).complete(any(),any());verify(inbox,never()).waitForProvider(any(),anyString(),any(),anyString());
    }
    @Test void verifiedPendingRefundUsesWaitRatherThanCompletionOrBlindPermanentAdjustment(){
        var d=due("refund.updated");refund("pending");assertTrue(service.reconcileOne());
        var mutation=ArgumentCaptor.forClass(Runnable.class);verify(inbox).waitForProvider(eq(d),eq(OWNER),mutation.capture(),eq("stripe-refund-pending:"+RESOURCE+":pending"));
        mutation.getValue().run();verifyNoInteractions(ledger);verify(inbox,never()).completeOwned(any(),anyString(),any(),any());verify(inbox,never()).retry(any());
    }
    @Test void pointerScopeAndEventIdentityMustBothMatchTheClaimBeforeAnyProviderRead(){
        for(var d:List.of(delivery("acct_foreign12345678",EVENT,"refund.updated"),delivery(MERCHANT,"evt_foreign12345678","refund.updated"))){
            when(inbox.claim(PROVIDER,MERCHANT,ENV)).thenReturn(Optional.of(d));assertTrue(service.processNextEvent());verify(inbox).retry(d);
        }
        verify(gateway,never()).retrieveRefund(anyString());verifyNoInteractions(ledger);verify(inbox,never()).completeOwned(any(),anyString(),any(),any());
    }
    @Test void unsupportedOrUnknownRefundStateUsesBoundedTechnicalRetryAndNoMutation(){
        var d=due("refund.updated");refund("unrecognized_supplier_state");assertTrue(service.processNextEvent());
        verify(inbox).retry(d);verifyNoInteractions(ledger);verify(inbox,never()).waitForProvider(any(),anyString(),any(),anyString());verify(inbox,never()).completeOwned(any(),anyString(),any(),any());
    }
    private CreditSchemaReadiness.Check readinessCheck(String fragment){return CreditSchemaReadiness.CHECKS.values().stream().filter(c->c.sql().contains(fragment)).findFirst().orElseThrow();}
    private void rejectReadiness(CreditSchemaReadiness.Check check){when(db.queryForObject(check.sql(),Integer.class)).thenReturn(check.expected()+1);}
    private void restoreReadiness(){for(var check:CreditSchemaReadiness.CHECKS.values())when(db.queryForObject(check.sql(),Integer.class)).thenReturn(check.expected());}
    @Test void preV40ProtocolOrMissingScopeNeverClaimsAnyEvent(){
        rejectReadiness(readinessCheck("protocol"));assertFalse(service.processNextEvent());verifyNoInteractions(inbox,ledger);
        restoreReadiness();rejectReadiness(readinessCheck("merchant_scope"));assertFalse(service.processNextEvent());verifyNoInteractions(inbox,ledger);
    }
    @Test void newPurchasesRemainDisabledByDefaultWhileExistingInboxCanBeReconciled(){
        assertEquals(false,service.catalog().get("enabled"));assertEquals(List.of(),service.catalog().get("packages"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.create(new CreatorPrincipal(OWNER),"starter-USD"));
        verify(gateway,never()).verifyAccount();var d=due("refund.updated");refund("pending");assertTrue(service.processNextEvent());verify(inbox).waitForProvider(eq(d),eq(OWNER),any(),anyString());
    }
    @Test void emptyScopedInboxDoesNotInspectAnySupplierOrHistoricalUnknownScope(){
        when(inbox.claim(PROVIDER,MERCHANT,ENV)).thenReturn(Optional.empty());assertFalse(service.processNextEvent());assertEquals(PROVIDER,service.provider());
        verify(inbox,never()).claim(anyString());verify(gateway,never()).retrieveRefund(anyString());verifyNoInteractions(ledger);
    }
    private void runTransactionsInMemory(){
        doAnswer(call->{java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work=call.getArgument(0);work.accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
    }
    @Test void stripeAdmissionAlsoBlocksAnExistingMainPayPalUnknownCreation(){
        runTransactionsInMemory();when(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",OWNER)).thenReturn(List.of(Map.of("id",OWNER)));
        when(db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",Integer.class,OWNER,OWNER)).thenReturn(1);
        when(db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE creator_id=? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,OWNER)).thenReturn(1);
        var pack=new PaymentCatalog(new MockEnvironment()).find("starter-USD").orElseThrow();
        var denied=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ReflectionTestUtils.invokeMethod(service,"reserve",OWNER,"new-order",pack));
        assertEquals(409,denied.getStatusCode().value());verify(db,never()).update(startsWith("INSERT INTO payment_order"),any(Object[].class));
        verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger,inbox);
    }
    @Test void synchronousSettlementLocksTheOwnerBeforeTheOrderAndRechecksOwnership(){
        runTransactionsInMemory();when(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",OWNER)).thenReturn(List.of(Map.of("id",OWNER)));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?",ORDER,PROVIDER)).thenReturn(List.of(row));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE",ORDER,PROVIDER)).thenReturn(List.of(row));
        var session=json.valueToTree(Map.of("id","cs_test_offline12345678","metadata",Map.of("order_id",ORDER),"client_reference_id",ORDER,"mode","payment","currency","usd","amount_total",990,"livemode",false,"status","complete","payment_status","paid","payment_intent",INTENT));
        ReflectionTestUtils.invokeMethod(service,"settle",ORDER,"cs_test_offline12345678",session);
        var order=inOrder(db);order.verify(db).queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?",ORDER,PROVIDER);
        order.verify(db).queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",OWNER);
        order.verify(db).queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE",ORDER,PROVIDER);
        verify(ledger).bindPayment("STRIPE",MERCHANT,ENV,OWNER,ORDER,INTENT,"USD");verify(ledger,never()).credit(anyString(),anyLong(),anyString());
    }

    @Test void missingNativeIdentityTableOrLegacyInboxPrimaryKeyCannotOpenProcessing(){
        rejectReadiness(readinessCheck("p0_credit_native_identity"));assertFalse(service.processNextEvent());verifyNoInteractions(inbox,ledger);
        restoreReadiness();var key=CreditSchemaReadiness.CHECKS.values().stream().filter(c->c.sql().contains("payment_webhook_inbox")&&c.sql().contains("PRIMARY")).findFirst().orElseThrow();
        rejectReadiness(key);assertFalse(service.processNextEvent());verifyNoInteractions(inbox,ledger);
    }

}
