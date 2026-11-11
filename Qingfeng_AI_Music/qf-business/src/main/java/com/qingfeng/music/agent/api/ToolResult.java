package com.qingfeng.music.agent.api;

/** 工具调用结果契约，W1 冻结。W2 的 AgentToolDispatcher 统一产出，W3 映射为 SSE 事件。 */
public record ToolResult(
        String status,
        SongCard songCard,
        String progress,
        String errorCode,
        String messageId) {

    public static final String OK = "ok";
    public static final String BUSY = "busy";
    public static final String ERROR = "error";
    public static final String CLARIFY = "clarify";

    public static ToolResult ok(String messageId, SongCard card) {
        return new ToolResult(OK, card, null, null, messageId);
    }

    public static ToolResult busy(String messageId, String progress) {
        return new ToolResult(BUSY, null, progress, null, messageId);
    }

    public static ToolResult clarify(String messageId, String errorCode) {
        return new ToolResult(CLARIFY, null, null, errorCode, messageId);
    }

    public static ToolResult error(String messageId, String errorCode) {
        return new ToolResult(ERROR, null, null, errorCode, messageId);
    }
}
