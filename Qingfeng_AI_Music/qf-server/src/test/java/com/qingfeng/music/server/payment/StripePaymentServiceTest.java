package com.qingfeng.music.server.payment;

import com.qingfeng.music.server.payment.StripePaymentService.Resolution;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests use synthetic supplier facts and mocked persistence; not MySQL/Sandbox evidence. */
class StripePaymentServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final JdbcFencedCreditsLedger ledger=mock(JdbcFencedCreditsLedger.class);
    private final StripeCheckoutGateway gateway=mock(StripeCheckoutGateway.class);
    private StripePaymentService service;
    private Map<String,Object> row;
    @BeforeEach void fixture() {
        service=new StripePaymentService(db,mock(TransactionTemplate.class),ledger,new PaymentCatalog(new MockEnvironment()),gateway,json);
        when(gateway.provider()).thenReturn("STRIPE_SANDBOX");when(gateway.environment()).thenReturn("sandbox");
        when(gateway.merchantId()).thenReturn("acct_offline12345678");when(gateway.isLive()).thenReturn(false);
        row=new HashMap<>(Map.of("id","order-fixture","creator_id","owner-fixture","amount",new BigDecimal("9.90"),"currency","USD","credits",100L,"status","PAID","provider_capture_id","pi_offline12345678","provider","STRIPE_SANDBOX"));
    }
    private JsonNode node(String text){return json.readTree(text);}
    private Resolution refund(String amount,String currency,String status) {
        when(gateway.retrieveRefund("re_offline12345678")).thenReturn(node("{\"id\":\"re_offline12345678\",\"status\":\""+status+"\",\"payment_intent\":\"pi_offline12345678\",\"amount\":"+amount+",\"currency\":\""+currency+"\"}"));
        when(db.queryForList("SELECT * FROM payment_order WHERE provider=? AND provider_capture_id=?","STRIPE_SANDBOX","pi_offline12345678")).thenReturn(List.of(row));
        return ReflectionTestUtils.invokeMethod(service,"reconcileRefund","re_offline12345678");
    }
    private Resolution dispute(String status,String balances) {
        when(gateway.retrieveDispute("dp_offline12345678")).thenReturn(node("{\"id\":\"dp_offline12345678\",\"payment_intent\":\"pi_offline12345678\",\"status\":\""+status+"\",\"amount\":198,\"currency\":\"usd\",\"balance_transactions\":"+balances+"}"));
        when(db.queryForList("SELECT * FROM payment_order WHERE provider=? AND provider_capture_id=?","STRIPE_SANDBOX","pi_offline12345678")).thenReturn(List.of(row));
        return ReflectionTestUtils.invokeMethod(service,"reconcileDispute","dp_offline12345678");
    }
    @Test void successfulRefundUsesAuthoritativeMinorUnitsAndBoundOrderIdentity() {
        var result=refund("198","usd","succeeded");assertTrue(result.verified());result.apply().run();
        verify(ledger).paymentRefund("STRIPE","acct_offline12345678","sandbox","re_offline12345678","owner-fixture","order-fixture","pi_offline12345678",198L,"USD");
    }
    @Test void failedRefundCannotPermanentlyChangeCredits() {
        var result=refund("198","usd","failed");assertTrue(result.verified());result.apply().run();
        verify(ledger).recordFailedRefund("STRIPE","acct_offline12345678","sandbox","re_offline12345678","owner-fixture","order-fixture","pi_offline12345678",198L,"USD","failed","stripe-refund-terminal:re_offline12345678:failed");
        verify(ledger,never()).paymentRefund(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString());
        verify(ledger,never()).compensateRefundReturnedFunds(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void pendingRefundCannotPermanentlyChangeCredits() {
        var result=refund("198","usd","pending");if(result.apply()!=null)result.apply().run();verifyNoInteractions(ledger);
    }
    @Test void mismatchedCurrencyOrNonPositiveRefundCannotReachLedger() {
        assertThrows(IllegalStateException.class,()->refund("198","gbp","succeeded"));
        assertThrows(IllegalStateException.class,()->refund("0","usd","succeeded"));verifyNoInteractions(ledger);
    }
    @Test void refundCannotRouteToUnpaidOrder() {
        row.put("status","PENDING");assertThrows(IllegalStateException.class,()->refund("198","usd","succeeded"));verifyNoInteractions(ledger);
    }
    @Test void duplicateCaptureIdentityAcrossOrdersRequiresManualResolution() {
        when(gateway.retrieveRefund("re_offline12345678")).thenReturn(node("{\"id\":\"re_offline12345678\",\"status\":\"succeeded\",\"payment_intent\":\"pi_offline12345678\",\"amount\":198,\"currency\":\"usd\"}"));
        when(db.queryForList("SELECT * FROM payment_order WHERE provider=? AND provider_capture_id=?","STRIPE_SANDBOX","pi_offline12345678")).thenReturn(List.of(row,new HashMap<>(row)));
        assertThrows(IllegalStateException.class,()->ReflectionTestUtils.invokeMethod(service,"reconcileRefund","re_offline12345678"));verifyNoInteractions(ledger);
    }
    @Test void onlyFinalLossWithAttributableNativeStripeBalanceArrayMayRevokeCredits() {
        var result=dispute("lost","[{\"id\":\"txn_loss12345678\",\"source\":\"dp_offline12345678\",\"type\":\"adjustment\",\"currency\":\"usd\",\"amount\":-198}]");
        assertTrue(result.verified());result.apply().run();
        verify(ledger).paymentDispute(eq("STRIPE"),eq("acct_offline12345678"),eq("sandbox"),eq("dp_offline12345678"),eq("owner-fixture"),eq("order-fixture"),eq("pi_offline12345678"),eq(198L),eq("USD"),anyLong(),eq("LOST"),eq(198L));
    }
    @Test void returnedBalanceMovementReducesActualLossAndFeesAreExcluded() {
        var result=dispute("lost","[{\"source\":\"dp_offline12345678\",\"type\":\"adjustment\",\"currency\":\"usd\",\"amount\":-198},{\"source\":\"dp_offline12345678\",\"type\":\"adjustment\",\"currency\":\"usd\",\"amount\":99},{\"source\":\"dp_offline12345678\",\"type\":\"stripe_fee\",\"currency\":\"usd\",\"amount\":-1500}]");
        result.apply().run();
        verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(198L),eq("USD"),anyLong(),eq("LOST"),eq(99L));
    }
    @Test void unverifiedOrWrongCurrencyFinalLossStaysUnknownAndCannotRevoke() {
        var result=dispute("lost","[{\"source\":\"dp_other12345678\",\"type\":\"adjustment\",\"currency\":\"usd\",\"amount\":-198},{\"source\":\"dp_offline12345678\",\"type\":\"adjustment\",\"currency\":\"gbp\",\"amount\":-198}]");
        result.apply().run();
        verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(198L),eq("USD"),anyLong(),eq("UNKNOWN"),isNull());
    }
    @Test void wonUnderReviewAndUnrecognizedClosedStatesNeverApplyFinalLoss() {
        for(String status:List.of("won","under_review","closed")) {
            var result=dispute(status,"[]");result.apply().run();
        }
        verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(198L),eq("USD"),anyLong(),eq("WON"),isNull());
        verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(198L),eq("USD"),anyLong(),eq("UNDER_REVIEW"),isNull());
        verify(ledger).paymentDispute(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),eq(198L),eq("USD"),anyLong(),eq("UNKNOWN"),isNull());
    }
    @Test void overflowNativeAmountCannotWrapIntoAValidSameCurrencyLoss(){
        assertThrows(RuntimeException.class,()->dispute("lost","[{\"source\":\"dp_offline12345678\",\"type\":\"adjustment\",\"currency\":\"usd\",\"amount\":18446744073709551418}]"));
        verifyNoInteractions(ledger);
    }
    @Test void fractionalRefundMinorUnitsCannotBeTruncatedIntoCreditDeduction(){
        assertThrows(IllegalStateException.class,()->refund("198.5","usd","succeeded"));verifyNoInteractions(ledger);
    }
    @Test void ownerAndProviderBoundLookupCannotReadAnotherUsersOrPaypalsOrder() {
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?","order-fixture","stranger","STRIPE_SANDBOX")).thenReturn(List.of());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.order(new CreatorPrincipal("stranger"),"order-fixture")).getStatusCode().value());
        verifyNoInteractions(ledger);verify(gateway,never()).retrieveSession(anyString());
    }
    @Test void unknownCreationOlderThanSafeReplayWindowNeverCreatesAnotherSession() {
        row.put("status","CREATING");row.put("created_at",Timestamp.from(Instant.now().minusSeconds(21*3600)));
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?","order-fixture","owner-fixture","STRIPE_SANDBOX")).thenReturn(List.of(row));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->ReflectionTestUtils.invokeMethod(service,"createOrResume","owner-fixture","order-fixture")).getStatusCode().value());
        verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }

    private void checkoutOrder(String status,String sessionId) {
        row.put("status",status);row.put("created_at",Timestamp.from(Instant.now()));
        if(sessionId==null)row.remove("provider_link_id");else row.put("provider_link_id",sessionId);
        when(db.queryForList("SELECT * FROM payment_order WHERE id=? AND creator_id=? AND provider=?","order-fixture","owner-fixture","STRIPE_SANDBOX")).thenReturn(List.of(row));
        ReflectionTestUtils.setField(service,"enabled",true);
        when(gateway.configured()).thenReturn(true);
        for(var check:CreditSchemaReadiness.CHECKS.values())when(db.queryForObject(check.sql(),Integer.class)).thenReturn(check.expected());
        when(db.queryForObject("SELECT COUNT(*) FROM payment_order WHERE provider IN ('STRIPE_SANDBOX','STRIPE_LIVE') AND provider<>? AND status IN ('CREATING','CREATION_UNKNOWN','PENDING')",Integer.class,"STRIPE_SANDBOX")).thenReturn(0);
    }
    private JsonNode openCheckout(String url) {
        return json.valueToTree(Map.of("id","cs_test_offline12345678","status","open","payment_status","unpaid","url",url));
    }
    @Test void newlyCreatedOfficialCheckoutPreservesOpaqueFragmentInReturnedUrl() {
        checkoutOrder("CREATING",null);
        String url="https://checkout.stripe.com/c/pay/cs_test_offline12345678#fidkdWxOYHwnPyd1blpxYHZxWjA0%2Fopaque";
        when(gateway.createSession("order-fixture","Qingfeng music credits",new BigDecimal("9.90"),"USD")).thenReturn(openCheckout(url));
        when(db.update("UPDATE payment_order SET provider_link_id=?,status=CASE WHEN status IN ('CREATING','CREATION_UNKNOWN') THEN 'PENDING' ELSE status END WHERE id=? AND provider=? AND (provider_link_id IS NULL OR provider_link_id=?)",
            "cs_test_offline12345678","order-fixture","STRIPE_SANDBOX","cs_test_offline12345678")).thenAnswer(invocation->{
                row.put("provider_link_id","cs_test_offline12345678");row.put("status","PENDING");return 1;
            });
        Map<String,Object> result=ReflectionTestUtils.invokeMethod(service,"createOrResume","owner-fixture","order-fixture");
        assertNotNull(result);assertEquals(url,result.get("checkoutUrl"));assertEquals("order-fixture",result.get("orderId"));assertEquals("PENDING",result.get("status"));
        verify(gateway,times(1)).createSession("order-fixture","Qingfeng music credits",new BigDecimal("9.90"),"USD");
        verify(gateway,never()).retrieveSession(anyString());verifyNoInteractions(ledger);
    }
    @Test void resumeOfficialCheckoutPreservesOpaqueFragmentWithoutCreatingAnotherSession() {
        checkoutOrder("PENDING","cs_test_offline12345678");
        String url="https://checkout.stripe.com/c/pay/cs_test_offline12345678#fidkdWxOYHwnPyd1blpxYHZxWjA0%2Fopaque";
        when(gateway.retrieveSession("cs_test_offline12345678")).thenReturn(openCheckout(url));
        Map<String,Object> result=service.resume(new CreatorPrincipal("owner-fixture"),"order-fixture");
        assertEquals(url,result.get("checkoutUrl"));assertEquals("order-fixture",result.get("orderId"));assertEquals("PENDING",result.get("status"));
        verify(gateway,times(1)).retrieveSession("cs_test_offline12345678");
        verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }
    private List<String> untrustedCheckoutUrls() {
        return List.of("http://checkout.stripe.com/c/pay/cs_test_offline#fid",
            "https://checkout.stripe.com.evil.example/c/pay/cs_test_offline#fid",
            "https://evil.example/checkout.stripe.com#fid",
            "https://attacker@checkout.stripe.com/c/pay/cs_test_offline#fid",
            "https://checkout.stripe.com@evil.example/c/pay/cs_test_offline#fid",
            "https://checkout.stripe.com:443/c/pay/cs_test_offline#fid",
            "https://checkout.stripe.com:8443/c/pay/cs_test_offline#fid",
            "https://checkout.stripe.com/c/pay/cs_test_offline#invalid%ZZ");
    }
    @Test void newCheckoutStillRejectsUntrustedOriginsAndMalformedFragments() {
        checkoutOrder("CREATING",null);
        for(String url:untrustedCheckoutUrls()) {
            when(gateway.createSession("order-fixture","Qingfeng music credits",new BigDecimal("9.90"),"USD")).thenReturn(openCheckout(url));
            ResponseStatusException failure=assertThrows(ResponseStatusException.class,()->ReflectionTestUtils.invokeMethod(service,"createOrResume","owner-fixture","order-fixture"),url);
            assertEquals(502,failure.getStatusCode().value(),url);assertFalse(row.containsKey("provider_link_id"),url);
        }
        verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(ledger);
    }
    @Test void resumeStillWithholdsUntrustedOriginsAndMalformedFragments() {
        checkoutOrder("PENDING","cs_test_offline12345678");
        for(String url:untrustedCheckoutUrls()) {
            when(gateway.retrieveSession("cs_test_offline12345678")).thenReturn(openCheckout(url));
            Map<String,Object> result=service.resume(new CreatorPrincipal("owner-fixture"),"order-fixture");
            assertFalse(result.containsKey("checkoutUrl"),url);assertEquals("PENDING",result.get("status"),url);
        }
        verify(gateway,never()).createSession(anyString(),anyString(),any(),anyString());verifyNoInteractions(ledger);
    }
}
