package com.qingfeng.music.server.payment;

import java.math.BigDecimal;

/** Provider boundary. A gateway must never treat a browser redirect as payment confirmation.
 * Each adapter validates provider identifiers before storing or using them in paths, and validates
 * external checkout links against its trusted HTTPS hosts before returning them to the client.
 */
public interface PaymentGateway {
    record Link(String id, String url) {}
    record Snapshot(String id, String reference, String status, boolean reusable, String currency,
                    BigDecimal amount, String successfulPaymentId) {}
    record Notification(String reference, String linkId) {}
    boolean configured();
    Link create(String reference, String title, BigDecimal amount, String currency);
    Snapshot retrieve(String linkId);
    /** A valid unrelated notification returns null; invalid notifications must fail. */
    Notification notification(String timestamp, String signature, byte[] body);
}
