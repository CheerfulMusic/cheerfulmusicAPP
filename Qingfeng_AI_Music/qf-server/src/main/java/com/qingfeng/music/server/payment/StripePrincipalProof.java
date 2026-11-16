package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pure, fail-closed proof of a complete USD principal loss and its exact native settlement return.
 * No exchange-rate arithmetic, persistence, HTTP, or partial/overlapping-loss inference is performed.
 * Scope on Evidence must describe the authenticated gateway that actually performed every GET;
 * expectedScope must come from the persisted payment binding, not untrusted webhook metadata.
 */
public final class StripePrincipalProof {
    private static final ObjectMapper JSON=new ObjectMapper();
    private StripePrincipalProof() {}

    public record Scope(String merchant,String environment,boolean live) {}
    public record Order(String orderId,String paymentIntentId,String currency,long paidMinor) {}
    public record Evidence(Scope scope,JsonNode dispute,JsonNode charge,JsonNode chargeBalance,
                           JsonNode refundList,JsonNode disputeList) {}
    public record Loss(long lossMinor,String withdrawalId,String chargeBalanceId,
                       String settlementCurrency,long settlementPrincipalMinor,String evidenceSummary) {}
    public record Returned(long returnedMinor,String transactionId,boolean available) {}
    private record Context(String disputeId,String chargeId,String settlement,long principal,
                           String chargeBalanceId,JsonNode dispute,JsonNode chargeBalance) {}

    /** Only an entire, unreimbursed order with one unambiguous native withdrawal can become loss. */
    public static Loss loss(Scope expectedScope,Order order,Evidence evidence) {
        Context context=validate(expectedScope,order,evidence,"lost");
        JsonNode balances=array(context.dispute(),"balance_transactions");
        require(balances.size()==1,"single unreimbursed withdrawal required");
        JsonNode withdrawal=balances.get(0);
        String withdrawalId=withdrawal(context,withdrawal);
        return new Loss(order.paidMinor(),withdrawalId,context.chargeBalanceId(),context.settlement(),
            context.principal(),summary(expectedScope,order,context,withdrawal));
    }

    /** A committed local loss and its original native evidence are mandatory, even after WON.
     * A fully attributed positive movement still pending is returned as available=false: wait only.
     */
    public static Returned returned(Scope expectedScope,Order order,Evidence evidence,Loss recordedLoss) {
        Context context=validate(expectedScope,order,evidence,"won");
        require(recordedLoss!=null&&recordedLoss.lossMinor()==order.paidMinor()
            &&recordedLoss.settlementPrincipalMinor()==context.principal()
            &&Objects.equals(recordedLoss.settlementCurrency(),context.settlement())
            &&Objects.equals(recordedLoss.chargeBalanceId(),context.chargeBalanceId()),"recorded full loss evidence mismatch");
        JsonNode balances=array(context.dispute(),"balance_transactions");
        require(balances.size()==2,"complete native withdrawal/return pair required");
        JsonNode negative=null,positive=null;
        for(JsonNode movement:balances) {
            long amount=integer(movement.path("amount"));
            if(amount<0) { require(negative==null,"multiple withdrawals");negative=movement; }
            else if(amount>0) { require(positive==null,"multiple returns");positive=movement; }
            else throw rejected("zero principal movement");
        }
        require(negative!=null&&positive!=null,"withdrawal and return required");
        String withdrawalId=withdrawal(context,negative);
        require(withdrawalId.equals(recordedLoss.withdrawalId()),"recorded withdrawal identity mismatch");
        String returnId=movement(positive,"adjustment",context.disputeId(),context.settlement());
        require(!returnId.equals(withdrawalId)&&!returnId.equals(context.chargeBalanceId()),"duplicate balance transaction identity");
        require(integer(positive.path("amount"))==context.principal(),"complete native principal return required");
        if("USD".equals(context.settlement()))require(positive.path("exchange_rate").isNull(),"same-currency return exchange evidence is ambiguous");
        return new Returned(order.paidMinor(),returnId,"available".equals(text(positive.path("status"))));
    }

    private static Context validate(Scope expected,Order order,Evidence evidence,String finalState) {
        require(expected!=null&&expected.merchant()!=null&&expected.merchant().matches("acct_[A-Za-z0-9]{8,64}"),"merchant scope required");
        require(("sandbox".equals(expected.environment())&&!expected.live())
            ||("live".equals(expected.environment())&&expected.live()),"consistent environment required");
        require(evidence!=null&&expected.equals(evidence.scope()),"authenticated read scope mismatch");
        require(order!=null&&order.orderId()!=null&&!order.orderId().isBlank()&&order.orderId().length()<=128
            &&validId(order.paymentIntentId(),"pi_")&&"USD".equals(order.currency())&&order.paidMinor()>0,"complete USD order snapshot required");
        JsonNode dispute=object(evidence.dispute(),"dispute"),charge=object(evidence.charge(),"charge");
        String disputeId=id(dispute,"du_","dp_"),chargeId=id(charge,"ch_");
        mode(dispute,expected.live());mode(charge,expected.live());
        require(finalState.equals(text(dispute.path("status"))),"authoritative final dispute outcome required");
        require(chargeId.equals(reference(dispute.path("charge")))
            &&order.paymentIntentId().equals(reference(dispute.path("payment_intent")))
            &&order.paymentIntentId().equals(reference(charge.path("payment_intent"))),"payment/dispute identity mismatch");
        require("succeeded".equals(text(charge.path("status")))&&trueBoolean(charge.path("paid"))
            &&trueBoolean(charge.path("captured")),"complete captured payment required");
        require(integer(charge.path("amount"))==order.paidMinor()&&integer(charge.path("amount_captured"))==order.paidMinor()
            &&integer(dispute.path("amount"))==order.paidMinor(),"full original principal required");
        require(order.currency().equals(currency(charge.path("currency")))&&order.currency().equals(currency(dispute.path("currency"))),"presentment currency mismatch");
        require(charge.path("refunded").isBoolean()&&!charge.path("refunded").asBoolean()
            &&integer(charge.path("amount_refunded"))==0,"refund overlap requires separate resolution");
        JsonNode refunds=completeList(evidence.refundList());
        require(refunds.isEmpty(),"any refund requires separate resolution");
        JsonNode disputes=completeList(evidence.disputeList());
        require(disputes.size()==1,"multiple or missing disputes require separate resolution");
        JsonNode listed=object(disputes.get(0),"dispute");mode(listed,expected.live());
        require(disputeId.equals(id(listed,"du_","dp_"))&&chargeId.equals(reference(listed.path("charge")))
            &&order.paymentIntentId().equals(reference(listed.path("payment_intent")))
            &&order.currency().equals(currency(listed.path("currency")))&&integer(listed.path("amount"))==order.paidMinor()
            &&finalState.equals(text(listed.path("status"))),"dispute listing does not match authoritative resource");
        require(financialSnapshot(dispute).equals(financialSnapshot(listed)),"dispute changed during independent reads");
        JsonNode original=object(evidence.chargeBalance(),"balance_transaction");
        String settlement=currency(original.path("currency"));
        // Initial launch deliberately admits only the observed USD-to-GBP pair and same-USD proof.
        require("USD".equals(settlement)||"GBP".equals(settlement),"unsupported native settlement currency");
        String originalId=movement(original,"charge",chargeId,settlement);
        require(originalId.equals(reference(charge.path("balance_transaction"))),"original charge settlement identity mismatch");
        long principal=integer(original.path("amount"));require(principal>0,"positive original settlement principal required");
        if("USD".equals(settlement)) {
            require(principal==order.paidMinor()&&original.path("exchange_rate").isNull(),"same-currency principal mismatch");
        }
        return new Context(disputeId,chargeId,settlement,principal,originalId,dispute,original);
    }

    private static String withdrawal(Context context,JsonNode value) {
        String id=movement(value,"adjustment",context.disputeId(),context.settlement());
        require("available".equals(text(value.path("status"))),"settled withdrawal required");
        require(!id.equals(context.chargeBalanceId()),"duplicate original/withdrawal identity");
        require(integer(value.path("amount"))==-context.principal(),"native withdrawal does not exactly match original principal");
        if("USD".equals(context.settlement()))require(value.path("exchange_rate").isNull(),"same-currency exchange evidence is ambiguous");
        return id;
    }

    private static String movement(JsonNode value,String type,String source,String settlement) {
        object(value,"balance_transaction");String id=id(value,"txn_");
        require(type.equals(text(value.path("type")))&&source.equals(reference(value.path("source")))
            &&settlement.equals(currency(value.path("currency"))),"native movement attribution mismatch");
        long amount=integer(value.path("amount")),fee=integer(value.path("fee")),net=integer(value.path("net"));
        try { require(Math.subtractExact(amount,fee)==net,"fee/net reconciliation mismatch"); }
        catch(ArithmeticException invalid) { throw rejected("fee/net integer overflow"); }
        require("pending".equals(text(value.path("status")))||"available".equals(text(value.path("status"))),"known funds availability required");
        JsonNode exchange=value.get("exchange_rate");
        require(exchange!=null,"explicit exchange-rate evidence required");
        if(!exchange.isNull()) {
            require(exchange.isNumber(),"invalid exchange-rate evidence");
            try { require(new BigDecimal(exchange.toString()).signum()>0,"positive exchange-rate evidence required"); }
            catch(NumberFormatException invalid) { throw rejected("invalid exchange-rate evidence"); }
        }
        return id;
    }

    private static JsonNode completeList(JsonNode value) {
        object(value,"list");require(value.path("has_more").isBoolean()&&!value.path("has_more").asBoolean(),"complete unpaginated evidence required");
        return array(value,"data");
    }
    private static JsonNode object(JsonNode value,String type) {
        require(value!=null&&value.isObject()&&type.equals(text(value.path("object"))),"typed resource object required");return value;
    }
    private static JsonNode array(JsonNode value,String field) {
        JsonNode result=value.path(field);require(result.isArray(),"native array evidence required");return result;
    }
    private static void mode(JsonNode node,boolean live) {
        require(node.path("livemode").isBoolean()&&node.path("livemode").asBoolean()==live,"payment mode mismatch");
    }
    private static boolean trueBoolean(JsonNode value) { return value.isBoolean()&&value.asBoolean(); }
    private static String currency(JsonNode value) {
        String currency=text(value);require(currency.matches("[A-Za-z]{3}"),"currency required");return currency.toUpperCase(Locale.ROOT);
    }
    private static String id(JsonNode value,String... prefixes) {
        String id=text(value.path("id"));for(String prefix:prefixes)if(validId(id,prefix))return id;throw rejected("resource identity required");
    }
    private static boolean validId(String id,String prefix) { return id!=null&&id.startsWith(prefix)&&id.length()>prefix.length()&&id.length()<=128&&id.matches("[A-Za-z0-9_]+"); }
    private static String reference(JsonNode value) {
        return value!=null&&value.isString()?value.asString():value!=null&&value.isObject()?text(value.path("id")):"";
    }
    private static String text(JsonNode value) { return value!=null&&value.isString()?value.asString():""; }
    private static long integer(JsonNode value) {
        require(value!=null&&value.isIntegralNumber(),"exact financial integer required");
        try { return new BigInteger(value.toString()).longValueExact(); }
        catch(ArithmeticException invalid) { throw rejected("financial integer overflow"); }
    }
    private static void require(boolean condition,String reason) { if(!condition)throw rejected(reason); }
    private static IllegalStateException rejected(String reason) { return new IllegalStateException("Stripe principal proof rejected: "+reason); }

    private static Map<String,Object> safeMovement(JsonNode value) {
        Map<String,Object> safe=new LinkedHashMap<>();
        for(String field:new String[]{"id","object","type","amount","currency","fee","net","status","exchange_rate"})safe.put(field,value.path(field));
        safe.put("source",reference(value.path("source")));return safe;
    }
    private static String financialSnapshot(JsonNode dispute){
        var snapshot=new java.util.TreeMap<String,Map<String,Object>>();
        for(JsonNode movement:array(dispute,"balance_transactions")){
            String id=id(movement,"txn_");
            require(snapshot.put(id,safeMovement(movement))==null,"duplicate native movement identity");
        }
        return JSON.writeValueAsString(snapshot);
    }
    private static String summary(Scope scope,Order order,Context context,JsonNode withdrawal) {
        return JSON.writeValueAsString(Map.of("version",1,"scope",Map.of("merchant",scope.merchant(),"environment",scope.environment(),"live",scope.live()),
            "order_id",order.orderId(),"payment_intent",order.paymentIntentId(),"charge",context.chargeId(),"dispute",context.disputeId(),
            "order_loss_minor",order.paidMinor(),"order_currency",order.currency(),"charge_balance",safeMovement(context.chargeBalance()),"withdrawal",safeMovement(withdrawal)));
    }
}
