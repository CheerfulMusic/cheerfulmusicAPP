package com.qingfeng.music.server.ai;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.api.CoverApi.Edit;
import com.qingfeng.music.cover.application.CoverRepository;
import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Recovery;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
public final class CoverCapacityHandler implements AiCapacityWorker.Handler {
    private final CoverService covers;
    private final CoverRepository repository;

    public CoverCapacityHandler(CoverService covers, CoverRepository repository) {
        this.covers = covers;
        this.repository = repository;
    }

    @Override public Lane lane() { return Lane.COVER; }

    @Override public Set<Stage> stages() { return Set.of(Stage.START, Stage.POLL, Stage.ARCHIVE); }

    @Override public AiCapacityWorker.Completion run(Job job) {
        try {
            return next(job.stage(), covers.runQueued(job.owner(), job.scope(), job.requestKey()));
        } catch (DomainException failure) {
            if ("COVER_PROVIDER_UNAVAILABLE".equals(failure.code())) {
                return AiCapacityWorker.Completion.waitFor(job.stage(), Duration.ofSeconds(30));
            }
            if ("WORK_NOT_FOUND".equals(failure.code())) {
                repository.expireUnstarted(job.scope(), job.requestKey());
                return AiCapacityWorker.Completion.failed(failure.code());
            }
            throw failure;
        }
    }

    private AiCapacityWorker.Completion next(Stage current, Edit edit) {
        Duration delay = Duration.ofSeconds(ThreadLocalRandom.current().nextLong(12, 22));
        return switch (edit.status()) {
            case "QUEUED", "SUBMITTING" -> AiCapacityWorker.Completion.waitFor(Stage.START, delay);
            case "RUNNING" -> AiCapacityWorker.Completion.waitFor(Stage.POLL, delay);
            case "SAVING" -> AiCapacityWorker.Completion.waitFor(Stage.ARCHIVE, current == Stage.ARCHIVE ? delay : Duration.ZERO);
            case "SUCCEEDED" -> AiCapacityWorker.Completion.success();
            case "FAILED" -> AiCapacityWorker.Completion.failed(edit.errorCode());
            default -> AiCapacityWorker.Completion.unknown(edit.errorCode());
        };
    }

    @Override public void expired(Job job) {
        repository.expireUnstarted(job.scope(), job.requestKey());
    }

    @Override public Recovery interrupted(Job job) {
        return repository.request(job.scope(), job.requestKey()).map(saved -> switch (saved.edit().status()) {
            case "QUEUED", "SUBMITTING" -> Recovery.resume(Stage.START);
            case "RUNNING" -> Recovery.resume(Stage.POLL);
            case "SAVING" -> Recovery.resume(Stage.ARCHIVE);
            case "SUCCEEDED" -> Recovery.success();
            case "FAILED" -> new Recovery(State.FAILED, null, saved.edit().errorCode());
            default -> Recovery.unknown();
        }).orElseGet(Recovery::unknown);
    }
}
