package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationState;

/**
 * 工具处理器契约。每个处理器只做「参数适配 → 调用现有 Service → 投影 SongCard」，
 * 不写会话/消息，不引入业务规则。会话落库统一由 AgentToolDispatcher 经 ConversationService 完成。
 */
public interface AgentToolHandler {

    /** 该处理器对应的工具。 */
    com.qingfeng.music.agent.api.AgentTool tool();

    /** 执行工具。只读工具把 readOnly 置 true，Dispatcher 不会为此落库或推进 revision。 */
    AgentToolResult execute(ToolContext ctx, Object args);

    /** 一次工具调用的执行上下文：包含当前会话指针与轻量快照，供处理器读取而不直接依赖仓储。 */
    record ToolContext(String creatorId, String conversationId, String requestKey,
                       Conversation current, ConversationState state) {}

    /**
     * 处理器的投影结果。Dispatcher 据此决定落库内容与消息状态。
     * - readOnly=true（如 get_current_song）：只回卡片，不落库、不推进 revision。
     * - readOnly=false：Dispatcher 用 currentSongJson + expectedRevision 走 applyToolResult，
     *   带 revision 乐观锁，冲突整体回滚并抛 CONFLICT（→ HTTP 409）。
     */
    record AgentToolResult(SongCard card, String currentWorkId, String currentSongJson,
                           long expectedRevision, String taskId, boolean readOnly) {}
}
