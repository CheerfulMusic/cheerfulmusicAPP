package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.agent.model.AgentJson;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排队意图自动触发。W2 实现 busy 排队的「自动继续」：
 * 每 3 秒扫描 PLACEHOLDER/PENDING 消息；若其 prevTaskId 指向的生成任务已进入终态
 * （SUCCEEDED / FAILED / CANCELLED），把该占位标记为 RUNNING（防止重复触发），
 * 再用原 requestKey 重新分发同一意图。复用 requestKey 保证幂等、不重复扣费，用户无需手动重试。
 *
 * 注意：不引入消息队列，复用 GenerationWorker 同款 3 秒 @Scheduled 轮询节奏。
 */
public class PendingIntentDispatcher {

    private static final Set<GenerationStatus> TERMINAL =
            Set.of(GenerationStatus.SUCCEEDED, GenerationStatus.FAILED, GenerationStatus.CANCELLED);

    private final ConversationRepository repository;
    private final AgentToolDispatcher dispatcher;
    private final GenerationApi generation;

    public PendingIntentDispatcher(ConversationRepository repository, AgentToolDispatcher dispatcher,
                                   GenerationApi generation) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.generation = generation;
    }

    /** 单次扫描；单元测试直接调用，避免真等 3 秒。 */
    public int processOnce() {
        List<AgentMessage> pending = repository.findPendingPlaceholders();
        int fired = 0;
        for (AgentMessage message : pending) {
            Map<String, Object> intent = AgentToolDispatcher.deserializeIntent(message.contentJson());
            String prevTaskId = asString(intent.get("prevTaskId"));
            String workId = asString(intent.get("workId"));
            String creatorId = asString(intent.get("creatorId"));
            if (!isTerminal(creatorId, workId, prevTaskId)) continue;

            repository.updateMessageState(message.id(), AgentMessage.STATE_RUNNING);
            String toolId = asString(intent.get("tool"));
            AgentTool tool = AgentTool.fromId(toolId);
            if (tool == null) continue;
            Object args = AgentToolArgs.deserialize(tool, AgentJson.write(asMap(intent.get("args"))));
            String requestKey = asString(intent.get("requestKey"));
            dispatcher.redispatch(tool, args, creatorId, requestKey);
            fired++;
        }
        return fired;
    }

    private boolean isTerminal(String creatorId, String workId, String taskId) {
        if (taskId == null || taskId.isBlank()) return true;
        if (workId == null || workId.isBlank()) return true;
        if (creatorId == null || creatorId.isBlank()) return true;
        try {
            return generation.list(creatorId, workId).stream()
                    .filter(task -> task.id().equals(taskId))
                    .findFirst()
                    .map(task -> TERMINAL.contains(task.status()))
                    .orElse(true);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
