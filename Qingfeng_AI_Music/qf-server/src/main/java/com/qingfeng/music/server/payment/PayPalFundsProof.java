package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pure, conservative proof from authenticated GETs. A dispute outcome or initiated
 * fund movement alone never proves settled merchant loss. Native Reporting rows
 * must cover the entire interval; unsupported FX, overlaps and movements fail closed.
 * Scope is supplied by the authenticated gateway, not by a webhook or request body.
 * References: PayPal reporting_transactions_v1/customer_disputes_v1 schemas and
 * https://developer.paypal.com/docs/transaction-search/transaction-event-codes/ . */
final class PayPalFundsProof {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Scope(String merchant,String environment) {}
    record Order(String orderId,String captureId,String remoteOrderId,String currency,long paidMinor) {}
    record Evidence(Scope scope,JsonNode capture,JsonNode dispute,JsonNode disputeList,JsonNode report) {}
    record Loss(long lossMinor,String withdrawalId,String originalTransactionId,String evidenceSha256) {}
    record Returned(long returnedMinor,String transactionId,String evidenceSha256) {}
    record Refund(String id,long amountMinor) {}
    record Review(List<Refund> refunds,long refundedMinor,long lossMinor,String evidenceSha256) {}
    private record Context(Scope scope,Order order,JsonNode capture,List<JsonNode> rows,Instant end,Instant watermark) {}
    private record Dispute(String id,long amount,JsonNode resource) {}
    private PayPalFundsProof() {}

    static Loss loss(Scope scope,Order order,Evidence evidence) {
        require(evidence!=null&&Objects.equals(scope,evidence.scope()),"authenticated evidence scope mismatch");
        Context c=context(scope,order,evidence.capture(),evidence.report());
        require("COMPLETED".equals(text(c.capture().path("status"))),"refund overlap or unsupported capture state");
        Dispute d=dispute(c,evidence.dispute(),evidence.disputeList(),"RESOLVED_BUYER_FAVOUR");
        List<JsonNode> related=related(c.rows(),order.captureId());
        JsonNode withdrawal=single(related,"T1201");
        require(related.size()==2,"refund, fee, hold, return or unknown overlap requires separate review");
        long amount=negative(c,withdrawal,order.captureId());
        require(amount<=d.amount(),"loss exceeds disputed principal");
        require(settlement(d.resource(),"DEBIT")==amount&&settlement(d.resource(),"CREDIT")==0,"seller principal movement mismatch");
        return new Loss(amount,id(withdrawal),order.captureId(),lossSha(c,d,withdrawal));
    }

    static Returned returned(Scope scope,Order order,Evidence evidence,Loss recordedLoss) {
        require(evidence!=null&&Objects.equals(scope,evidence.scope()),"authenticated evidence scope mismatch");
        Context c=context(scope,order,evidence.capture(),evidence.report());
        require("COMPLETED".equals(text(c.capture().path("status"))),"refund overlap or unsupported capture state");
        Dispute d=dispute(c,evidence.dispute(),evidence.disputeList(),"RESOLVED_SELLER_FAVOUR");
        List<JsonNode> related=related(c.rows(),order.captureId());
        JsonNode withdrawal=single(related,"T1201");
        long amount=negative(c,withdrawal,order.captureId());
        require(recordedLoss!=null&&amount==recordedLoss.lossMinor()&&amount<=d.amount()
            &&id(withdrawal).equals(recordedLoss.withdrawalId())&&order.captureId().equals(recordedLoss.originalTransactionId())
            &&lossSha(c,d,withdrawal).equals(recordedLoss.evidenceSha256()),"recorded loss identity mismatch");
        JsonNode returned=null;
        for(JsonNode row:related)if(Set.of("T1202","T1205").contains(text(row.path("transaction_event_code")))) {
            require(returned==null,"multiple returns require separate review");returned=row;
        }
        require(returned!=null&&related.size()==3,"complete original/withdrawal/return chain required");
        require("S".equals(text(returned.path("transaction_status")))&&reference(returned,id(withdrawal))
            &&money(returned.path("transaction_amount"),order.currency())==amount,"settled exact principal reimbursement required");
        require(settlement(d.resource(),"DEBIT")==amount&&settlement(d.resource(),"CREDIT")==amount,"seller reimbursement movement mismatch");
        return new Returned(amount,id(returned),sha(Map.of("loss",recordedLoss.evidenceSha256(),"return",safe(returned))));
    }

    /** Only this no-dispute subset can release a recovered original capture REVIEW.
     * Caller must independently GET and verify every returned refund ID, then apply
     * its idempotent adjustment before releasing the hold in the same transaction. */
    static Review review(Scope scope,Order order,JsonNode capture,JsonNode disputeList,JsonNode report) {
        Context c=context(scope,order,capture,report);
        require(completeDisputes(disputeList).isEmpty(),"unresolved dispute history");
        return review(c);
    }

    /** A final won case with no settled principal withdrawal can release a review;
     * any principal movement, including one absent from reporting, remains blocked. */
    static Review review(Scope scope,Order order,Evidence evidence) {
        require(evidence!=null&&Objects.equals(scope,evidence.scope()),"authenticated evidence scope mismatch");
        Context c=context(scope,order,evidence.capture(),evidence.report());
        Dispute d=dispute(c,evidence.dispute(),evidence.disputeList(),"RESOLVED_SELLER_FAVOUR");
        require(settlement(d.resource(),"DEBIT")==0&&settlement(d.resource(),"CREDIT")==0,"principal movement requires independently recorded loss/return");
        return review(c);
    }

    private static Review review(Context c) {
        Order order=c.order();JsonNode capture=c.capture();
        var refunds=new ArrayList<Refund>();long total=0;
        for(JsonNode row:related(c.rows(),order.captureId())) {
            if(id(row).equals(order.captureId()))continue;
            require("T1107".equals(text(row.path("transaction_event_code"))),"chargeback, hold, return or unknown related movement");
            long amount=negative(c,row,order.captureId());
            total=Math.addExact(total,amount);require(total<=order.paidMinor(),"refund principal exceeds original payment");
            refunds.add(new Refund(id(row),amount));
        }
        String state=text(capture.path("status"));
        require(("COMPLETED".equals(state)&&total==0)
            ||("PARTIALLY_REFUNDED".equals(state)&&total>0&&total<order.paidMinor())
            ||("REFUNDED".equals(state)&&total==order.paidMinor()),"capture and complete refund history disagree");
        refunds.sort(Comparator.comparing(Refund::id));
        return new Review(List.copyOf(refunds),total,0,sha(Map.of("scope",c.scope(),"order",order,"refunds",refunds)));
    }

    private static Context context(Scope scope,Order order,JsonNode capture,JsonNode report) {
        require(scope!=null&&scope.merchant()!=null&&scope.merchant().matches("[A-Za-z0-9]{8,64}")
            &&scope.environment()!=null&&Set.of("live","sandbox").contains(scope.environment()),"merchant/environment required");
        require(order!=null&&order.orderId()!=null&&!order.orderId().isBlank()&&order.orderId().length()<=128
            &&validId(order.captureId())&&validId(order.remoteOrderId())&&order.currency()!=null&&Set.of("USD","GBP").contains(order.currency())
            &&order.paidMinor()>0,"immutable same-currency order snapshot required");
        object(capture);object(report);
        require(order.captureId().equals(text(capture.path("id")))
            &&order.remoteOrderId().equals(text(capture.path("supplementary_data").path("related_ids").path("order_id")))
            &&scope.merchant().equals(text(capture.path("payee").path("merchant_id")))
            &&capture.path("final_capture").isBoolean()&&capture.path("final_capture").asBoolean()
            &&money(capture.path("amount"),order.currency())==order.paidMinor(),"capture/order/payee/original amount mismatch");
        if(capture.has("custom_id"))require(order.orderId().equals(text(capture.path("custom_id"))),"capture local reference mismatch");
        Instant created=time(capture.path("create_time")),updated=time(capture.path("update_time"));
        Instant start=time(report.path("start_date")),end=time(report.path("end_date")),watermark=time(report.path("last_refreshed_datetime"));
        require(!updated.isBefore(created)&&!end.isBefore(start)&&!start.isAfter(created)&&!end.isBefore(updated)
            &&!watermark.isBefore(end),"report interval or refresh watermark incomplete");
        require(scope.merchant().equals(text(report.path("account_number")))&&report.path("complete").isBoolean()
            &&report.path("complete").asBoolean(),"authenticated complete merchant report required");
        require(!report.path("has_more").asBoolean(false),"incomplete report pagination");
        if(report.has("links"))for(JsonNode link:array(report,"links"))require(!"next".equalsIgnoreCase(text(link.path("rel"))),"incomplete report pagination");
        JsonNode rows=array(report,"transaction_details");var result=new ArrayList<JsonNode>();var ids=new HashSet<String>();
        for(JsonNode detail:rows) {
            JsonNode row=detail.path("transaction_info");object(row);
            require(ids.add(id(row)),"duplicate native transaction identity");
            Instant initiated=time(row.path("transaction_initiation_date")),changed=time(row.path("transaction_updated_date"));
            require(!initiated.isBefore(start)&&!changed.isBefore(initiated)&&!changed.isAfter(end),"transaction outside complete report interval");
            result.add(row);
        }
        JsonNode original=null;
        for(JsonNode row:result)if(order.captureId().equals(id(row)))original=row;
        require(original!=null&&text(original.path("transaction_event_code")).matches("T00[0-9]{2}")
            &&Set.of("S","V").contains(text(original.path("transaction_status")))
            &&money(original.path("transaction_amount"),order.currency())==order.paidMinor(),"original successful payment report required");
        if(original.has("paypal_reference_id"))require("ODR".equals(text(original.path("paypal_reference_id_type")))
            &&order.remoteOrderId().equals(text(original.path("paypal_reference_id"))),"original report order reference mismatch");
        return new Context(scope,order,capture,List.copyOf(result),end,watermark);
    }

    private static Dispute dispute(Context c,JsonNode dispute,JsonNode list,String outcome) {
        object(dispute);String id=text(dispute.path("dispute_id"));
        require(id.matches("[A-Za-z0-9-]{1,128}")&&"RESOLVED".equals(text(dispute.path("status")))
            &&outcome.equals(text(dispute.path("dispute_outcome").path("outcome_code"))),"final authoritative dispute outcome required");
        Instant updated=time(dispute.path("update_time"));
        require(!updated.isBefore(time(c.capture().path("create_time")))&&!c.end().isBefore(updated)
            &&!c.watermark().isBefore(updated),"report does not cover dispute update");
        JsonNode transactions=array(dispute,"disputed_transactions");require(transactions.size()==1,"single original disputed payment required");
        JsonNode transaction=transactions.get(0);
        require(c.order().captureId().equals(text(transaction.path("seller_transaction_id")))
            &&c.scope().merchant().equals(text(transaction.path("seller").path("merchant_id")))
            &&money(transaction.path("gross_amount"),c.order().currency())==c.order().paidMinor(),"dispute original payment mismatch");
        long amount=money(dispute.path("dispute_amount"),c.order().currency());require(amount>0&&amount<=c.order().paidMinor(),"invalid disputed principal");
        JsonNode items=completeDisputes(list);require(items.size()==1,"multiple or missing disputes require separate review");
        JsonNode summary=items.get(0);require(id.equals(text(summary.path("dispute_id")))
            &&"RESOLVED".equals(text(summary.path("status")))&&updated.equals(time(summary.path("update_time")))
            &&money(summary.path("dispute_amount"),c.order().currency())==amount,"independent dispute listing mismatch");
        return new Dispute(id,amount,dispute);
    }

    private static JsonNode completeDisputes(JsonNode list) {
        object(list);JsonNode items=array(list,"items"),links=array(list,"links");
        for(JsonNode link:links)require(!"next".equalsIgnoreCase(text(link.path("rel"))),"incomplete dispute pagination");
        require(!list.path("has_more").asBoolean(false),"incomplete dispute pagination");return items;
    }
    private static long settlement(JsonNode dispute,String type) {
        long result=0;int count=0;String currency=text(dispute.path("dispute_amount").path("currency_code"));
        for(JsonNode movement:array(dispute,"fund_movements")) {
            String party=text(movement.path("party"));require(Set.of("SELLER","BUYER","PAYMENT_PROCESSOR").contains(party),"unknown fund movement party");
            if(!"SELLER".equals(party))continue;
            require("DISPUTE_SETTLEMENT".equals(text(movement.path("reason"))),"unsupported seller fund movement reason");
            String actualType=text(movement.path("type"));require(Set.of("DEBIT","CREDIT").contains(actualType),"unknown seller principal movement");
            long amount=money(movement.path("amount"),currency);require(amount>0,"invalid seller principal movement");
            Instant initiated=time(movement.path("initiated_time"));require(!initiated.isAfter(time(dispute.path("update_time"))),"movement later than dispute snapshot");
            if(type.equals(actualType)){require(++count==1,"ambiguous principal fund movements");result=amount;}
        }
        return result;
    }
    private static List<JsonNode> related(List<JsonNode> rows,String capture) {
        var ids=new HashSet<String>();ids.add(capture);var result=new ArrayList<JsonNode>();boolean changed;
        do {changed=false;for(JsonNode row:rows)if(!result.contains(row)&&(ids.contains(id(row))||ids.contains(text(row.path("paypal_reference_id"))))) {
            result.add(row);ids.add(id(row));changed=true;
        }} while(changed);return result;
    }
    private static JsonNode single(List<JsonNode> rows,String code) {
        JsonNode found=null;for(JsonNode row:rows)if(code.equals(text(row.path("transaction_event_code")))) {require(found==null,"multiple principal withdrawals");found=row;}
        require(found!=null,"settled native principal withdrawal required");return found;
    }
    private static long negative(Context c,JsonNode row,String parent) {
        require("S".equals(text(row.path("transaction_status")))&&reference(row,parent),"successful native related movement required");
        long amount=money(row.path("transaction_amount"),c.order().currency());require(amount<0,"negative native principal required");
        return Math.negateExact(amount);
    }
    private static boolean reference(JsonNode row,String parent) {return "TXN".equals(text(row.path("paypal_reference_id_type")))&&parent.equals(text(row.path("paypal_reference_id")));}
    private static String lossSha(Context c,Dispute d,JsonNode withdrawal) {return sha(Map.of("scope",c.scope(),"order",c.order(),"dispute",d.id(),"withdrawal",safe(withdrawal)));}
    private static Map<String,String> safe(JsonNode row) {
        return Map.of("id",id(row),"code",text(row.path("transaction_event_code")),"status",text(row.path("transaction_status")),
            "reference",text(row.path("paypal_reference_id")),"reference_type",text(row.path("paypal_reference_id_type")),
            "currency",text(row.path("transaction_amount").path("currency_code")),"amount",text(row.path("transaction_amount").path("value")));
    }
    private static String sha(Object value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(JSON.valueToTree(value)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
    private static String canonical(JsonNode node) {
        if(node.isObject()) {
            var sorted=new TreeMap<String,JsonNode>();node.properties().forEach(e->sorted.put(e.getKey(),e.getValue()));
            var result=new StringJoiner(",","{","}");sorted.forEach((key,value)->result.add(JSON.writeValueAsString(key)+":"+canonical(value)));return result.toString();
        }
        if(node.isArray()){var result=new StringJoiner(",","[","]");for(JsonNode value:node)result.add(canonical(value));return result.toString();}
        return node.toString();
    }
    private static long money(JsonNode node,String currency) {
        object(node);String value=text(node.path("value"));require(currency.equals(text(node.path("currency_code")))&&value.matches("-?[0-9]{1,12}\\.[0-9]{2}"),"exact same-currency money required");
        return new BigDecimal(value).movePointRight(2).longValueExact();
    }
    private static String id(JsonNode row) {String id=text(row.path("transaction_id"));require(validId(id),"native transaction identity required");return id;}
    private static boolean validId(String id) {return id!=null&&id.matches("[A-Za-z0-9]{1,32}");}
    private static Instant time(JsonNode value) {try{return Instant.parse(text(value));}catch(RuntimeException bad){throw rejected("authoritative timestamp required");}}
    private static JsonNode array(JsonNode node,String field) {JsonNode result=node.path(field);require(result.isArray(),"explicit array "+field+" required");return result;}
    private static void object(JsonNode node) {require(node!=null&&node.isObject(),"native object required");}
    private static String text(JsonNode node) {return node!=null&&node.isString()?node.asString():"";}
    private static void require(boolean valid,String reason) {if(!valid)throw rejected(reason);}
    private static IllegalStateException rejected(String reason) {return new IllegalStateException("PayPal funds proof rejected: "+reason);}
}
