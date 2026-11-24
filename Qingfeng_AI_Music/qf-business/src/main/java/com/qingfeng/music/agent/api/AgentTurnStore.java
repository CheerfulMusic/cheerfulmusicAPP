package com.qingfeng.music.agent.api;

/** Durable claim before any external call. An interrupted claim is never executed twice automatically. */
public interface AgentTurnStore {
    record Claim(boolean fresh, String resultJson, long userSeq) {
        public Claim(boolean fresh,String resultJson){this(fresh,resultJson,0);}
    }
    default Claim claim(String creatorId,String conversationId,String requestKey,String text){
        return claim(creatorId,conversationId,requestKey,text,()->{});
    }
    /** Called under the conversation lock only for a fresh turn, before writing the user message. */
    Claim claim(String creatorId,String conversationId,String requestKey,String text,Runnable beforeInsert);
    default java.util.Optional<String> completedResult(String owner, String conversation, String key) {
        return java.util.Optional.empty();
    }
    default boolean lockIfActive(String owner, String conversation, String key) {
        return false;
    }
    void complete(String creatorId, String conversationId, String requestKey, String resultJson);
    /** Verify the durable turn is still live and renew its reset-protection lease. */
    default void assertActive(String creatorId,String conversationId,String requestKey) {}
}
