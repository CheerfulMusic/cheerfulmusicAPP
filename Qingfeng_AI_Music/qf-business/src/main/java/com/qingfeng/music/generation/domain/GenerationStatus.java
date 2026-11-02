package com.qingfeng.music.generation.domain;

import com.qingfeng.music.common.exception.DomainException;

/** 未确认供应商是否受理的提交，不得自动重新付费生成。 */
public enum GenerationStatus {
    QUEUED, SUBMITTING, RUNNING, SAVING, SUCCEEDED, FAILED, SUBMISSION_UNKNOWN;

    public GenerationStatus transitionTo(GenerationStatus next) {
        boolean allowed = next != null && switch (this) {
            case QUEUED -> next == SUBMITTING || next == FAILED;
            case SUBMITTING -> next == RUNNING || next == SAVING || next == FAILED || next == SUBMISSION_UNKNOWN;
            case SUBMISSION_UNKNOWN -> next == RUNNING || next == SAVING || next == FAILED;
            case RUNNING -> next == SAVING || next == FAILED;
            case SAVING -> next == SUCCEEDED || next == FAILED;
            case SUCCEEDED, FAILED -> false;
        };
        if (!allowed) throw new DomainException("INVALID_TASK_TRANSITION", "不允许的生成任务状态转换");
        return next;
    }
}
