package com.qingfeng.music.server.payment;

import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Exact reversal of the refund's own native balance movement; never an FX calculation. */
final class StripeRefundReturnProof {
    record Returned(String transactionId, boolean available, String evidence) {}

    static Returned verify(boolean live, String refundId, String intentId, long amount,
                           String currency, long paid, JsonNode refund, JsonNode charge,
                           JsonNode withdrawal, JsonNode reversal) {
        require(amount > 0 && amount <= paid && "refund".equals(text(refund.path("object")))
            && refundId.equals(id(refund)) && Set.of("failed","canceled").contains(text(refund.path("status")))
            && amount == integer(refund.path("amount")) && currency.equalsIgnoreCase(text(refund.path("currency")))
            && intentId.equals(id(refund.path("payment_intent"))), "refund identity");
        require("charge".equals(text(charge.path("object"))) && id(charge).equals(id(refund.path("charge")))
            && intentId.equals(id(charge.path("payment_intent"))) && charge.path("livemode").isBoolean()
            && charge.path("livemode").asBoolean() == live && "succeeded".equals(text(charge.path("status")))
            && charge.path("paid").isBoolean() && charge.path("paid").asBoolean()
            && charge.path("captured").isBoolean() && charge.path("captured").asBoolean()
            && paid == integer(charge.path("amount")) && paid == integer(charge.path("amount_captured"))
            && currency.equalsIgnoreCase(text(charge.path("currency"))), "refund original payment");
        if (refund.has("livemode")) require(refund.path("livemode").isBoolean()
            && refund.path("livemode").asBoolean() == live, "refund environment");
        movement(withdrawal, id(refund.path("balance_transaction")), refundId, "refund");
        movement(reversal, id(refund.path("failure_balance_transaction")), refundId, "refund_failure");
        String nativeCurrency = text(withdrawal.path("currency")).toUpperCase(Locale.ROOT);
        long debit = integer(withdrawal.path("amount")), credit = integer(reversal.path("amount"));
        require(!id(withdrawal).equals(id(reversal)) && nativeCurrency.matches("[A-Z]{3}")
            && nativeCurrency.equalsIgnoreCase(text(reversal.path("currency"))) && debit < 0 && credit > 0
            && Math.negateExact(debit) == credit, "refund complete native principal reversal");
        if (nativeCurrency.equals(currency)) require(credit == amount, "refund presentment principal");
        String evidence = "refund-return:"+refundId+":"+id(withdrawal)+":"+id(reversal)
            +":"+nativeCurrency+":"+credit+":"+currency+":"+amount;
        return new Returned(id(reversal), "available".equals(text(reversal.path("status"))), evidence);
    }

    private static void movement(JsonNode node, String expected, String refund, String type) {
        String identity = id(node);
        require(expected.matches("txn_[A-Za-z0-9_]{1,124}") && expected.equals(identity)
            && "balance_transaction".equals(text(node.path("object"))) && type.equals(text(node.path("type")))
            && refund.equals(id(node.path("source")))
            && Set.of("available","pending").contains(text(node.path("status"))), "refund balance binding");
        require(Math.subtractExact(integer(node.path("amount")), integer(node.path("fee")))
            == integer(node.path("net")), "refund fee reconciliation");
    }

    private static long integer(JsonNode node) {
        require(node.isIntegralNumber(), "exact financial integer");
        return new BigInteger(node.toString()).longValueExact();
    }
    private static String id(JsonNode node) { return node.isString()?node.asString():text(node.path("id")); }
    private static String text(JsonNode node) { return node == null || node.isNull()?"":node.asString(""); }
    private static void require(boolean valid, String detail) { if (!valid) throw new IllegalStateException("Unverified "+detail); }
}
