package com.qingfeng.music.conversation.api;

import java.time.Instant;
import java.util.List;

/** 会话仓储端口：business 只依赖本接口，SQL 与 PO 全部留在 infrastructure。 */
public interface ConversationRepository {

    Conversation findByCreator(String creatorId);

    Conversation create(String id, String creatorId, Instant now);

    List<AgentMessage> listMessages(String conversationId);

    long nextSeq(String conversationId);

    void insertMessage(AgentMessage message);

    void updateCurrentWork(String conversationId, String workId, Instant now);

    ConversationState findState(String conversationId);

    void createState(String conversationId, Instant now);

    /** 带 revision 条件更新，返回受影响行数；0 表示并发冲突，由应用层抛 409 语义异常。 */
    int updateState(String conversationId, String currentSongJson, long expectedRevision, Instant now);

    /** 清空消息、current_work_id 指针与快照，但保留 conversation 行以稳定会话 ID。 */
    void clear(String conversationId, Instant now);
}
