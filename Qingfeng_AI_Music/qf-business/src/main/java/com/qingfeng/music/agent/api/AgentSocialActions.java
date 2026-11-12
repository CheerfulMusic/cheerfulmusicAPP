package com.qingfeng.music.agent.api;

public interface AgentSocialActions {
    record Result(String reply, String action, String shareUrl, SongCard songCard) {}
    Result execute(String creatorId, String conversationId, String action, String text,
                   String requestKey, SongCard current);
    default boolean liked(String creatorId, SongCard current) { return false; }
}
