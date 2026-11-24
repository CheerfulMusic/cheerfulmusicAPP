package com.qingfeng.music.server.web.app;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
final class AiLegacyResults {
    private record Watch(String id, Function<Job, ResponseEntity<?>> response) {}
    private final Map<DeferredResult<ResponseEntity<?>>, Watch> requests = new HashMap<>();
    private final JdbcAiWorkQueue queue;

    AiLegacyResults(JdbcAiWorkQueue queue) {
        this.queue = queue;
    }

    DeferredResult<ResponseEntity<?>> observe(Job job, Function<Job, ResponseEntity<?>> response) {
        var result = new DeferredResult<ResponseEntity<?>>(185_000L);
        result.onTimeout(() -> result.setErrorResult(new DomainException(
            pendingCode(job), "请求仍在处理，请查看原请求结果。")));
        result.onCompletion(() -> forget(result));
        synchronized (this) {
            if (requests.size() >= 1200)
                throw new DomainException(pendingCode(job), "请求已受理，请查看原请求结果。");
            requests.put(result, new Watch(job.id(), response));
        }
        return result;
    }

    private static String pendingCode(Job job) {
        return job.lane() == JdbcAiWorkQueue.Lane.AGENT ? "AGENT_TURN_IN_PROGRESS" : "LYRICS_REQUEST_PENDING";
    }

    static DeferredResult<ResponseEntity<?>> immediate(ResponseEntity<?> response) {
        var result = new DeferredResult<ResponseEntity<?>>();
        result.setResult(response);
        return result;
    }

    @Scheduled(fixedDelay = 1200)
    void poll() {
        Map<DeferredResult<ResponseEntity<?>>, Watch> copy;
        synchronized (this) {
            if (requests.isEmpty()) return;
            copy = Map.copyOf(requests);
        }
        var ids = new ArrayList<>(copy.values().stream().map(Watch::id).distinct().toList());
        var ready = new HashMap<String, Job>();
        for (int offset = 0; offset < ids.size(); offset += 160) {
            for (Job job : queue.statuses(ids.subList(offset, Math.min(offset + 160, ids.size())))) {
                if (job.state().terminal()) ready.put(job.id(), job);
            }
        }
        copy.forEach((result, watch) -> {
            var job = ready.get(watch.id());
            if (job == null || result.isSetOrExpired()) return;
            try {
                result.setResult(watch.response().apply(job));
            } catch (RuntimeException failure) {
                result.setErrorResult(failure);
            }
        });
    }

    private synchronized void forget(DeferredResult<ResponseEntity<?>> request) {
        requests.remove(request);
    }
}
