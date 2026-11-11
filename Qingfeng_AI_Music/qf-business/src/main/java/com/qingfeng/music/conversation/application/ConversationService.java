package com.qingfeng.music.conversation.application;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.api.ConversationState;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 会话应用层。事务边界全部收敛在本类：
 * - current/appendMessage/reset/applyToolResult 均为单个 @Transactional 方法；
 * - applyToolResult 在同一事务内更新 conversation.current_work_id、agent_message、
 *   agent_conversation_state 三处，state 写入带 revision 条件，冲突整体回滚。
 */
public class ConversationService {

    public static final String CONFLICT = "AGENT_CONVERSATION_CONFLICT";
    public static final String NOT_FOUND = "AGENT_CONVERSATION_NOT_FOUND";

    private final ConversationRepository repository;
    private final Supplier<Instant> clock;

    public ConversationService(ConversationRepository repository) {
        this(repository, Instant::now);
    }

    public ConversationService(ConversationRepository repository, Supplier<Instant> clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Conversation current(String creatorId) {
        requireCreator(creatorId);
        Conversation existing = repository.findByCreator(creatorId);
        if (existing != null) {
            if (repository.findState(existing.id()) == null) repository.createState(existing.id(), clock.get());
            return existing;
        }
        Instant now = clock.get();
        Conversation created = repository.create(UUID.randomUUID().toString(), creatorId, now);
        repository.createState(created.id(), now);
        return created;
    }

    @Transactional
    public AgentMessage appendMessage(String creatorId, String conversationId, String role,
                                      String kind, String state, String contentJson, String taskId) {
        Conversation owned = requireOwned(creatorId, conversationId);
        Instant now = clock.get();
        AgentMessage message = new AgentMessage(UUID.randomUUID().toString(), owned.id(),
                repository.nextSeq(owned.id()), role, kind, state, contentJson, taskId, now);
        repository.insertMessage(message);
        return message;
    }

    public List<AgentMessage> listMessages(String creatorId, String conversationId) {
        return repository.listMessages(requireOwned(creatorId, conversationId).id());
    }

    public ConversationState state(String creatorId, String conversationId) {
        return repository.findState(requireOwned(creatorId, conversationId).id());
    }

    @Transactional
    public void reset(String creatorId, String conversationId) {
        repository.clear(requireOwned(creatorId, conversationId).id(), clock.get());
    }

    /**
     * W2 的工具结果落库入口：三处更新必须同一事务。
     * currentSongJson 写入带 expectedRevision 条件，0 行即抛冲突（Controller 映射 409），
     * 事务回滚保证不会留下「消息已写、快照未写」的不一致状态。
     */
    @Transactional
    public AgentMessage applyToolResult(String creatorId, String conversationId, String messageId,
                                        String contentJson, String currentWorkId,
                                        String currentSongJson, String taskId, long expectedRevision) {
        Conversation owned = requireOwned(creatorId, conversationId);
        Instant now = clock.get();
        repository.updateCurrentWork(owned.id(), currentWorkId, now);
        AgentMessage message = new AgentMessage(
                messageId == null || messageId.isBlank() ? UUID.randomUUID().toString() : messageId,
                owned.id(), repository.nextSeq(owned.id()), "assistant",
                AgentMessage.KIND_TOOL_CARD, AgentMessage.STATE_DONE, contentJson, taskId, now);
        repository.insertMessage(message);
        int updated = repository.updateState(owned.id(), currentSongJson, expectedRevision, now);
        if (updated != 1) throw new DomainException(CONFLICT, "会话状态已更新，请重新读取后再试。");
        return message;
    }

    private Conversation requireOwned(String creatorId, String conversationId) {
        requireCreator(creatorId);
        Conversation conversation = repository.findByCreator(creatorId);
        if (conversation == null || conversationId == null || !conversation.id().equals(conversationId)) {
            throw new DomainException(NOT_FOUND, "会话不存在或不属于当前创建者。");
        }
        return conversation;
    }

    private static void requireCreator(String creatorId) {
        if (creatorId == null || creatorId.isBlank()) throw new DomainException(NOT_FOUND, "缺少创建者身份。");
    }
}
