package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.api.ToolResult;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工具分发器：W2 的核心协调点。
 * - 校验参数（AgentToolRegistry）
 * - 路由到对应处理器（处理器只做适配 + 投影）
 * - 成功且非只读：经 ConversationService.applyToolResult 单事务落库（消息 + current_song 指针/快照），带 revision 乐观锁
 * - 捕获 PERSONAL_GENERATION_BUSY：写入 PLACEHOLDER/PENDING 排队意图（含 prevTaskId），返回 busy，不抛 500
 * - 其余 DomainException：转 error，不抛 500
 */
public class AgentToolDispatcher {

    private static final Set<GenerationStatus> TERMINAL =
            Set.of(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED, GenerationStatus.CANCELLED);

    private final AgentToolRegistry registry;
    private final Map<AgentTool, AgentToolHandler> handlers;
    private final ConversationService conversations;
    private final GenerationApi generation;

    public AgentToolDispatcher(AgentToolRegistry registry,
                               Map<AgentTool, AgentToolHandler> handlers,
                               ConversationService conversations,
                               GenerationApi generation) {
        this.registry = registry;
        this.handlers = handlers;
        this.conversations = conversations;
        this.generation = generation;
    }

    public ToolResult dispatch(AgentTool tool, Object args, String creatorId, String requestKey) {
        registry.validate(tool, args);
        Conversation conversation = conversations.current(creatorId);
        String conversationId = conversation.id();
        var state = conversations.state(creatorId, conversationId);
        AgentToolHandler handler = handlers.get(tool);
        if (handler == null) {
            return ToolResult.error(newMessageId(), "UNKNOWN_TOOL");
        }
        String messageId = newMessageId();
        AgentToolHandler.ToolContext ctx = new AgentToolHandler.ToolContext(
                creatorId, conversationId, requestKey, conversation, state);
        try {
            AgentToolHandler.AgentToolResult result = handler.execute(ctx, args);
            if (result.readOnly()) {
                return ToolResult.ok(messageId, result.card());
            }
            conversations.applyToolResult(creatorId, conversationId, result.taskId() == null
                            ? messageId : messageId,
                    cardJson(result.card()), result.currentWorkId(), result.currentSongJson(),
                    result.taskId(), result.expectedRevision());
            return ToolResult.ok(messageId, result.card());
        } catch (DomainException error) {
            if ("PERSONAL_GENERATION_BUSY".equals(error.code())) {
                return ToolResult.busy(messageId, "已有歌曲正在生成，请等当前任务完成后再发送生成请求。尚未自动排队或重复扣费。");
            }
            // 其余业务异常（含 CONFLICT / INVALID_* / UNAVAILABLE）向上抛，由全局处理器映射 HTTP 状态码：
            // CONFLICT→409（并发/陈旧写入）、INVALID_*→400、UNAVAILABLE→503。
            throw error;
        }
    }

    /** 由 PendingIntentDispatcher 调用，重新分发排队意图；复用同一 requestKey 保证幂等、不重复扣费。 */
    public ToolResult redispatch(AgentTool tool, Object args, String creatorId, String requestKey) {
        return dispatch(tool, args, creatorId, requestKey);
    }

    /** 找到该创作者当前在途（未结束）的生成任务，作为 busy 排队意图的触发哨兵。 */
    public String findInFlightTask(String creatorId, String workId) {
        if (workId == null || generation == null) return null;
        try {
            List<GenerationApi.Task> tasks = generation.list(creatorId, workId);
            return tasks.stream()
                    .filter(task -> !TERMINAL.contains(task.status()))
                    .map(GenerationApi.Task::id)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String serializeIntent(AgentTool tool, Object args, String requestKey, String prevTaskId,
                                  String workId, String conversationId, String creatorId) {
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("tool", tool.id());
        intent.put("args", AgentJson.read(AgentToolArgs.serialize(tool, args)));
        intent.put("requestKey", requestKey);
        intent.put("prevTaskId", prevTaskId);
        intent.put("workId", workId);
        intent.put("conversationId", conversationId);
        intent.put("creatorId", creatorId);
        return AgentJson.write(intent);
    }

    static Map<String, Object> deserializeIntent(String json) {
        return AgentJson.read(json);
    }

    private static String cardJson(SongCard card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songId", card.songId());
        m.put("title", card.title());
        m.put("style", card.style());
        m.put("lyrics", card.lyrics());
        m.put("audioUrl", card.audioUrl());
        m.put("status", card.status());
        m.put("coverUrl", card.coverUrl());
        m.put("revision", Long.toString(card.revision()));
        m.put("revisionId", card.revisionId());
        m.put("assetId", card.assetId());
        m.put("audioOrigin", card.audioOrigin());
        m.put("taskId", card.taskId());
        return AgentJson.write(m);
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString();
    }
}
