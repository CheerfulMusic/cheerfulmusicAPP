package com.qingfeng.music.infrastructure.persistence.agent;

import java.time.Instant;

public class MessageRow {
    public String id, conversationId, role, kind, state, contentJson, taskId;
    public long seq;
    public Instant createdAt;
}
