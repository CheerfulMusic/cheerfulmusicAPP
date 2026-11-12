package com.qingfeng.music.infrastructure.persistence.agent;

import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.api.ConversationState;

import java.time.Instant;
import java.util.List;

/** 端口实现。命名沿用项目既有 Jdbc*Repository 习惯；实际 SQL 由 ConversationMapper 注解提供。 */
public final class JdbcConversationRepository implements ConversationRepository {

    private final ConversationMapper mapper;

    public JdbcConversationRepository(ConversationMapper mapper) { this.mapper = mapper; }

    @Override
    public Conversation findByCreator(String creatorId) {
        ConversationRow row = mapper.findByCreator(creatorId);
        return row == null ? null : new Conversation(row.id, row.creatorId, row.currentWorkId,
                row.createdAt, row.updatedAt);
    }

    @Override
    public Conversation create(String id, String creatorId, Instant now) {
        mapper.insert(id, creatorId, now);
        return new Conversation(id, creatorId, null, now, now);
    }

    @Override
    public List<AgentMessage> listMessages(String conversationId) {
        return mapper.listMessages(conversationId).stream().map(row -> new AgentMessage(
                row.id, row.conversationId, row.seq, row.role, row.kind, row.state,
                row.contentJson, row.taskId, row.createdAt)).toList();
    }

    private List<AgentMessage> messages(List<MessageRow> rows) {
        return rows.stream().map(row->new AgentMessage(row.id,row.conversationId,row.seq,row.role,row.kind,row.state,row.contentJson,row.taskId,row.createdAt)).toList();
    }
    @Override public List<AgentMessage> messagesBefore(String id,long before,int limit) { return messages(mapper.messagesBefore(id,before,limit)); }
    @Override public List<AgentMessage> messagesAfter(String id,long after,int limit) { return messages(mapper.messagesAfter(id,after,limit)); }
    @Override public long historyEpoch(String id) { return mapper.historyEpoch(id); }
    @Override public List<AgentMessage> recentActionMessages(String id,int limit) { return messages(mapper.recentActionMessages(id,limit)); }

    @Override
    public long nextSeq(String conversationId) { return mapper.nextSeq(conversationId); }

    @Override
    public void insertMessage(AgentMessage message) {
        MessageRow row = new MessageRow();
        row.id = message.id();
        row.conversationId = message.conversationId();
        row.seq = message.seq();
        row.role = message.role();
        row.kind = message.kind();
        row.state = message.state();
        row.contentJson = message.contentJson();
        row.taskId = message.taskId();
        row.createdAt = message.createdAt();
        mapper.insertMessage(row);
    }

    @Override
    public void updateCurrentWork(String conversationId, String workId, Instant now) {
        mapper.updateCurrentWork(conversationId, workId, now);
    }

    @Override
    public ConversationState findState(String conversationId) {
        StateRow row = mapper.findState(conversationId);
        return row == null ? null : new ConversationState(row.conversationId, row.currentSongJson, row.revision);
    }

    @Override
    public void createState(String conversationId, Instant now) { mapper.insertState(conversationId, now); }

    @Override
    public int updateState(String conversationId, String currentSongJson, long expectedRevision, Instant now) {
        return mapper.updateState(conversationId, currentSongJson, expectedRevision, now);
    }

    @Override
    public void clear(String conversationId, Instant now) {
        mapper.lockForReset(conversationId);
        if(mapper.recentRunning(conversationId,now.minusSeconds(600))>0) {
            throw new com.qingfeng.music.common.exception.DomainException("AGENT_TURN_IN_PROGRESS","当前消息仍在处理，请先等待或刷新结果。持续 10 分钟未恢复时可重新开始；已有作品和生成任务会保留。");
        }
        mapper.archiveRequests(conversationId);
        mapper.deleteMessages(conversationId);
        mapper.clearWork(conversationId, now);
        mapper.clearState(conversationId, now);
    }

    @Override
    public List<AgentMessage> findPendingPlaceholders() {
        return mapper.findPendingPlaceholders(AgentMessage.KIND_PLACEHOLDER, AgentMessage.STATE_PENDING)
                .stream().map(row -> new AgentMessage(
                row.id, row.conversationId, row.seq, row.role, row.kind, row.state,
                row.contentJson, row.taskId, row.createdAt)).toList();
    }

    @Override
    public void updateMessageState(String messageId, String state) {
        mapper.updateMessageState(messageId, state);
    }
}
