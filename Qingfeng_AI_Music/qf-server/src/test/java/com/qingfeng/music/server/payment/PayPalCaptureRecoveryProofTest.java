package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Pure evidence-binding tests; synthetic GET facts, no database or supplier requests. */
class PayPalCaptureRecoveryProofTest {
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> local(){var row=new HashMap<String,Object>();row.put("id","local-order");row.put("creator_id","owner-one");row.put("provider","PAYPAL_SANDBOX");row.put("amount",new BigDecimal("9.90"));row.put("currency","USD");row.put("credits",100L);row.put("provider_link_id","REMOTE123");return row;}
    PayPalCheckoutGateway.Snapshot snapshot(String status){return new PayPalCheckoutGateway.Snapshot("REMOTE123","local-order","COMPLETED","MERCHANT123","CAPTURE123",status,new BigDecimal("9.90"),"USD");}
    JsonNode capture(String status){return json.readTree("{\"id\":\"CAPTURE123\",\"status\":\""+status+"\",\"final_capture\":true,\"amount\":{\"value\":\"9.90\",\"currency_code\":\"USD\"},\"payee\":{\"merchant_id\":\"MERCHANT123\"},\"supplementary_data\":{\"related_ids\":{\"order_id\":\"REMOTE123\"}}}");}
    PayPalCaptureRecoveryProof.Proven proof(String state){return PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot(state),capture(state));}
    @Test void completedPartialAndFullRefundedCaptureStillProveOriginalReceiptWithoutProvingRefundHistory(){
        for(String state:List.of("COMPLETED","PARTIALLY_REFUNDED","REFUNDED")){var p=proof(state);assertEquals(100,p.credits());assertEquals(new BigDecimal("9.90"),p.amount());assertEquals(state,p.observedCaptureStatus());assertEquals("PAYPAL_SANDBOX",p.provider());assertTrue(p.evidence().contains("CAPTURE123"));}
    }
    @Test void reversedPendingFailedAndUnknownNeverProveOriginalReceipt(){for(String state:List.of("REVERSED","PENDING","FAILED","DECLINED","UNKNOWN",""))assertThrows(IllegalStateException.class,()->proof(state));}
    @Test void missingOrNonBooleanFinalCaptureCannotInventSingleCompletedCapture(){for(String field:List.of("false","null","\"true\"")){var resource=json.readTree(capture("PARTIALLY_REFUNDED").toString().replace("\"final_capture\":true","\"final_capture\":"+field));assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot("PARTIALLY_REFUNDED"),resource));}}
    @Test void exactAmountCurrencyAndMerchantMustMatchBothGetResourcesAndLocalSnapshot(){
        String original=capture("PARTIALLY_REFUNDED").toString();
        for(String altered:List.of(original.replace("9.90","0.01"),original.replace("9.90","9.901"),original.replace("9.90","0.00"),original.replace("USD","GBP"),original.replace("MERCHANT123","WRONGMERCHANT")))assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot("PARTIALLY_REFUNDED"),json.readTree(altered)));
    }
    @Test void captureRelatedOrderAndLocalReferenceCannotCrossOrders(){
        var original=capture("REFUNDED").toString();
        for(String altered:List.of(original.replace("CAPTURE123","OTHER123"),original.replace("REMOTE123","OTHER123")))assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot("REFUNDED"),json.readTree(altered)));
        var other=local();other.put("id","another-order");assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",other,snapshot("REFUNDED"),capture("REFUNDED")));
    }
    @Test void missingPayeeAmountAndRelatedOrderAreNotFilledFromRefundOrWebhook(){
        for(String missing:List.of("payee","amount","supplementary_data")){
            var data=new HashMap<String,Object>();capture("REFUNDED").properties().forEach(e->{if(!e.getKey().equals(missing))data.put(e.getKey(),e.getValue());});
            assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot("REFUNDED"),json.valueToTree(data)));
        }
    }
    @Test void betweenGetStatusChangesAndUncompletedOrderAreRetriedInsteadOfRelaxingStatus(){
        assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),snapshot("COMPLETED"),capture("REFUNDED")));
        var pending=new PayPalCheckoutGateway.Snapshot("REMOTE123","local-order","APPROVED","MERCHANT123","CAPTURE123","REFUNDED",new BigDecimal("9.90"),"USD");assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",local(),pending,capture("REFUNDED")));
    }
    @Test void environmentAndPreexistingCaptureBindingsCannotBeReassigned(){
        assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","live",local(),snapshot("REFUNDED"),capture("REFUNDED")));
        var bound=local();bound.put("provider_capture_id","ANOTHERCAPTURE");assertThrows(IllegalStateException.class,()->PayPalCaptureRecoveryProof.verify("MERCHANT123","sandbox",bound,snapshot("REFUNDED"),capture("REFUNDED")));
    }
    @Test void transactionApplyMustRecheckOwnerCreditsAndMerchantSnapshot(){
        var proof=proof("PARTIALLY_REFUNDED");
        for(String key:List.of("creator_id","credits","amount","currency","provider_link_id","provider_capture_id")){
            var changed=local();changed.put(key,switch(key){case "credits"->999L;case "amount"->new BigDecimal("0.01");default->"changed";});assertThrows(IllegalStateException.class,()->proof.assertCurrent(changed,"MERCHANT123","sandbox"));
        }
        assertThrows(IllegalStateException.class,()->proof.assertCurrent(local(),"ANOTHERMERCHANT","sandbox"));
    }
}
