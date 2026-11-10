package com.qingfeng.music.work.application;

import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.time.Instant;
import java.util.*;

public class WorkService implements WorkApi {
    private final WorkRepository repository;
    public WorkService(WorkRepository repository) { this.repository = repository; }
    @org.springframework.transaction.annotation.Transactional
    public Work create(String owner, Draft draft) {
        return create(owner,draft,UUID.randomUUID().toString());
    }
    @org.springframework.transaction.annotation.Transactional
    public Work create(String owner,Draft draft,String draftKey) {
        if(draftKey==null||!draftKey.matches("[0-9a-fA-F-]{36}"))throw new DomainException("INVALID_DRAFT","草稿标识无效，请刷新后重试");
        validate(draft);
        if(!repository.lockActiveOwner(owner))throw new DomainException("CREATOR_SESSION_CHANGED","登录身份已改变，请刷新后重试；本机草稿已保留");
        Instant now = Instant.now();
        var existing=repository.find(owner,draftKey);if(existing.isPresent())return existing.get();
        Work work = new Work(draftKey, draft.recipient().trim(), draft.occasion(), draft.story(), draft.message(), draft.style(), draft.title(), 0, now, now,draft.vocalGender());
        repository.insert(owner, work); return get(owner, work.id());
    }
    public Work update(String owner, String id, long expectedVersion, Draft draft) {
        validate(draft); Work current = get(owner, id);
        Work work = new Work(id, draft.recipient().trim(), draft.occasion(), draft.story(), draft.message(), draft.style(), draft.title(), expectedVersion + 1, current.createdAt(), Instant.now(),draft.vocalGender());
        if (!repository.update(owner, work, expectedVersion)) throw new DomainException("WORK_VERSION_CONFLICT", "草稿已被其他页面修改，请重新载入后再保存");
        return get(owner, id);
    }
    public Work get(String owner, String id) { return repository.find(owner, id).orElseThrow(() -> new DomainException("WORK_NOT_FOUND", "作品不存在或你无权访问")); }
    public List<Work> list(String owner, int page) { return repository.list(owner, Math.multiplyExact(page, 20), 20); }
    @org.springframework.transaction.annotation.Transactional
    public Work delete(String owner, String id) {
        Work work = get(owner, id);
        repository.delete(owner, id);
        return work;
    }
    private void validate(Draft d) {
        if (d == null || d.recipient() == null || d.recipient().length()>80 || d.occasion()==null || d.occasion().length()>32 || d.story()==null || d.story().length()>4000 || d.message()==null || d.message().length()>500 || d.style()==null || d.style().length()>80 || d.title()==null || d.title().length()>100)
            throw new DomainException("INVALID_DRAFT", "请检查草稿字段和长度");
        if (d.recipient().isBlank() && d.occasion().isBlank() && d.story().isBlank() && d.message().isBlank() && d.title().isBlank())
            throw new DomainException("INVALID_DRAFT", "请先写下一点创作灵感。");
    }
}
