package com.qingfeng.music.infrastructure.persistence.agent;

import java.time.Instant;

public class ConversationRow {
    public String id, creatorId, currentWorkId;
    public Instant createdAt, updatedAt;
}
