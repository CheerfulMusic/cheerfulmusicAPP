package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authoritative resources only. No HTTP, database, credentials, or Sandbox assertion. */
class StripePrincipalProofTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final StripePrincipalProof.Scope SCOPE=new StripePrincipalProof.Scope("acct_offline12345678","sandbox",false);
    private static final StripePrincipalProof.Order ORDER=new StripePrincipalProof.Order("order-offline","pi_offline12345678","USD",990);
    private static final String DISPUTE="du_offline12345678",CHARGE="ch_offline12345678";

    private static Map<String,Object> movement(String id,String type,String source,String currency,long amount,long fee,String status,Object exchange) {
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("id",id);value.put("object","balance_transaction");value.put("type",type);value.put("source",source);
        value.put("currency",currency);value.put("amount",amount);value.put("fee",fee);value.put("net",Math.subtractExact(amount,fee));
        value.put("status",status);value.put("exchange_rate",exchange);return value;
    }
    private static Map<String,Object> list(List<?> data) {
        return new LinkedHashMap<>(Map.of("object","list","has_more",false,"data",data));
    }
    private static final class Fixture {
        StripePrincipalProof.Scope readScope=SCOPE;
        Map<String,Object> charge=new LinkedHashMap<>();
        Map<String,Object> original=movement("txn_charge12345678","charge",CHARGE,"gbp",746,40,"pending",new BigDecimal("0.753535"));
        Map<String,Object> withdrawal=movement("txn_withdraw12345678","adjustment",DISPUTE,"gbp",-746,2000,"available",null);
        Map<String,Object> dispute=new LinkedHashMap<>();
        Map<String,Object> refunds=list(List.of()),disputes;
        Map<String,Object> returned;
        Fixture() {
            charge.put("id",CHARGE);charge.put("object","charge");charge.put("payment_intent",ORDER.paymentIntentId());
            charge.put("status","succeeded");charge.put("paid",true);charge.put("captured",true);charge.put("livemode",false);
            charge.put("amount",990);charge.put("amount_captured",990);charge.put("currency","usd");charge.put("amount_refunded",0);charge.put("refunded",false);
            charge.put("balance_transaction",original.get("id"));
            charge.put("billing_details",Map.of("email","private-fixture@example.invalid"));
            dispute.put("id",DISPUTE);dispute.put("object","dispute");dispute.put("charge",CHARGE);dispute.put("payment_intent",ORDER.paymentIntentId());
            dispute.put("status","lost");dispute.put("livemode",false);dispute.put("amount",990);dispute.put("currency","usd");
            dispute.put("balance_transactions",List.of(withdrawal));disputes=list(List.of(dispute));
        }
        StripePrincipalProof.Evidence evidence() {
            return new StripePrincipalProof.Evidence(readScope,JSON.valueToTree(dispute),JSON.valueToTree(charge),JSON.valueToTree(original),JSON.valueToTree(refunds),JSON.valueToTree(disputes));
        }
        StripePrincipalProof.Loss loss() { return StripePrincipalProof.loss(SCOPE,ORDER,evidence()); }
        void won(String status) {
            returned=movement("txn_return12345678","adjustment",DISPUTE,"gbp",746,0,status,null);
            dispute.put("status","won");dispute.put("balance_transactions",List.of(withdrawal,returned));
        }
        void sameUsd() {
            original.put("currency","usd");original.put("amount",990);original.put("net",950);original.put("exchange_rate",null);
            withdrawal.put("currency","usd");withdrawal.put("amount",-990);withdrawal.put("net",-2990);
        }
    }
    private static void rejected(Consumer<Fixture> alteration) {
        Fixture f=new Fixture();alteration.accept(f);assertThrows(IllegalStateException.class,f::loss);
    }

    @Test void exactNativeGbpPrincipalProvesWholeUsdLossWithoutUsingFeeOrExchangeArithmetic() {
        Fixture f=new Fixture();var loss=f.loss();
        assertEquals(990,loss.lossMinor());assertEquals(746,loss.settlementPrincipalMinor());assertEquals("GBP",loss.settlementCurrency());
        assertEquals("txn_charge12345678",loss.chargeBalanceId());assertEquals("txn_withdraw12345678",loss.withdrawalId());
        JsonNode summary=JSON.readTree(loss.evidenceSummary());
        assertEquals(1,summary.path("version").asInt());assertEquals(-746,summary.path("withdrawal").path("amount").asLong());
        assertEquals(2000,summary.path("withdrawal").path("fee").asLong());assertEquals(-2746,summary.path("withdrawal").path("net").asLong());
        assertFalse(loss.evidenceSummary().contains("private-fixture"));assertFalse(loss.evidenceSummary().contains("billing_details"));
    }
    @Test void nativeAmountMismatchAtChangedSettlementRateIsNeverRoundedOrConverted() {
        for(long amount:new long[]{-745,-747,-990})rejected(f->{f.withdrawal.put("amount",amount);f.withdrawal.put("net",amount-2000);});
    }
    @Test void completeSameCurrencyProofUsesIdenticalStrictIdentityAndIntegerBoundary() {
        Fixture f=new Fixture();f.sameUsd();var loss=f.loss();assertEquals(990,loss.lossMinor());assertEquals(990,loss.settlementPrincipalMinor());assertEquals("USD",loss.settlementCurrency());
        rejected(x->{x.sameUsd();x.dispute.put("amount",new BigDecimal("990.0"));});
        rejected(x->{x.sameUsd();x.withdrawal.put("source","du_other12345678");});
        rejected(x->{x.sameUsd();x.original.put("exchange_rate",BigDecimal.ONE);});
    }
    @Test void authenticatedMerchantEnvironmentAndExplicitModeMustMatchStoredBinding() {
        rejected(f->f.readScope=new StripePrincipalProof.Scope("acct_another12345678","sandbox",false));
        rejected(f->f.readScope=new StripePrincipalProof.Scope(SCOPE.merchant(),"live",true));
        rejected(f->f.dispute.remove("livemode"));rejected(f->f.charge.put("livemode",true));
        assertThrows(IllegalStateException.class,()->StripePrincipalProof.loss(new StripePrincipalProof.Scope(SCOPE.merchant(),"sandbox",true),ORDER,new Fixture().evidence()));
    }
    @Test void fullFinalCapturedPaymentAndExactPaymentChainAreRequired() {
        List<Consumer<Fixture>> invalid=List.of(f->f.dispute.put("status","under_review"),f->f.dispute.put("status","closed"),
            f->f.dispute.put("status","won"),f->f.dispute.put("amount",198),f->f.charge.put("amount",991),f->f.charge.put("amount_captured",500),
            f->f.charge.put("captured",false),f->f.charge.put("paid",false),f->f.charge.put("paid","true"),f->f.charge.put("status","pending"),
            f->f.dispute.put("charge","ch_another12345678"),f->f.dispute.put("payment_intent","pi_another12345678"),
            f->f.charge.put("payment_intent","pi_another12345678"),f->f.dispute.put("currency","gbp"),f->f.charge.put("currency","gbp"));
        invalid.forEach(StripePrincipalProofTest::rejected);
    }
    @Test void fractionalOverflowMissingAndStringFinancialNumbersCannotTruncateIntoProof() {
        for(Object value:new Object[]{new BigDecimal("990.1"),new BigDecimal("990.0"),"990",new BigInteger("9223372036854775808"),-990,0})
            rejected(f->f.dispute.put("amount",value));
        rejected(f->f.dispute.remove("amount"));rejected(f->f.original.put("amount",new BigDecimal("746.0")));
        rejected(f->f.withdrawal.put("fee",new BigDecimal("2000.1")));rejected(f->f.withdrawal.put("net",-746));
        rejected(f->{f.withdrawal.put("fee",Long.MAX_VALUE);f.withdrawal.put("net",0);});
    }
    @Test void completeRefundAndSingleDisputeInventoriesAreMandatoryEvenIfAmountsLookValid() {
        rejected(f->f.charge.put("amount_refunded",1));rejected(f->f.charge.put("refunded",true));
        rejected(f->f.refunds.put("data",List.of(Map.of("id","re_failed12345678","status","failed"))));
        rejected(f->f.refunds.put("has_more",true));rejected(f->f.refunds.remove("has_more"));
        rejected(f->f.disputes.put("has_more",true));rejected(f->f.disputes.put("data",List.of()));
        rejected(f->f.disputes.put("data",List.of(f.dispute,new LinkedHashMap<>(f.dispute))));
        rejected(f->{var other=new LinkedHashMap<>(f.dispute);other.put("id","du_other12345678");f.disputes.put("data",List.of(other));});
    }
    @Test void movementObjectSourceTypeCurrencyAvailabilityAndExactOriginalLinkAreRequired() {
        List<Consumer<Fixture>> invalid=List.of(f->f.original.put("source","ch_other12345678"),f->f.withdrawal.put("source","du_other12345678"),
            f->f.original.put("type","transfer"),f->f.withdrawal.put("type","refund_failure"),f->f.withdrawal.put("currency","usd"),
            f->f.original.put("currency","eur"),f->f.charge.put("balance_transaction","txn_other12345678"),
            f->f.original.remove("exchange_rate"),f->f.original.put("exchange_rate","0.75"),f->f.original.put("exchange_rate",BigDecimal.ZERO),
            f->f.withdrawal.put("status","unknown"),f->f.withdrawal.remove("object"),f->f.withdrawal.put("id","not_a_txn"));
        invalid.forEach(StripePrincipalProofTest::rejected);
        Fixture f=new Fixture();var e=f.evidence();assertThrows(IllegalStateException.class,()->StripePrincipalProof.loss(SCOPE,ORDER,
            new StripePrincipalProof.Evidence(SCOPE,e.dispute(),e.charge(),JSON.valueToTree("txn_charge12345678"),e.refundList(),e.disputeList())));
    }
    @Test void anyReturnIncludingPendingMultipleOrDuplicateMovementsPreventsLoss() {
        rejected(f->{f.returned=movement("txn_credit12345678","adjustment",DISPUTE,"gbp",746,0,"pending",null);f.dispute.put("balance_transactions",List.of(f.withdrawal,f.returned));});
        rejected(f->f.dispute.put("balance_transactions",List.of(f.withdrawal,f.withdrawal)));
        rejected(f->f.withdrawal.put("id",f.original.get("id")));
        rejected(f->f.dispute.put("balance_transactions",Map.of("data",List.of(f.withdrawal))));
    }
    @Test void completeAvailableReturnMatchesPersistedNativeLossAndRestoresUsdPrincipalOnly() {
        Fixture f=new Fixture();var loss=f.loss();f.won("available");f.returned.put("fee",-100);f.returned.put("net",846);
        var returned=StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss);
        assertEquals(990,returned.returnedMinor());assertEquals("txn_return12345678",returned.transactionId());assertTrue(returned.available());
        assertEquals(returned,StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss));
    }
    @Test void completePendingReturnMustWaitAndMayLaterBecomeAvailableWithoutChangingIdentity() {
        Fixture f=new Fixture();var loss=f.loss();f.won("pending");var waiting=StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss);
        assertFalse(waiting.available());assertEquals(990,waiting.returnedMinor());f.returned.put("status","available");
        var ready=StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss);assertTrue(ready.available());assertEquals(waiting.transactionId(),ready.transactionId());
    }
    @Test void returnRequiresOriginalPersistedFullLossAndBothNativeTransactionIdentities() {
        Fixture f=new Fixture();var p=f.loss();f.won("available");
        List<StripePrincipalProof.Loss> invalid=List.of(new StripePrincipalProof.Loss(198,p.withdrawalId(),p.chargeBalanceId(),"GBP",746,p.evidenceSummary()),
            new StripePrincipalProof.Loss(990,"txn_other12345678",p.chargeBalanceId(),"GBP",746,p.evidenceSummary()),
            new StripePrincipalProof.Loss(990,p.withdrawalId(),"txn_other12345678","GBP",746,p.evidenceSummary()),
            new StripePrincipalProof.Loss(990,p.withdrawalId(),p.chargeBalanceId(),"USD",746,p.evidenceSummary()),
            new StripePrincipalProof.Loss(990,p.withdrawalId(),p.chargeBalanceId(),"GBP",747,p.evidenceSummary()));
        for(var loss:invalid)assertThrows(IllegalStateException.class,()->StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss));
        assertThrows(IllegalStateException.class,()->StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),null));
    }
    @Test void ambiguousPartialFxAndReusedReturnFactsCannotRestoreRights() {
        List<Consumer<Fixture>> invalid=List.of(f->{f.returned.put("amount",745);f.returned.put("net",745);},
            f->{f.returned.put("amount",747);f.returned.put("net",747);},f->f.returned.put("currency","usd"),
            f->f.returned.put("source","du_other12345678"),f->f.returned.put("id",f.withdrawal.get("id")),
            f->f.returned.put("id",f.original.get("id")),f->f.returned.put("status","unknown"),
            f->f.returned.put("amount",new BigDecimal("746.0")),f->f.returned.put("net",745),
            f->f.dispute.put("balance_transactions",List.of(f.withdrawal)),f->f.dispute.put("balance_transactions",List.of(f.withdrawal,f.returned,f.returned)),
            f->f.dispute.put("status","lost"),f->f.charge.put("amount_refunded",198));
        for(var alteration:invalid) { Fixture f=new Fixture();var loss=f.loss();f.won("available");alteration.accept(f);
            assertThrows(IllegalStateException.class,()->StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss)); }
    }
    @Test void sameCurrencyReturnAlsoRejectsInjectedExchangeEvidence() {
        Fixture f=new Fixture();f.sameUsd();var loss=f.loss();f.won("available");
        f.returned.put("currency","usd");f.returned.put("amount",990);f.returned.put("net",990);
        assertTrue(StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss).available());
        f.returned.put("exchange_rate",BigDecimal.ONE);
        assertThrows(IllegalStateException.class,()->StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss));
    }
    @Test void newReadOnlyGatewayResourceMethodsRejectPathInjectionBeforeHttp() {
        StripeCheckoutGateway gateway=new StripeCheckoutGateway(JSON);
        ReflectionTestUtils.setField(gateway,"environment","sandbox");ReflectionTestUtils.setField(gateway,"accountId",SCOPE.merchant());
        ReflectionTestUtils.setField(gateway,"apiKey","sk_test_offline_fixture");ReflectionTestUtils.setField(gateway,"webhookSecret","whsec_offline_fixture");
        ReflectionTestUtils.setField(gateway,"accountVerified",true);ReflectionTestUtils.setField(gateway,"successUrl","");ReflectionTestUtils.setField(gateway,"cancelUrl","");
        for(String value:new String[]{"../other","txn_fixture?secret=x","ch_fixture&starting_after=x",null}) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->gateway.retrieveBalanceTransaction(value)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->gateway.refundsForCharge(value)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->gateway.disputesForCharge(value)).getStatusCode().value());
        }
    }
    @Test void pendingWithdrawalCannotProveFinalLossOrItsLaterReturn(){
        rejected(f->f.withdrawal.put("status","pending"));
        Fixture f=new Fixture();var loss=f.loss();f.won("available");f.withdrawal.put("status","pending");
        assertThrows(IllegalStateException.class,()->StripePrincipalProof.returned(SCOPE,ORDER,f.evidence(),loss));
    }
    @Test void newlyObservedPositiveMovementInLastReadCannotBeIgnoredAsAnOlderLostSnapshot(){
        Fixture f=new Fixture();var listed=new LinkedHashMap<>(f.dispute);
        var credit=movement("txn_newreturn12345678","adjustment",DISPUTE,"gbp",746,0,"pending",null);
        listed.put("balance_transactions",List.of(f.withdrawal,credit));f.disputes.put("data",List.of(listed));
        assertThrows(IllegalStateException.class,f::loss);
    }
}
