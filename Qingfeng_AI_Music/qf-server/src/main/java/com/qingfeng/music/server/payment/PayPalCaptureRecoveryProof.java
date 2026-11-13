package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Original capture evidence from two authoritative GETs, never a webhook/refund assertion.
 * A refunded capture proves receipt of the original principal, not the full refund history. */
final class PayPalCaptureRecoveryProof {
    record Proven(String provider,String merchant,String environment,String owner,String order,
                  String remoteOrder,String capture,BigDecimal amount,String currency,long credits,
                  String observedCaptureStatus,String evidence) {
        void assertCurrent(Map<String,Object> local,String expectedMerchant,String expectedEnvironment){
            require(merchant.equals(expectedMerchant)&&environment.equals(expectedEnvironment)
                &&provider.equals(local.get("provider"))&&owner.equals(local.get("creator_id"))
                &&order.equals(local.get("id"))&&currency.equals(local.get("currency"))
                &&local.get("amount") instanceof BigDecimal stored&&amount.compareTo(stored)==0
                &&local.get("credits") instanceof Number value&&credits==value.longValue()
                &&(local.get("provider_link_id")==null||remoteOrder.equals(local.get("provider_link_id")))
                &&(local.get("provider_capture_id")==null||capture.equals(local.get("provider_capture_id"))),"changed original order snapshot");
        }
    }
    static Proven verify(String merchant,String environment,Map<String,Object> local,
                         PayPalCheckoutGateway.Snapshot snapshot,JsonNode capture){
        require(Set.of("sandbox","live").contains(environment)&&merchant!=null&&!merchant.isBlank(),"merchant scope");
        String provider="PAYPAL_"+environment.toUpperCase(java.util.Locale.ROOT);
        String captureId=text(capture.path("id")),state=text(capture.path("status"));
        String related=text(capture.path("supplementary_data").path("related_ids").path("order_id"));
        require(captureId.matches("[A-Za-z0-9]{1,32}")&&related.matches("[A-Za-z0-9]{1,32}"),"capture/order identity");
        require(Set.of("COMPLETED","PARTIALLY_REFUNDED","REFUNDED").contains(state)
            &&capture.path("final_capture").isBoolean()&&capture.path("final_capture").asBoolean(),"successful final capture");
        require(snapshot!=null&&"COMPLETED".equals(snapshot.orderStatus())&&state.equals(snapshot.captureStatus())
            &&captureId.equals(snapshot.captureId())&&related.equals(snapshot.orderId())
            &&merchant.equals(snapshot.payeeId())&&merchant.equals(text(capture.path("payee").path("merchant_id"))),"authoritative capture/order/payee agreement");
        String currency=text(capture.path("amount").path("currency_code"));
        String value=text(capture.path("amount").path("value"));
        require(Set.of("USD","GBP").contains(currency)&&value.matches("[0-9]{1,12}\\.[0-9]{2}"),"exact original capture amount");
        BigDecimal amount=new BigDecimal(value);
        require(amount.signum()>0&&snapshot.amount()!=null&&amount.compareTo(snapshot.amount())==0
            &&currency.equals(snapshot.currency())&&Objects.equals(local.get("id"),snapshot.reference())
            &&local.get("creator_id") instanceof String owner&&!owner.isBlank()
            &&local.get("credits") instanceof Number number&&number.longValue()>0,"original financial identity");
        var result=new Proven(provider,merchant,environment,(String)local.get("creator_id"),(String)local.get("id"),related,captureId,
            amount,currency,((Number)local.get("credits")).longValue(),state,"paypal-original-capture:"+captureId+":"+state);
        result.assertCurrent(local,merchant,environment);return result;
    }
    private static String text(JsonNode node){return node==null||node.isNull()?"":node.asString("");}
    private static void require(boolean valid,String reason){if(!valid)throw new IllegalStateException("Unverified PayPal "+reason);}
}
