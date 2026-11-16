package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PayPalEntitlementAdapterTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String refund(String status){return "{\"id\":\"REF123\",\"status\":\""+status+"\",\"amount\":{\"value\":\"1.98\",\"currency_code\":\"USD\"},\"links\":[{\"rel\":\"up\",\"href\":\"https://api-m.sandbox.paypal.com/v2/payments/captures/CAP123\"}]}";}
    private static String dispute(String status,String outcome){return "{\"dispute_id\":\"PP-D-123\",\"status\":\""+status+"\",\"update_time\":\"2026-11-12T10:00:00Z\",\"disputed_transactions\":[{\"seller_transaction_id\":\"CAP123\"}],\"dispute_amount\":{\"value\":\"1.98\",\"currency_code\":\"USD\"},\"dispute_outcome\":{\"outcome_code\":\""+outcome+"\"}}";}
    @Test void successfulPartialRefundUsesExactMinorUnitsAndOriginalPayment(){
        var f=PayPalEntitlementAdapter.refund(JSON.readTree(refund("COMPLETED")),"sandbox");var ledger=mock(JdbcFencedCreditsLedger.class);
        assertEquals(198,f.amount());assertEquals("CAP123",f.capture());
        PayPalEntitlementAdapter.apply(ledger,"MERCHANT","sandbox","owner","order",f,false);
        verify(ledger).paymentRefund("PAYPAL","MERCHANT","sandbox","REF123","owner","order","CAP123",198,"USD");verifyNoMoreInteractions(ledger);
    }
    @Test void pendingOrFailedIsNeverASuccessfulRefund(){
        for(String state:new String[]{"PENDING","FAILED","CANCELLED",""}){
            var ledger=mock(JdbcFencedCreditsLedger.class);var f=PayPalEntitlementAdapter.refund(JSON.readTree(refund(state)),"sandbox");
            assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.apply(ledger,"MERCHANT","sandbox","owner","order",f,false));verifyNoInteractions(ledger);
        }
    }
    @Test void sandboxRefundCannotBindALiveOrUntrustedCaptureUrl(){
        for(String wrong:new String[]{"https://api-m.paypal.com","https://evil.example","http://api-m.sandbox.paypal.com","https://user@api-m.sandbox.paypal.com","https://api-m.sandbox.paypal.com:123"})
            assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.refund(JSON.readTree(refund("COMPLETED").replace("https://api-m.sandbox.paypal.com",wrong)),"sandbox"));
    }
    @Test void refundMustMatchRelatedIdAndExactMoney(){
        String r=refund("COMPLETED");
        assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.refund(JSON.readTree(r.replace("1.98","1.981")),"sandbox"));
        assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.refund(JSON.readTree(r.replace("1.98","0.00")),"sandbox"));
        assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.refund(JSON.readTree(r.replace("\"links\":","\"supplementary_data\":{\"related_ids\":{\"capture_id\":\"OTHER\"}},\"links\":")),"sandbox"));
    }
    @Test void onlySellerWinReleasesAnOrderHoldWithoutACharge(){
        for(String outcome:new String[]{"RESOLVED_SELLER_FAVOUR","CANCELED_BY_BUYER"}){
            var f=PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("RESOLVED",outcome)));var ledger=mock(JdbcFencedCreditsLedger.class);
            assertEquals("WON",f.state());PayPalEntitlementAdapter.apply(ledger,"MERCHANT","sandbox","owner","order",f,true);
            verify(ledger).paymentDispute("PAYPAL","MERCHANT","sandbox","PP-D-123","owner","order","CAP123",198,"USD",f.version(),"WON",null);verifyNoMoreInteractions(ledger);
        }
    }
    @Test void buyerOutcomeDoesNotInventActualPermanentPrincipalLoss(){
        var f=PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("RESOLVED","RESOLVED_BUYER_FAVOUR")));var ledger=mock(JdbcFencedCreditsLedger.class);
        assertEquals("LOST",f.state());PayPalEntitlementAdapter.apply(ledger,"MERCHANT","sandbox","owner","order",f,true);
        verify(ledger).paymentDispute("PAYPAL","MERCHANT","sandbox","PP-D-123","owner","order","CAP123",198,"USD",f.version(),"LOST",null);verifyNoMoreInteractions(ledger);
    }
    @Test void closedOrUnknownOutcomeNeverMeansLost(){
        assertEquals("UNKNOWN",PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("CLOSED","RESOLVED_BUYER_FAVOUR"))).state());
        assertEquals("UNKNOWN",PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("RESOLVED",""))).state());
        assertEquals("OPEN",PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("WAITING_FOR_SELLER_RESPONSE",""))).state());
        assertEquals("UNDER_REVIEW",PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("UNDER_REVIEW",""))).state());
    }
    @Test void oneDisputeCannotFreezeSeveralOriginalPayments(){
        String resource=dispute("OPEN","").replace("\"disputed_transactions\":[{\"seller_transaction_id\":\"CAP123\"}]","\"disputed_transactions\":[{\"seller_transaction_id\":\"CAP123\"},{\"seller_transaction_id\":\"CAP456\"}]");
        assertThrows(IllegalStateException.class,()->PayPalEntitlementAdapter.dispute(JSON.readTree(resource)));
        assertThrows(RuntimeException.class,()->PayPalEntitlementAdapter.dispute(JSON.readTree(dispute("OPEN","").replace("2026-11-12T10:00:00Z",""))));
    }
}
