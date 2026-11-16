package com.qingfeng.music.server.payment;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native-schema GET facts only. No supplier/API/database acceptance claim. */
class PayPalFundsProofTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String CREATED="2026-11-11T00:00:00Z",UPDATED="2026-11-12T01:00:00Z",END="2026-11-13T00:00:00Z";
    final PayPalFundsProof.Scope scope=new PayPalFundsProof.Scope("MERCHANT123","sandbox");
    final PayPalFundsProof.Order order=new PayPalFundsProof.Order("local-order","CAPTURE1","REMOTE1","USD",990);

    final class Facts {
        ObjectNode capture=node(Map.of("id","CAPTURE1","status","COMPLETED","final_capture",true,
            "amount",money("9.90"),"payee",Map.of("merchant_id","MERCHANT123"),"custom_id","local-order",
            "supplementary_data",Map.of("related_ids",Map.of("order_id","REMOTE1")),"create_time",CREATED,"update_time",UPDATED));
        ObjectNode report=node(Map.of("account_number","MERCHANT123","complete",true,"start_date","2026-11-10T00:00:00Z",
            "end_date",END,"last_refreshed_datetime",END,"transaction_details",List.of(detail(row("CAPTURE1","T0006","9.90","S",null)))));
        ObjectNode dispute=node(Map.of("dispute_id","PP-D-1","status","RESOLVED","update_time",UPDATED,
            "dispute_amount",money("9.90"),"dispute_outcome",Map.of("outcome_code","RESOLVED_BUYER_FAVOUR"),
            "disputed_transactions",List.of(Map.of("seller_transaction_id","CAPTURE1","seller",Map.of("merchant_id","MERCHANT123"),"gross_amount",money("9.90"))),
            "fund_movements",List.of(movement("DEBIT","9.90"))));
        ObjectNode list=node(Map.of("items",List.of(Map.of("dispute_id","PP-D-1","status","RESOLVED","update_time",UPDATED,"dispute_amount",money("9.90"))),"links",List.of()));
        Facts withdrawal(){add(row("WITHDRAWAL1","T1201","-9.90","S","CAPTURE1"));return this;}
        void add(ObjectNode row){((ArrayNode)report.path("transaction_details")).add(detail(row));}
        ObjectNode nativeRow(int index){return (ObjectNode)report.path("transaction_details").get(index).path("transaction_info");}
        PayPalFundsProof.Evidence evidence(){return new PayPalFundsProof.Evidence(scope,capture,dispute,list,report);}
        void won(){((ObjectNode)dispute.path("dispute_outcome")).put("outcome_code","RESOLVED_SELLER_FAVOUR");}
        void noDisputes(){list.set("items",JSON.createArrayNode());}
        void noMovements(){dispute.set("fund_movements",JSON.createArrayNode());}
    }
    static ObjectNode node(Object value){return (ObjectNode)JSON.valueToTree(value);}
    static Map<String,String> money(String value){return Map.of("currency_code","USD","value",value);}
    static ObjectNode movement(String type,String amount){return node(Map.of("party","SELLER","type",type,"reason","DISPUTE_SETTLEMENT","amount",money(amount),"initiated_time",UPDATED));}
    static ObjectNode row(String id,String code,String amount,String state,String parent){
        var r=node(Map.of("transaction_id",id,"transaction_event_code",code,"transaction_amount",money(amount),"transaction_status",state,"transaction_initiation_date",CREATED,"transaction_updated_date",UPDATED));
        if(parent!=null){r.put("paypal_reference_id",parent);r.put("paypal_reference_id_type","TXN");}return r;
    }
    static ObjectNode detail(ObjectNode row){return node(Map.of("transaction_info",row));}
    void failsLoss(Facts f){assertThrows(IllegalStateException.class,()->PayPalFundsProof.loss(scope,order,f.evidence()));}
    void failsReview(Facts f){assertThrows(IllegalStateException.class,()->PayPalFundsProof.review(scope,order,f.capture,f.list,f.report));}

    @Test void fullLossRequiresSettledNativeDebitAndCorrectSingleDispute(){
        var f=new Facts().withdrawal();var p=PayPalFundsProof.loss(scope,order,f.evidence());
        assertEquals(990,p.lossMinor());assertEquals("WITHDRAWAL1",p.withdrawalId());assertEquals("CAPTURE1",p.originalTransactionId());assertTrue(p.evidenceSha256().matches("[a-f0-9]{64}"));
    }
    @Test void partialLossUsesActualSettledPrincipalNotFullCaptureOrClaim(){
        var f=new Facts().withdrawal();f.nativeRow(1).set("transaction_amount",node(money("-3.30")));f.dispute.set("fund_movements",JSON.valueToTree(List.of(movement("DEBIT","3.30"))));
        assertEquals(330,PayPalFundsProof.loss(scope,order,f.evidence()).lossMinor());
    }
    @Test void originalFullyReversedVStillProvesOriginalReceiptButCannotProveLossByItself(){
        var f=new Facts().withdrawal();f.nativeRow(0).put("transaction_status","V");assertEquals(990,PayPalFundsProof.loss(scope,order,f.evidence()).lossMinor());
        var missing=new Facts();missing.nativeRow(0).put("transaction_status","V");failsLoss(missing);
    }
    @Test void scopeAndPayeeCannotCrossMerchantOrEnvironment(){
        var f=new Facts().withdrawal();assertThrows(IllegalStateException.class,()->PayPalFundsProof.loss(new PayPalFundsProof.Scope("OTHER1234","sandbox"),order,f.evidence()));
        assertThrows(IllegalStateException.class,()->PayPalFundsProof.loss(new PayPalFundsProof.Scope("MERCHANT123","live"),order,f.evidence()));
        ((ObjectNode)f.capture.path("payee")).put("merchant_id","OTHER1234");failsLoss(f);
        assertThrows(IllegalStateException.class,()->PayPalFundsProof.review(new PayPalFundsProof.Scope("MERCHANT123",null),order,f.capture,f.list,f.report));
    }
    @Test void originalCaptureOrderAndLocalReferenceCannotCrossOrders(){
        for(String field:List.of("id","custom_id")){var f=new Facts().withdrawal();f.capture.put(field,"WRONG1");failsLoss(f);}
        var f=new Facts().withdrawal();((ObjectNode)f.capture.path("supplementary_data").path("related_ids")).put("order_id","WRONG1");failsLoss(f);
    }
    @Test void disputedSellerAndCaptureMustMatchOriginal(){
        for(String value:List.of("wrongCapture","wrongSeller","wrongGross")){
            var f=new Facts().withdrawal();var t=(ObjectNode)f.dispute.path("disputed_transactions").get(0);
            if(value.equals("wrongCapture"))t.put("seller_transaction_id","OTHER1");else if(value.equals("wrongSeller"))((ObjectNode)t.path("seller")).put("merchant_id","OTHER1234");else t.set("gross_amount",node(money("1.00")));failsLoss(f);
        }
    }
    @Test void exactMoneyRejectsFxPrecisionZeroAndOverdraw(){
        for(String value:List.of("-9.901","-0.00","1.00","-10.00","-1e1")){var f=new Facts().withdrawal();f.nativeRow(1).set("transaction_amount",node(money(value)));failsLoss(f);}
        var f=new Facts().withdrawal();((ObjectNode)f.nativeRow(1).path("transaction_amount")).put("currency_code","GBP");failsLoss(f);
    }
    @Test void pendingDeniedReversedOrUnknownWithdrawalCannotDebit(){
        for(String state:List.of("P","D","V","UNKNOWN","")){var f=new Facts().withdrawal();f.nativeRow(1).put("transaction_status",state);failsLoss(f);}
    }
    @Test void openUnderReviewClosedAndSellerWinCannotBecomePermanentLoss(){
        for(String state:List.of("OPEN","UNDER_REVIEW","CLOSED","UNKNOWN")){var f=new Facts().withdrawal();f.dispute.put("status",state);failsLoss(f);}
        var won=new Facts().withdrawal();won.won();failsLoss(won);
    }
    @Test void buyerFavourAmountRefundedAndInitiatedDebitAloneDoNotProveSettledMerchantLoss(){
        var f=new Facts();((ObjectNode)f.dispute.path("dispute_outcome")).set("amount_refunded",node(money("9.90")));failsLoss(f);
        var hold=new Facts();hold.add(row("HOLD1","T1110","-9.90","S","CAPTURE1"));failsLoss(hold);
    }
    @Test void duplicateOrMultipleNativeWithdrawalsAreNotCollapsed(){
        var duplicate=new Facts().withdrawal();duplicate.add(row("WITHDRAWAL1","T1201","-9.90","S","CAPTURE1"));failsLoss(duplicate);
        var multiple=new Facts().withdrawal();multiple.add(row("WITHDRAWAL2","T1201","-1.00","S","CAPTURE1"));failsLoss(multiple);
    }
    @Test void referenceMustNameOriginalCaptureAndHaveTxnType(){
        for(String mutation:List.of("parent","type")){var f=new Facts().withdrawal();f.nativeRow(1).put(mutation.equals("parent")?"paypal_reference_id":"paypal_reference_id_type",mutation.equals("parent")?"OTHER1":"ODR");failsLoss(f);}
    }
    @Test void incompleteReportStartEndAndRefreshWatermarkCannotProveFinalFacts(){
        for(String field:List.of("end_date","last_refreshed_datetime")){var f=new Facts().withdrawal();f.report.put(field,"2026-11-11T01:00:00Z");failsLoss(f);}
        var late=new Facts().withdrawal();late.dispute.put("update_time","2026-11-14T00:00:00Z");((ObjectNode)late.list.path("items").get(0)).put("update_time","2026-11-14T00:00:00Z");failsLoss(late);
        var start=new Facts().withdrawal();start.report.put("start_date",UPDATED);failsLoss(start);
    }
    @Test void reportMerchantCompletenessAndPaginationAreMandatory(){
        var account=new Facts().withdrawal();account.report.put("account_number","OTHER1234");failsLoss(account);
        var incomplete=new Facts().withdrawal();incomplete.report.put("complete",false);failsLoss(incomplete);
        var pages=new Facts().withdrawal();pages.report.set("links",JSON.valueToTree(List.of(Map.of("rel","next"))));failsLoss(pages);
        var more=new Facts().withdrawal();more.report.put("has_more",true);failsLoss(more);
    }
    @Test void independentDisputeListMustBeCompleteUniqueAndSameSnapshot(){
        var missing=new Facts().withdrawal();missing.noDisputes();failsLoss(missing);
        var many=new Facts().withdrawal();((ArrayNode)many.list.path("items")).add(many.list.path("items").get(0));failsLoss(many);
        var next=new Facts().withdrawal();next.list.set("links",JSON.valueToTree(List.of(Map.of("rel","next"))));failsLoss(next);
        var changed=new Facts().withdrawal();((ObjectNode)changed.list.path("items").get(0)).put("dispute_id","PP-D-OTHER");failsLoss(changed);
    }
    @Test void refundFeeHoldOrUnknownOverlapRequiresReview(){
        for(String code:List.of("T1107","T1110","T1111","T0106","T0114","T9999")){
            var f=new Facts().withdrawal();f.add(row("EXTRA1",code,"-1.00","S","CAPTURE1"));failsLoss(f);
        }
        var refunded=new Facts().withdrawal();refunded.capture.put("status","PARTIALLY_REFUNDED");failsLoss(refunded);
    }
    @Test void sellerMovementsMustBeExactUnambiguousAndSameCurrency(){
        var missing=new Facts().withdrawal();missing.noMovements();failsLoss(missing);
        var repeated=new Facts().withdrawal();((ArrayNode)repeated.dispute.path("fund_movements")).add(movement("DEBIT","9.90"));failsLoss(repeated);
        var unknown=new Facts().withdrawal();((ArrayNode)unknown.dispute.path("fund_movements")).add(movement("UNKNOWN","1.00"));failsLoss(unknown);
        var fx=new Facts().withdrawal();((ObjectNode)fx.dispute.path("fund_movements").get(0).path("amount")).put("currency_code","GBP");failsLoss(fx);
        var fee=new Facts().withdrawal();var feeMovement=movement("DEBIT","1.00");feeMovement.put("reason","DISPUTE_FEE");((ArrayNode)fee.dispute.path("fund_movements")).add(feeMovement);failsLoss(fee);
        var party=new Facts().withdrawal();var unknownParty=movement("DEBIT","1.00");unknownParty.put("party","UNKNOWN");((ArrayNode)party.dispute.path("fund_movements")).add(unknownParty);failsLoss(party);
    }
    Facts reimbursement(String code){var f=new Facts().withdrawal();f.won();f.add(row("RETURN1",code,"9.90","S","WITHDRAWAL1"));((ArrayNode)f.dispute.path("fund_movements")).add(movement("CREDIT","9.90"));return f;}
    @Test void onlyExactSettledT1202OrT1205CanReturnRecordedLoss(){
        var lost=new Facts().withdrawal();var recorded=PayPalFundsProof.loss(scope,order,lost.evidence());
        for(String code:List.of("T1202","T1205")){var f=reimbursement(code);var result=PayPalFundsProof.returned(scope,order,f.evidence(),recorded);assertEquals(990,result.returnedMinor());assertEquals("RETURN1",result.transactionId());assertTrue(result.evidenceSha256().matches("[a-f0-9]{64}"));}
    }
    @Test void returnRequiresExactPriorLossBindingAndItsStableEvidence(){
        var recorded=PayPalFundsProof.loss(scope,order,new Facts().withdrawal().evidence());var f=reimbursement("T1202");
        for(var wrong:List.of(new PayPalFundsProof.Loss(1,"WITHDRAWAL1","CAPTURE1",recorded.evidenceSha256()),new PayPalFundsProof.Loss(990,"OTHER1","CAPTURE1",recorded.evidenceSha256()),new PayPalFundsProof.Loss(990,"WITHDRAWAL1","OTHER1",recorded.evidenceSha256()),new PayPalFundsProof.Loss(990,"WITHDRAWAL1","CAPTURE1","00")))assertThrows(IllegalStateException.class,()->PayPalFundsProof.returned(scope,order,f.evidence(),wrong));
        assertThrows(IllegalStateException.class,()->PayPalFundsProof.returned(scope,order,f.evidence(),null));
    }
    @Test void pendingPartialOverpaidWrongReferenceAndRepeatedReturnCannotCredit(){
        var recorded=PayPalFundsProof.loss(scope,order,new Facts().withdrawal().evidence());
        for(String mutation:List.of("pending","partial","excess","parent","type","duplicate")){
            var f=reimbursement("T1202");var row=f.nativeRow(2);
            switch(mutation){case "pending"->row.put("transaction_status","P");case "partial"->row.set("transaction_amount",node(money("1.00")));case "excess"->row.set("transaction_amount",node(money("10.00")));case "parent"->row.put("paypal_reference_id","CAPTURE1");case "type"->row.put("transaction_event_code","T1106");case "duplicate"->f.add(row("RETURN2","T1205","9.90","S","WITHDRAWAL1"));}
            assertThrows(IllegalStateException.class,()->PayPalFundsProof.returned(scope,order,f.evidence(),recorded));
        }
    }
    @Test void evidenceDigestDoesNotDependOnNativeObjectPropertyOrder(){
        var f=new Facts().withdrawal();var first=PayPalFundsProof.loss(scope,order,f.evidence());var r=f.nativeRow(1);var entries=new ArrayList<>(r.properties());Collections.reverse(entries);var reordered=JSON.createObjectNode();for(var e:entries)reordered.set(e.getKey(),e.getValue());((ObjectNode)f.report.path("transaction_details").get(1)).set("transaction_info",reordered);
        assertEquals(first.evidenceSha256(),PayPalFundsProof.loss(scope,order,f.evidence()).evidenceSha256());
    }
    @Test void reviewOfNoDisputeOriginalCanReleaseOnlyZeroLoss(){
        var f=new Facts();f.noDisputes();var p=PayPalFundsProof.review(scope,order,f.capture,f.list,f.report);assertEquals(0,p.lossMinor());assertEquals(0,p.refundedMinor());assertTrue(p.refunds().isEmpty());
    }
    @Test void reviewCollectsSortedSuccessfulPartialRefundsAndFullRefund(){
        var f=new Facts();f.noDisputes();f.capture.put("status","PARTIALLY_REFUNDED");f.add(row("REFUND2","T1107","-1.10","S","CAPTURE1"));f.add(row("REFUND1","T1107","-2.20","S","CAPTURE1"));
        var p=PayPalFundsProof.review(scope,order,f.capture,f.list,f.report);assertEquals(330,p.refundedMinor());assertEquals(List.of(new PayPalFundsProof.Refund("REFUND1",220),new PayPalFundsProof.Refund("REFUND2",110)),p.refunds());
        f.capture.put("status","REFUNDED");f.add(row("REFUND3","T1107","-6.60","S","CAPTURE1"));assertEquals(990,PayPalFundsProof.review(scope,order,f.capture,f.list,f.report).refundedMinor());
    }
    @Test void reviewRejectsPendingFailedOverpaidDuplicateAndCaptureStateMismatch(){
        for(String mutation:List.of("pending","denied","excess","duplicate","state")){
            var f=new Facts();f.noDisputes();f.capture.put("status","PARTIALLY_REFUNDED");f.add(row("REFUND1","T1107","-1.00","S","CAPTURE1"));
            switch(mutation){case "pending"->f.nativeRow(1).put("transaction_status","P");case "denied"->f.nativeRow(1).put("transaction_status","D");case "excess"->f.nativeRow(1).set("transaction_amount",node(money("-10.00")));case "duplicate"->f.add(row("REFUND1","T1107","-1.00","S","CAPTURE1"));case "state"->f.capture.put("status","COMPLETED");}failsReview(f);
        }
    }
    @Test void reviewCannotInventEmptyDisputeOrIgnoreFinancialMovement(){
        var disputed=new Facts();failsReview(disputed);
        for(String code:List.of("T1201","T1202","T1205","T1110","T1111","T1106","T9999")){var f=new Facts();f.noDisputes();f.add(row("OTHER1",code,"-1.00","S","CAPTURE1"));failsReview(f);}
    }
    @Test void wonWithoutLossMayReleaseReviewOnlyWithCompleteNoPrincipalMovementProof(){
        var f=new Facts();f.won();f.noMovements();var p=PayPalFundsProof.review(scope,order,f.evidence());assertEquals(0,p.lossMinor());assertEquals(0,p.refundedMinor());
        var losing=new Facts();losing.noMovements();assertThrows(IllegalStateException.class,()->PayPalFundsProof.review(scope,order,losing.evidence()));
        var absentReportDebit=new Facts();absentReportDebit.won();assertThrows(IllegalStateException.class,()->PayPalFundsProof.review(scope,order,absentReportDebit.evidence()));
        var permanent=reimbursement("T1202");assertThrows(IllegalStateException.class,()->PayPalFundsProof.review(scope,order,permanent.evidence()));
    }
    @Test void missingFieldsAndUnrelatedSuccessfulTransactionsNeverFillRequiredProof(){
        var missing=new Facts().withdrawal();missing.capture.remove("final_capture");failsLoss(missing);
        var unrelated=new Facts();unrelated.add(row("OTHER1","T1201","-9.90","S","ANOTHER1"));failsLoss(unrelated);
        var original=new Facts().withdrawal();original.nativeRow(0).put("transaction_status","P");failsLoss(original);
    }
}
