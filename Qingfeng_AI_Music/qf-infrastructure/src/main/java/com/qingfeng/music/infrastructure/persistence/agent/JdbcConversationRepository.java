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
        mapper.deleteMessages(conversationId);
        mapper.clearWork(conversationId, now);
        mapper.clearState(conversationId, now);
    }
}
