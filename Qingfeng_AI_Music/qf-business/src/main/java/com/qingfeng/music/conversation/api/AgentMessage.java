package com.qingfeng.music.conversation.api;

import java.time.Instant;

/** 对话消息。kind/state 的取值由应用层常量约束，与 V32 迁移注释一致。 */
public record AgentMessage(String id, String conversationId, long seq, String role,
                           String kind, String state, String contentJson, String taskId,
                           Instant createdAt) {

    public static final String KIND_TEXT = "TEXT";
    public static final String KIND_TOOL_CARD = "TOOL_CARD";
    public static final String KIND_PLACEHOLDER = "PLACEHOLDER";

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_DONE = "DONE";
    public static final String STATE_FAILED = "FAILED";
}
