package com.qingfeng.music.conversation.api;

/** 轻量快照：只存元数据，lyrics/audioUrl 由 W2 实时聚合，不在此落地副本。 */
public record ConversationState(String conversationId, String currentSongJson, long revision) {}
