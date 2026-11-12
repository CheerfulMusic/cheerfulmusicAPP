package com.qingfeng.music.conversation.api;

import java.time.Instant;
import java.util.List;

/** 会话仓储端口：business 只依赖本接口，SQL 与 PO 全部留在 infrastructure。 */
public interface ConversationRepository {

    Conversation findByCreator(String creatorId);

    Conversation create(String id, String creatorId, Instant now);

    List<AgentMessage> listMessages(String conversationId);

    /** Compatibility defaults for in-memory adapters; JDBC implements bounded SQL queries. */
    default List<AgentMessage> messagesBefore(String id,long before,int limit) {
        var rows=listMessages(id).stream().filter(m->m.seq()<before).toList();
        return rows.subList(Math.max(0,rows.size()-limit),rows.size());
    }
    default List<AgentMessage> messagesAfter(String id,long after,int limit) {
        return listMessages(id).stream().filter(m->m.seq()>after).limit(limit).toList();
    }
    default long historyEpoch(String id) { return 0; }
    default List<AgentMessage> recentActionMessages(String id,int limit) {
        var rows=listMessages(id).stream().filter(m->"assistant".equals(m.role())&&m.contentJson()!=null
                &&(m.contentJson().contains("\"shareUrl\"")||m.contentJson().contains("\"revoke_share\""))).toList();
        return rows.subList(Math.max(0,rows.size()-limit),rows.size());
    }

    long nextSeq(String conversationId);

    void insertMessage(AgentMessage message);

    void updateCurrentWork(String conversationId, String workId, Instant now);

    ConversationState findState(String conversationId);

    void createState(String conversationId, Instant now);

    /** 带 revision 条件更新，返回受影响行数；0 表示并发冲突，由应用层抛 409 语义异常。 */
    int updateState(String conversationId, String currentSongJson, long expectedRevision, Instant now);

    /** 清空消息、current_work_id 指针与快照，但保留 conversation 行以稳定会话 ID。 */
    void clear(String conversationId, Instant now);

    /** W2 排队：取出所有 PLACEHOLDER 且 state=PENDING 的消息，供 PendingIntentDispatcher 消费。 */
    List<AgentMessage> findPendingPlaceholders();

    /** W2 排队：把已触发（即将重新分发）的占位标记为 RUNNING，避免重复触发。 */
    void updateMessageState(String messageId, String state);
}
