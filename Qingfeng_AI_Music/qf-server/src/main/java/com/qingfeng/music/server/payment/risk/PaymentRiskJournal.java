package com.qingfeng.music.server.payment.risk;

import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal audit journal. Call only after signature verification and authoritative order correlation. */
@Service
@Profile({"mysql", "test"})
public class PaymentRiskJournal {
    private static final Set<String> SUPPORTED = Set.of("PAYMENT.CAPTURE.REFUNDED", "CUSTOMER.DISPUTE.CREATED", "CUSTOMER.DISPUTE.UPDATED", "CUSTOMER.DISPUTE.RESOLVED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PaymentRiskJournal(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** Records verified notification identity, without deciding entitlement or refund amounts. */
    public void recordVerifiedPaypalEvent(String orderId, String eventId, String eventType, String resourceId) {
        if (!valid(orderId, 36) || !valid(eventId, 128) || eventType == null || !SUPPORTED.contains(eventType) || !valid(resourceId, 128))
            throw new IllegalArgumentException("invalid payment risk event");
        transactions.executeWithoutResult(tx -> {
            var order = jdbc.queryForList("SELECT id FROM payment_order WHERE id=? FOR UPDATE", orderId);
            if (order.isEmpty()) throw new IllegalArgumentException("unknown payment order");
            var existing = jdbc.query("SELECT order_id,event_type,resource_id FROM payment_risk_event WHERE provider='PAYPAL' AND event_id=?",
                (rs, n) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3)), eventId);
            Event incoming = new Event(orderId, eventType, resourceId);
            if (!existing.isEmpty()) {
                if (!existing.get(0).equals(incoming)) throw new IllegalStateException("conflicting payment event");
                return;
            }
            jdbc.update("INSERT INTO payment_risk_event(provider,event_id,order_id,event_type,resource_id,created_at) VALUES('PAYPAL',?,?,?,?,CURRENT_TIMESTAMP)",
                eventId, orderId, eventType, resourceId);
        });
    }

    private static boolean valid(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private record Event(String order, String type, String resource) {}
}
