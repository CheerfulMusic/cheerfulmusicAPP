package com.qingfeng.music.agent.api;

/** Durable claim before any external call. An interrupted claim is never executed twice automatically. */
public interface AgentTurnStore {
    record Claim(boolean fresh, String resultJson) {}
    Claim claim(String creatorId, String conversationId, String requestKey, String text);
    void complete(String creatorId, String conversationId, String requestKey, String resultJson);
    /** Verify the durable turn is still live and renew its reset-protection lease. */
    default void assertActive(String creatorId,String conversationId,String requestKey) {}
}
