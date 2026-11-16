package com.qingfeng.music.credits;

/** A work starts with one free lyric generation; each completed music generation adds one more. */
public interface LyricFreeAllowance {
    /** Claim one available grant for this request. Caller holds the creator and LYRICS admission locks. */
    boolean reserve(String owner, String work, String requestKey);

    /** Restore a claimed grant only after a definite lyric failure, in the same transaction. */
    void release(String work, String requestKey);

    /** Grant once for a successful music task. Caller holds the creator and LYRICS admission locks. */
    void grantMusic(String owner, String work, String taskId);

    /** Read-only view for a work owned by this creator. */
    boolean freeAvailable(String owner, String work);
}
