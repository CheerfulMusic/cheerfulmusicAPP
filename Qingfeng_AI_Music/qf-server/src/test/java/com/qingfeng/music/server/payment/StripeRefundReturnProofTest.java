package com.qingfeng.music.server.payment;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic balance facts exercise the proof, not remote Stripe acceptance. */
class StripeRefundReturnProofTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode node(Map<String,Object> value){return json.valueToTree(value);}
    private Map<String,Object> refund(){return new HashMap<>(Map.of("id","re_test1","object","refund","status","failed","amount",198,"currency","usd","payment_intent","pi_test1","charge","ch_test1","balance_transaction","txn_debit","failure_balance_transaction","txn_return"));}
    private Map<String,Object> charge(){return new HashMap<>(Map.of("id","ch_test1","object","charge","payment_intent","pi_test1","livemode",false,"paid",true,"captured",true,"status","succeeded","amount",990,"amount_captured",990,"currency","usd"));}
    private Map<String,Object> movement(boolean returned){return new HashMap<>(Map.of("id",returned?"txn_return":"txn_debit","object","balance_transaction","type",returned?"refund_failure":"refund","source","re_test1","amount",returned?149:-149,"fee",0,"net",returned?149:-149,"currency","gbp","status","available"));}
    private StripeRefundReturnProof.Returned verify(Map<String,Object> r,Map<String,Object> c,Map<String,Object> d,Map<String,Object> p){return StripeRefundReturnProof.verify(false,"re_test1","pi_test1",198,"USD",990,node(r),node(c),node(d),node(p));}
    @Test void completeNativeReversalRestoresOriginalRefundPrincipalWithoutConvertingCurrency(){
        var proof=verify(refund(),charge(),movement(false),movement(true));
        assertEquals("txn_return",proof.transactionId());assertTrue(proof.available());assertTrue(proof.evidence().contains("GBP:149:USD:198"));
    }
    @Test void pendingReturnIsProvenButNotAvailable(){var p=movement(true);p.put("status","pending");assertFalse(verify(refund(),charge(),movement(false),p).available());}
    @Test void netAndFeeNeverBecomePrincipal(){var p=movement(true);p.put("fee",15);p.put("net",134);assertTrue(verify(refund(),charge(),movement(false),p).available());p.put("net",149);assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),p));}
    @Test void sameCurrencyMustMatchOriginalRefundAmount(){var d=movement(false);var p=movement(true);d.put("currency","usd");p.put("currency","usd");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),d,p));d.put("amount",-198);d.put("net",-198);p.put("amount",198);p.put("net",198);assertTrue(verify(refund(),charge(),d,p).available());}
    @Test void partialOrOverReturnCannotRestoreAnything(){for(int amount:List.of(148,150)){var p=movement(true);p.put("amount",amount);p.put("net",amount);assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),p));}}
    @Test void crossRefundCrossIntentAndCrossChargeAreRejected(){for(String key:List.of("id","payment_intent","charge")){var r=refund();r.put(key,"other_identity");assertThrows(IllegalStateException.class,()->verify(r,charge(),movement(false),movement(true)));}}
    @Test void nativeBalanceIdsAndSourcesAreBound(){for(String key:List.of("id","source","type","object")){var p=movement(true);p.put(key,"other_identity");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),p));}}
    @Test void duplicateNativeIdentityCannotBecomeRefundReturn(){var r=refund();r.put("failure_balance_transaction","txn_debit");var p=movement(true);p.put("id","txn_debit");assertThrows(IllegalStateException.class,()->verify(r,charge(),movement(false),p));}
    @Test void missingIntegerFractionAndOverflowFailClosed(){for(Object value:List.of("149",149.1,"9223372036854775808")){var p=movement(true);p.put("amount",value);assertThrows(RuntimeException.class,()->verify(refund(),charge(),movement(false),p));}var p=movement(true);p.remove("amount");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),p));}
    @Test void unknownStateMissingSourceAndDifferentNativeCurrencyFailClosed(){var p=movement(true);p.put("status","unknown");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),p));var missing=movement(true);missing.remove("source");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),missing));var mismatch=movement(true);mismatch.put("currency","eur");assertThrows(IllegalStateException.class,()->verify(refund(),charge(),movement(false),mismatch));}
    @Test void paymentModePartialCaptureAndUnpaidChargeAreRejected(){for(String key:List.of("livemode","paid","captured","amount_captured","currency")){var c=charge();c.put(key,switch(key){case "livemode"->true;case "paid","captured"->false;case "amount_captured"->198;default->"gbp";});assertThrows(IllegalStateException.class,()->verify(refund(),c,movement(false),movement(true)));}}
    @Test void explicitRefundLiveModeCannotHideBehindTestCharge(){var r=refund();r.put("livemode",true);assertThrows(IllegalStateException.class,()->verify(r,charge(),movement(false),movement(true)));}
    @Test void nonTerminalStatusCannotRestoreCredits(){for(String status:List.of("succeeded","pending","requires_action","unknown")){var r=refund();r.put("status",status);assertThrows(IllegalStateException.class,()->verify(r,charge(),movement(false),movement(true)));}}
}
