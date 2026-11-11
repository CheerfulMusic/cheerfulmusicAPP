package com.qingfeng.music.conversation.api;

import java.time.Instant;

/** 会话主记录。currentWorkId 是「指针」，歌词/音频/状态一律不在此处存副本。 */
public record Conversation(String id, String creatorId, String currentWorkId,
                           Instant createdAt, Instant updatedAt) {}
