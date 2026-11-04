package com.qingfeng.music.infrastructure.persistence.work;

import java.time.Instant;
/** 仅供持久化使用，不跨出基础设施层。 */
public class WorkRow {
    public String id, ownerId, recipient, occasion, story, message, style, title,vocalGender;
    public long version;
    public Instant createdAt, updatedAt;
}
