package com.qingfeng.music.server.payment;

import com.qingfeng.music.credits.CreditsLedger;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Java service tests, no Spring context, real database or PayPal transport. */
class PayPalNewPurchaseGateTest {
    private JdbcTemplate db;
    private TransactionTemplate tx;
    private CreditsLedger ledger;
    private PayPalCheckoutGateway gateway;
    private PayPalPaymentService service;
    private PaymentInbox inbox;
    private Map<String,Object> row;
    private final String owner="synthetic-owner", id="synthetic-local-order", remote="REMOTE1";
    private final PayPalCheckoutGateway.WebhookHeaders headers=
        new PayPalCheckoutGateway.WebhookHeaders("synthetic","https://fixture.invalid/cert","fixture-id","fixture-signature","fixture-time");

    @BeforeEach void setup(){
        db=mock(JdbcTemplate.class); tx=mock(TransactionTemplate.class); ledger=mock(CreditsLedger.class);
        gateway=mock(PayPalCheckoutGateway.class);
        var env=new MockEnvironment();
        service=new PayPalPaymentService(db,tx,ledger,new PaymentCatalog(env),env){
            @Override PayPalCheckoutGateway gateway(){return gateway;}
        };
        inbox=mock(PaymentInbox.class);service.infrastructure(inbox,mock(PaymentRiskService.class));
        ReflectionTestUtils.setField(service,"enabled",true);
        ReflectionTestUtils.setField(service,"environment","sandbox");
        ReflectionTestUtils.setField(service,"clientId","synthetic-client");
        ReflectionTestUtils.setField(service,"clientSecret","synthetic-secret");
        ReflectionTestUtils.setField(service,"merchantId","MERCHANT1");
        ReflectionTestUtils.setField(service,"webhookId","synthetic-webhook");
        ReflectionTestUtils.setField(service,"acceptingNewOrders",false);
        row=new HashMap<>(Map.of("id",id,"creator_id",owner,"provider_link_id",remote,
            "amount",new BigDecimal("9.90"),"currency","USD","credits",100L,"status","PENDING"));
    }
    private PayPalCheckoutGateway.Snapshot snapshot(String status,String captureStatus){
        return new PayPalCheckoutGateway.Snapshot(remote,id,status,"MERCHANT1",
            "COMPLETED".equals(captureStatus)?"CAP1":"",captureStatus,new BigDecimal("9.90"),"USD");
    }
    private void persistence(){
        when(db.queryForObject(contains("SELECT creator_id"),eq(String.class),any(),any())).thenReturn(owner);
        when(db.queryForList(anyString(),any(Object[].class))).thenAnswer(inv->List.of(row));
        doAnswer(inv->{
            Consumer<TransactionStatus> callback=inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));return null;
        }).when(tx).executeWithoutResult(any());
        when(db.update(anyString(),any(Object[].class))).thenAnswer(inv->{
            if (inv.getArgument(0,String.class).contains("status='CANCELLED'")) row.put("status","CANCELLED");
            else {row.put("status","PAID"); row.put("provider_capture_id","CAP1");}
            return 1;
        });
    }
    private byte[] event(){
        return ("{\"id\":\"WH-fixture\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\",\"resource\":{\"id\":\"CAP1\","
            +"\"supplementary_data\":{\"related_ids\":{\"order_id\":\""+remote+"\"}}}}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void closedGateHasNoDatabaseLedgerOrGatewaySideEffects(){
        var failure=assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,failure.getStatusCode());
        verifyNoInteractions(db,tx,ledger,gateway);
    }
    @Test void catalogPausesPurchasesButKeepsSettlementAndSdkReady(){
        assertEquals(false,service.catalog().get("enabled"));
        assertEquals(true,service.catalog().get("settlementEnabled"));
        assertEquals(true,service.sdkConfig().get("enabled"));
        assertEquals("synthetic-client",service.sdkConfig().get("clientId"));
        assertTrue(service.ready());
    }
    @Test void existingPaidOrderCanStillBeRead(){
        persistence();row.put("status","PAID");
        assertEquals("PAID",service.order(owner,id).get("status"));
        verifyNoInteractions(gateway,ledger);
    }
    @Test void existingCompletedOrderCanStillCreditThroughQuery(){
        persistence();when(gateway.retrieve(remote)).thenReturn(snapshot("COMPLETED","COMPLETED"));
        assertEquals("PAID",service.order(owner,id).get("status"));
        verify(ledger).credit(owner,100L,"payment:"+id);
        verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void completionWebhookStillVerifiesAndPersistsWhenPurchasesArePaused(){
        persistence();when(gateway.verifyWebhook(eq(headers),any(byte[].class))).thenReturn(true);
        when(gateway.retrieve(remote)).thenReturn(snapshot("COMPLETED","COMPLETED"));
        service.webhook(headers,event());
        verify(gateway).verifyWebhook(eq(headers),any(byte[].class));
        verify(inbox).acceptVerified(eq("PAYPAL_SANDBOX"),eq("WH-fixture"),eq("PAYMENT.CAPTURE.COMPLETED"),anyString());
        verifyNoInteractions(ledger);verify(gateway,never()).retrieve(anyString());
    }
    @Test void repeatedWebhookKeepsTheSameDurableEventIdentity(){
        persistence();when(gateway.verifyWebhook(eq(headers),any(byte[].class))).thenReturn(true);
        when(gateway.retrieve(remote)).thenReturn(snapshot("COMPLETED","COMPLETED"));
        service.webhook(headers,event());service.webhook(headers,event());
        verify(inbox,times(2)).acceptVerified(eq("PAYPAL_SANDBOX"),eq("WH-fixture"),eq("PAYMENT.CAPTURE.COMPLETED"),anyString());
        verifyNoInteractions(ledger);
    }
    @Test void invalidWebhookIsNotAcceptedWhenNewPurchasesArePaused(){
        when(gateway.verifyWebhook(eq(headers),any(byte[].class))).thenReturn(false);
        var failure=assertThrows(ResponseStatusException.class,()->service.webhook(headers,event()));
        assertEquals(HttpStatus.UNAUTHORIZED,failure.getStatusCode());
        verifyNoInteractions(db,tx,ledger);
    }
    @Test void approvedExistingOrderCanStillCapture(){
        persistence();when(gateway.retrieve(remote)).thenReturn(snapshot("APPROVED",""));
        when(gateway.capture(remote,"c"+id)).thenReturn(snapshot("COMPLETED","COMPLETED"));
        assertEquals("PAID",service.capture(owner,id).get("status"));
        verify(gateway).capture(remote,"c"+id);
        verify(ledger).credit(owner,100L,"payment:"+id);
    }
    @Test void existingApprovedOrderCanStillResume(){
        persistence();
        when(gateway.retrieveCheckout(remote)).thenReturn(new PayPalCheckoutGateway.CheckoutView(snapshot("APPROVED",""),""));
        assertEquals("APPROVED",service.resume(owner,id).get("status"));
        verifyNoInteractions(ledger,tx);
    }
    @Test void existingUnpaidOrderCanStillBeClosedAfterAuthoritativeQuery(){
        persistence();when(gateway.retrieve(remote)).thenReturn(snapshot("CREATED",""));
        assertEquals("CANCELLED",service.close(owner,id).get("status"));
        verifyNoInteractions(ledger);
        verify(gateway,never()).capture(anyString(),anyString());
    }
    @Test void unknownRemoteQueryKeepsPendingAndDoesNotConcludeUnpaid(){
        persistence();when(gateway.retrieve(remote)).thenThrow(new IllegalStateException("synthetic HTTP 404"));
        assertEquals("PENDING",service.order(owner,id).get("status"));
        assertEquals("PENDING",row.get("status"));
        verifyNoInteractions(ledger,tx);
    }
    @Test void reopeningGateRestoresNewPurchaseValidation(){
        ReflectionTestUtils.setField(service,"acceptingNewOrders",true);
        assertEquals(true,service.catalog().get("enabled"));
        var failure=assertThrows(ResponseStatusException.class,()->service.create(owner,"missing-package"));
        assertEquals(HttpStatus.BAD_REQUEST,failure.getStatusCode());
        verifyNoInteractions(db,tx,ledger,gateway);
    }
    @Test void canaryCatalogRequiresTheExactAuthenticatedOwner(){
        String chosen="00000000-0000-0000-0000-000000000001";
        ReflectionTestUtils.setField(service,"canaryOwnerIds",chosen);
        assertEquals(true,service.catalog(new com.qingfeng.music.server.security.CreatorPrincipal(chosen)).get("enabled"));
        assertEquals(false,service.catalog(new com.qingfeng.music.server.security.CreatorPrincipal("00000000-0000-0000-0000-000000000002")).get("enabled"));
        assertEquals(false,service.catalog().get("enabled"));
        assertEquals(true,service.sdkConfig().get("enabled"));
        var denied=assertThrows(ResponseStatusException.class,()->service.create(owner,"starter-USD"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,denied.getStatusCode());
        var admitted=assertThrows(ResponseStatusException.class,()->service.create(chosen,"missing-package"));
        assertEquals(HttpStatus.BAD_REQUEST,admitted.getStatusCode());
        verifyNoInteractions(db,tx,ledger,gateway);
    }
    @Test void malformedOrOversizedCanaryConfigurationFailsClosedForEveryOwner(){
        String chosen="00000000-0000-0000-0000-000000000001";
        var principal=new com.qingfeng.music.server.security.CreatorPrincipal(chosen);
        for(String value:List.of(chosen+",",chosen+",*",chosen+","+chosen,
                chosen+",00000000-0000-0000-0000-000000000002,00000000-0000-0000-0000-000000000003,00000000-0000-0000-0000-000000000004")){
            ReflectionTestUtils.setField(service,"canaryOwnerIds",value);
            assertEquals(false,service.catalog(principal).get("enabled"),value);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->service.create(chosen,"starter-USD")).getStatusCode());
        }
        verifyNoInteractions(db,tx,ledger,gateway);
    }
    @Test void canaryCannotBypassProviderReadinessOrChangeExistingOrderSettlement(){
        ReflectionTestUtils.setField(service,"canaryOwnerIds","00000000-0000-0000-0000-000000000001");
        persistence();when(gateway.retrieve(remote)).thenReturn(snapshot("COMPLETED","COMPLETED"));
        assertEquals("PAID",service.order(owner,id).get("status"));
        verify(ledger).credit(owner,100L,"payment:"+id);
        ReflectionTestUtils.setField(service,"enabled",false);
        assertEquals(false,service.catalog(new com.qingfeng.music.server.security.CreatorPrincipal("00000000-0000-0000-0000-000000000001")).get("enabled"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,()->service.create("00000000-0000-0000-0000-000000000001","starter-USD")).getStatusCode());
        verify(gateway,never()).create(anyString(),anyString(),any(),anyString(),anyString(),anyString());
    }
    @Test void admittedCanaryCreatesOnlyItsOwnOrderWithTheOriginalUsdQuote(){
        String chosen="00000000-0000-0000-0000-000000000001";
        ReflectionTestUtils.setField(service,"canaryOwnerIds",chosen);
        persistence();
        when(db.queryForObject(contains("creator_google_identity"),eq(Integer.class),eq(chosen),eq(chosen))).thenReturn(1);
        when(db.queryForObject(startsWith("SELECT COUNT(*) FROM payment_order"),eq(Integer.class),eq(chosen))).thenReturn(0);
        when(gateway.create(anyString(),anyString(),any(),anyString(),anyString(),anyString()))
            .thenReturn(new PayPalCheckoutGateway.Created("REMOTE2","https://www.sandbox.paypal.com/checkoutnow?token=REMOTE2"));
        var result=service.create(chosen,"starter-USD");
        assertEquals("REMOTE2",result.get("paypalOrderId"));
        verify(db).update(startsWith("INSERT INTO payment_order"),anyString(),eq(chosen),eq("starter-USD"),eq(new BigDecimal("9.90")),eq("USD"),eq(100L),eq("CREATING"),eq("PAYPAL_SANDBOX"));
        verify(gateway).create(anyString(),anyString(),eq(new BigDecimal("9.90")),eq("USD"),anyString(),anyString());
        verifyNoInteractions(ledger);
    }
}
