package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Only authoritative GET resources may enter this adapter; webhook bodies are pointers only.
 * A resolved buyer win does not establish actual principal loss. Such cases stay frozen
 * for a separate funds-evidence review rather than guessing a full order chargeback. */
final class PayPalEntitlementAdapter {
    record Fact(String id,String capture,String currency,long amount,long version,String state) {}
    static Fact refund(JsonNode resource,String environment){
        String id=resource.path("id").asString(); identifier(id);
        String capture=resource.path("supplementary_data").path("related_ids").path("capture_id").asString();
        for(var link:resource.path("links"))if("up".equals(link.path("rel").asString())){
            URI uri=URI.create(link.path("href").asString());
            String suffix="sandbox".equals(environment)?"sandbox.paypal.com":"paypal.com";
            if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)
                    ||!Set.of("api."+suffix,"api-m."+suffix).contains(uri.getHost())
                    ||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().matches("/v2/payments/captures/[A-Za-z0-9]{1,32}"))
                throw new IllegalStateException("Unsafe refund capture correlation");
            String linked=uri.getPath().substring(uri.getPath().lastIndexOf('/')+1);
            if(!capture.isBlank()&&!capture.equals(linked))throw new IllegalStateException("Conflicting refund capture correlation");
            capture=linked;
        }
        identifier(capture);
        return new Fact(id,capture,currency(resource.path("amount")),minor(resource.path("amount")),0,resource.path("status").asString());
    }
    static Fact dispute(JsonNode resource){
        String id=resource.path("dispute_id").asString();
        if(!id.matches("[A-Za-z0-9-]{1,128}")||resource.path("disputed_transactions").size()!=1)
            throw new IllegalStateException("A dispute must bind exactly one original payment");
        String capture=resource.path("disputed_transactions").get(0).path("seller_transaction_id").asString();identifier(capture);
        long version=Instant.parse(resource.path("update_time").asString()).toEpochMilli();
        if(version<=0)throw new IllegalStateException("Missing authoritative dispute version");
        String status=resource.path("status").asString(),outcome=resource.path("dispute_outcome").path("outcome_code").asString();
        String state=switch(status){
            case "OPEN","WAITING_FOR_BUYER_RESPONSE","WAITING_FOR_SELLER_RESPONSE" -> "OPEN";
            case "UNDER_REVIEW" -> "UNDER_REVIEW";
            case "RESOLVED" -> Set.of("RESOLVED_SELLER_FAVOUR","CANCELED_BY_BUYER").contains(outcome)?"WON":
                "RESOLVED_BUYER_FAVOUR".equals(outcome)?"LOST":"UNKNOWN";
            default -> "UNKNOWN";
        };
        return new Fact(id,capture,currency(resource.path("dispute_amount")),minor(resource.path("dispute_amount")),version,state);
    }
    static void apply(JdbcFencedCreditsLedger ledger,String merchant,String environment,String owner,String order,Fact fact,boolean dispute){
        if(dispute){
            // No actual-principal-loss proof is supplied by this resource: LOST(null) holds
            // only this batch and records the unresolved outcome, never permanently debits.
            ledger.paymentDispute("PAYPAL",merchant,environment,fact.id(),owner,order,fact.capture(),fact.amount(),fact.currency(),fact.version(),fact.state(),null);
        }else if("COMPLETED".equals(fact.state())){
            ledger.paymentRefund("PAYPAL",merchant,environment,fact.id(),owner,order,fact.capture(),fact.amount(),fact.currency());
        }else throw new IllegalStateException("Refund has no successful authoritative funds state");
    }
    private static void identifier(String value){if(value==null||!value.matches("[A-Za-z0-9]{1,32}"))throw new IllegalStateException("Invalid PayPal resource identity");}
    private static String currency(JsonNode amount){String currency=amount.path("currency_code").asString();if(!Set.of("USD","GBP").contains(currency))throw new IllegalStateException("Unsupported money currency");return currency;}
    private static long minor(JsonNode amount){String value=amount.path("value").asString();if(!value.matches("[0-9]{1,12}\\.[0-9]{2}"))throw new IllegalStateException("Exact money required");long minor=new BigDecimal(value).movePointRight(2).longValueExact();if(minor<=0)throw new IllegalStateException("Positive money required");return minor;}
}
