package com.qingfeng.music.agent.retrieval;

/** Transactional index invalidation. Disabled deployments never send or enqueue user content. */
public interface SemanticIndexQueue {
    void changed(String owner,String work);
    SemanticIndexQueue NONE=(owner,work)->{};
}
