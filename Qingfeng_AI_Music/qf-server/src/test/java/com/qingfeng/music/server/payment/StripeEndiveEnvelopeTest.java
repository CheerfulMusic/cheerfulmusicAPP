package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.payment.StripePaymentService.Resolution;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic Endive-version Event/Checkout envelopes, verified by the actual Stripe SDK.
 * Not captured Stripe deliveries, Sandbox acceptance, a database test, or a durable-inbox proof.
 * Supplier reads, transactions and persistence below are explicit in-memory mocks only.
 */
class StripeEndiveEnvelopeTest {
    private static final String VERSION="2026-10-28.endive",ACCOUNT="acct_offline12345678";
    private static final String SECRET="whsec_endive_offline_fixture_not_a_real_credential";
    private static final String SESSION="cs_test_endive12345678",INTENT="pi_endive12345678";
    private static final String ORDER="endive-order-fixture",OWNER="endive-owner-fixture";
    private static final List<String> KINDS=List.of("checkout.session.async_payment_succeeded","checkout.session.async_payment_failed","checkout.session.expired");
    private final ObjectMapper json=new ObjectMapper();
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final JdbcFencedCreditsLedger ledger=mock(JdbcFencedCreditsLedger.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private StripeCheckoutGateway gateway;
    private StripePaymentService service;
    private Map<String,Object> order;

    @BeforeEach void offlineFixture(){
        gateway=spy(new StripeCheckoutGateway(json));
        ReflectionTestUtils.setField(gateway,"environment","sandbox");
        ReflectionTestUtils.setField(gateway,"apiKey","sk_test_endive_offline_fixture");
        ReflectionTestUtils.setField(gateway,"accountId",ACCOUNT);
        ReflectionTestUtils.setField(gateway,"webhookSecret",SECRET);
        ReflectionTestUtils.setField(gateway,"successUrl","");ReflectionTestUtils.setField(gateway,"cancelUrl","");
        service=new StripePaymentService(db,tx,ledger,new PaymentCatalog(new MockEnvironment()),gateway,json);
        order=new HashMap<>(Map.of("id",ORDER,"creator_id",OWNER,"provider","STRIPE_SANDBOX","provider_link_id",SESSION,
            "status","PENDING","amount",new BigDecimal("9.90"),"currency","USD","credits",100L));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=?",ORDER,"STRIPE_SANDBOX")).thenAnswer(call->List.of(order));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND provider=? FOR UPDATE",ORDER,"STRIPE_SANDBOX")).thenAnswer(call->List.of(order));
        when(db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",OWNER)).thenReturn(List.of(Map.of("id",OWNER)));
        doAnswer(call->{
            Consumer<TransactionStatus> work=call.getArgument(0);work.accept(mock(TransactionStatus.class));return null;
        }).when(tx).executeWithoutResult(any());
        when(db.update("UPDATE payment_order SET status='PAID',provider_link_id=?,provider_capture_id=? WHERE id=? AND provider=?",SESSION,INTENT,ORDER,"STRIPE_SANDBOX"))
            .thenAnswer(call->{order.put("status","PAID");order.put("provider_capture_id",INTENT);return 1;});
        for(String state:List.of("FAILED","CANCELLED"))
            when(db.update("UPDATE payment_order SET status='"+state+"' WHERE id=? AND provider=? AND status IN ('CREATING','PENDING')",ORDER,"STRIPE_SANDBOX"))
                .thenAnswer(call->{if(!Set.of("CREATING","PENDING").contains(order.get("status")))return 0;order.put("status",state);return 1;});
    }
    private String body(String kind,String resource,boolean live,String account){
        String status=kind.endsWith("expired")?"expired":"complete";
        String payment=kind.endsWith("succeeded")?"paid":"unpaid";
        String accountField=account==null?"":",\"account\":\""+account+"\"";
        return """
            {"id":"evt_endive12345678","object":"event","api_version":"%s","created":1794474000,
             "livemode":%s,"pending_webhooks":1,"request":{"id":null,"idempotency_key":null},"type":"%s"%s,
             "data":{"object":{"id":"%s","object":"checkout.session","mode":"payment","status":"%s",
             "payment_status":"%s","amount_subtotal":990,"amount_total":990,"currency":"usd",
             "livemode":%s,"client_reference_id":"%s","metadata":{"order_id":"%s"},
             "payment_intent":"%s","payment_method_types":["card"],"customer":null,"customer_email":null,
             "custom_fields":[],"consent":null,"expires_at":1794560400,"url":null}}}
            """.formatted(VERSION,live,kind,accountField,resource,status,payment,live,ORDER,ORDER,INTENT);
    }
    private JsonNode signed(String body) throws Exception {
        return gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8),StripeCheckoutGatewayTest.signature(body,SECRET,Instant.now().getEpochSecond()));
    }
    private JsonNode remote(String status,String payment){
        return json.readTree("""
            {"id":"%s","object":"checkout.session","livemode":false,"mode":"payment","status":"%s",
             "payment_status":"%s","amount_total":990,"currency":"usd","metadata":{"order_id":"%s"},
             "client_reference_id":"%s","payment_intent":"%s"}
            """.formatted(SESSION,status,payment,ORDER,ORDER,INTENT));
    }
    private Resolution reconcile(JsonNode event,JsonNode authoritative){
        String resource=event.path("data").path("object").path("id").asString();
        doReturn(authoritative).when(gateway).retrieveSession(resource);
        return ReflectionTestUtils.invokeMethod(service,"reconcileSession",event.path("type").asString(),resource);
    }
    private void assertAcceptedKind(String kind) throws Exception {
        JsonNode event=signed(body(kind,SESSION,false,null));
        assertEquals(VERSION,event.path("api_version").asString());assertEquals(kind,event.path("type").asString());
        assertEquals(SESSION,event.path("data").path("object").path("id").asString());
        assertEquals("checkout.session",event.path("data").path("object").path("object").asString());
        assertTrue(event.path("request").path("id").isNull());assertEquals(990,event.path("data").path("object").path("amount_total").asLong());
        verifyNoInteractions(db,tx,ledger);verify(gateway,never()).verifyAccount();
    }

    @Test void acceptsEndiveAsyncSucceededSnapshotEnvelope() throws Exception {assertAcceptedKind(KINDS.get(0));}
    @Test void acceptsEndiveAsyncFailedSnapshotEnvelope() throws Exception {assertAcceptedKind(KINDS.get(1));}
    @Test void acceptsEndiveExpiredSnapshotEnvelope() throws Exception {assertAcceptedKind(KINDS.get(2));}

    @Test void repeatedAuthenticEndiveEnvelopeRetainsStableIdentityWithoutDirectlyIssuingCredits() throws Exception {
        for(String kind:KINDS){String body=body(kind,SESSION,false,ACCOUNT);JsonNode first=signed(body),second=signed(body);
            assertEquals(first,second);assertEquals("evt_endive12345678",second.path("id").asString());
            assertEquals(ACCOUNT,second.path("account").asString());}
        // Gateway signature acceptance is deliberately not called durable-event idempotency.
        verifyNoInteractions(db,tx,ledger);
    }
    @Test void allThreeEndiveKindsRejectWrongSignatureAndRawBodyMutation() throws Exception {
        for(String kind:KINDS){String body=body(kind,SESSION,false,null);long now=Instant.now().getEpochSecond();
            String wrong=StripeCheckoutGatewayTest.signature(body,"whsec_wrong_offline_fixture",now);
            assertEquals(401,assertThrows(ResponseStatusException.class,()->gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8),wrong)).getStatusCode().value());
            String valid=StripeCheckoutGatewayTest.signature(body,SECRET,now);
            assertEquals(401,assertThrows(ResponseStatusException.class,()->gateway.verifyWebhook((body+" ").getBytes(StandardCharsets.UTF_8),valid)).getStatusCode().value());}
        verifyNoInteractions(db,tx,ledger);
    }
    @Test void allThreeEndiveKindsRejectWrongModeOrForeignConnectedAccount() throws Exception {
        for(String kind:KINDS){
            assertEquals(409,assertThrows(ResponseStatusException.class,()->signed(body(kind,SESSION,true,null))).getStatusCode().value());
            assertEquals(409,assertThrows(ResponseStatusException.class,()->signed(body(kind,SESSION,false,"acct_foreign12345678"))).getStatusCode().value());}
        verifyNoInteractions(db,tx,ledger);
    }
    @Test void endiveVersionDoesNotBypassTimestampReplayLimit() throws Exception {
        for(String kind:KINDS){String body=body(kind,SESSION,false,null);String stale=StripeCheckoutGatewayTest.signature(body,SECRET,Instant.now().minusSeconds(600).getEpochSecond());
            assertEquals(401,assertThrows(ResponseStatusException.class,()->gateway.verifyWebhook(body.getBytes(StandardCharsets.UTF_8),stale)).getStatusCode().value());}
        verifyNoInteractions(db,tx,ledger);
    }
    @Test void endiveAsyncSuccessRequiresAuthoritativePaidSessionAndDuplicateSettlementCreditsOnce() throws Exception {
        var event=signed(body(KINDS.get(0),SESSION,false,null));
        var unpaid=reconcile(event,remote("complete","unpaid"));assertFalse(unpaid.verified());assertNull(unpaid.apply());
        verifyNoInteractions(ledger);
        for(int i=0;i<2;i++){var result=reconcile(event,remote("complete","paid"));assertTrue(result.verified());assertEquals(OWNER,result.ownerId());result.apply().run();}
        assertEquals("PAID",order.get("status"));verify(ledger,times(1)).credit(OWNER,100L,"payment:"+ORDER);
        verify(ledger,times(2)).bindPayment("STRIPE",ACCOUNT,"sandbox",OWNER,ORDER,INTENT,"USD");
    }
    @Test void endiveAsyncFailureAndExpiryOnlyChangeUnpaidPendingOrderWithoutCrediting() throws Exception {
        for(String kind:List.of(KINDS.get(1),KINDS.get(2))){order.put("status","PENDING");
            var result=reconcile(signed(body(kind,SESSION,false,null)),remote(kind.endsWith("expired")?"expired":"complete","unpaid"));
            assertTrue(result.verified());assertEquals(OWNER,result.ownerId());result.apply().run();
            assertEquals(kind.endsWith("expired")?"CANCELLED":"FAILED",order.get("status"));result.apply().run();}
        verifyNoInteractions(ledger);
    }
    @Test void lateFailureOrExpiredEnvelopeCannotCancelOrRecreditAlreadyPaidOrder() throws Exception {
        order.put("status","PAID");order.put("provider_capture_id",INTENT);
        var failure=reconcile(signed(body(KINDS.get(1),SESSION,false,null)),remote("complete","paid"));assertTrue(failure.verified());failure.apply().run();
        var expired=reconcile(signed(body(KINDS.get(2),SESSION,false,null)),remote("expired","unpaid"));assertTrue(expired.verified());expired.apply().run();
        assertEquals("PAID",order.get("status"));verify(ledger,never()).credit(anyString(),anyLong(),anyString());
    }
    @Test void signedResourceIdMustMatchAuthoritativeCheckoutAndItsBoundLocalOrder() throws Exception {
        for(String kind:KINDS){var event=signed(body(kind,SESSION,false,null));
            assertThrows(IllegalStateException.class,()->reconcile(event,json.readTree(remote("complete","paid").toString().replace(SESSION,"cs_other12345678"))));
            assertThrows(IllegalStateException.class,()->reconcile(event,json.readTree(remote("complete","paid").toString().replace(ORDER,"unknown-order-fixture"))));}
        verifyNoInteractions(ledger);verify(tx,never()).executeWithoutResult(any());
    }
    @Test void signedFinancialFieldsNeverReplaceAuthoritativeAmountCurrencyOrOrderOwner() throws Exception {
        String forged=body(KINDS.get(0),SESSION,false,null).replace("\"amount_total\":990","\"amount_total\":999999")
            .replace("\"currency\":\"usd\"","\"currency\":\"gbp\"").replace(ORDER,"victim-order-fixture");
        var result=reconcile(signed(forged),remote("complete","paid"));assertTrue(result.verified());assertEquals(OWNER,result.ownerId());result.apply().run();
        verify(ledger).credit(OWNER,100L,"payment:"+ORDER);verify(ledger,never()).credit(eq("victim-owner-fixture"),anyLong(),anyString());
        assertEquals("PAID",order.get("status"));
    }
    @Test void authoritativeCurrencyAndSnapshotMismatchRejectBeforeAnyFinancialWrite() throws Exception {
        for(String kind:KINDS){var event=signed(body(kind,SESSION,false,null));
            ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->reconcile(event,json.readTree(remote("complete","paid").toString().replace("\"currency\":\"usd\"","\"currency\":\"gbp\""))));
            assertEquals(409,failure.getStatusCode().value());}
        verifyNoInteractions(ledger);verify(tx,never()).executeWithoutResult(any());
    }
    @Test void missingSignedResourceIdIsRejectedBeforeDurableQueueOrLedgerWrite() throws Exception {
        ReflectionTestUtils.setField(service,"enabled",true);
        when(db.queryForObject("SELECT COUNT(*) FROM p0_credit_writer_policy WHERE id=1 AND protocol=2",Integer.class)).thenReturn(1);
        when(db.queryForObject("SELECT COUNT(*) FROM p0_credit_cutover_state WHERE id=1 AND status='ACTIVE'",Integer.class)).thenReturn(1);
        when(db.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN ('p0_credit_ledger_insert_fence','p0_credit_ledger_update_fence','p0_credit_ledger_delete_fence')",Integer.class)).thenReturn(3);
        when(db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE provider IN ('STRIPE_SANDBOX','STRIPE_LIVE') AND provider<>? AND status IN ('CREATING','PENDING')",Integer.class,"STRIPE_SANDBOX")).thenReturn(0);
        when(db.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('p0_credit_refund_terminal','p0_credit_refund_return','p0_stripe_dispute_proof')",Integer.class)).thenReturn(3);
        for(String kind:KINDS){String body=body(kind,"",false,null);String signature=StripeCheckoutGatewayTest.signature(body,SECRET,Instant.now().getEpochSecond());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.webhook(signature,body.getBytes(StandardCharsets.UTF_8))).getStatusCode().value());}
        verifyNoInteractions(ledger);verifyNoInteractions(tx);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
