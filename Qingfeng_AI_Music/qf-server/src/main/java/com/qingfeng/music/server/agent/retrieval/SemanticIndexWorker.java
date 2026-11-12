package com.qingfeng.music.server.agent.retrieval;

import org.springframework.scheduling.annotation.Scheduled;

public final class SemanticIndexWorker {
    private final JdbcSemanticIndexQueue queue;private final SemanticSource source;private final SemanticGateway gateway;
    public SemanticIndexWorker(JdbcSemanticIndexQueue queue,SemanticSource source,SemanticGateway gateway){this.queue=queue;this.source=source;this.gateway=gateway;}
    @Scheduled(fixedDelayString="${qingfeng.agent.retrieval.poll-delay-ms:3000}")
    public void processOnce(){
        if(!gateway.configured())return;
        var claimed=queue.claim();if(claimed.isEmpty())return;var job=claimed.get();
        try {
            var document=source.load(job.owner(),job.work());
            if(document.isEmpty())gateway.remove(job.owner(),job.work());else gateway.index(document.get());
            // Concurrent edits stay pending. A lost claim schedules repair if this HTTP write was stale.
            queue.complete(job);
        } catch(RuntimeException error){queue.retry(job);}
    }
}
