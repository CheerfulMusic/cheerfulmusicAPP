package com.qingfeng.music.events;

/** Publish inside the business transaction. Payloads contain record IDs, never credentials. */
@FunctionalInterface
public interface DurableEvents {
    void publish(String topic, String key, String payload);
    record Event(String id, String topic, String key, String payload) {}
}
