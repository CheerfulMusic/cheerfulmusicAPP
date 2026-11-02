package com.qingfeng.music.work.api;

import java.time.Instant;
import java.util.List;

public interface WorkApi {
    Work create(String creatorId, Draft draft);
    Work update(String creatorId, String workId, long expectedVersion, Draft draft);
    Work get(String creatorId, String workId);
    List<Work> list(String creatorId, int page);
    record Draft(String recipient, String occasion, String story, String message, String style, String title) {}
    /** API对象不暴露内部owner、会话或供应商信息。 */
    record Work(String id, String recipient, String occasion, String story, String message, String style, String title, long version, Instant createdAt, Instant updatedAt) {}
}
