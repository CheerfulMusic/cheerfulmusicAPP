package com.qingfeng.music.server.payment;

/** Consumes only its own verified, durably accepted provider events. No broker is required. */
public interface VerifiedPaymentProcessor {
    String provider();
    /** Claims at most one persisted event; false means this provider has no due event. */
    boolean processNextEvent();
}
