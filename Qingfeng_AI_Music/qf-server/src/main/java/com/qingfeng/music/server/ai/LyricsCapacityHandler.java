package com.qingfeng.music.server.ai;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Recovery;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.infrastructure.provider.AiModelUsage;
import com.qingfeng.music.lyrics.application.CompositionRequests;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsCompositionService.Command;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
public final class LyricsCapacityHandler implements AiCapacityWorker.Handler {
    public record Submission(Job job, LyricsCompositionService.RequestState state) {}
    private record Input(Command command, List<String> photos) {}
    private final JdbcAiWorkQueue queue;
    private final AiCapacity capacity;
    private final LyricsCompositionService composer;
    private final CompositionRequests requests;
    private final AiPhotoInputs storage;
    private final AiTextQuota quota;
    private final ObjectMapper json = new ObjectMapper();

    public LyricsCapacityHandler(JdbcAiWorkQueue queue, AiCapacity capacity, LyricsCompositionService composer,
                                 CompositionRequests requests, AiPhotoInputs storage, AiTextQuota quota) {
        this.queue = queue;
        this.capacity = capacity;
        this.composer = composer;
        this.requests = requests;
        this.storage = storage;
        this.quota = quota;
    }

    @Override public Lane lane() { return Lane.LYRICS; }

    public Submission submit(String owner, String work, String key, Command command, List<byte[]> photos) {
        // Verify ownership before creating any private input objects.
        composer.requestState(owner, work, key);
        String identity = json.writeValueAsString(new Input(command, photos.stream().map(LyricsCapacityHandler::digest).toList()));
        var keys = storage.save(photos);
        boolean attached = false;
        try {
            String payload = json.writeValueAsString(new Input(command, keys));
            var result = queue.submit(lane(), owner, work, key, payload, identity, capacity.limits(lane()),
                job -> composer.reserveQueued(owner, work, key, command, photos));
            attached = payload.equals(result.payload());
            return new Submission(result, composer.requestState(owner, work, key));
        } finally {
            if (!attached) storage.discard(keys);
        }
    }

    /** Compatibility path for older callers, including the existing tool interfaces. */
    public LyricsApi.Revision compose(String owner, String work, String key, Command command, List<byte[]> photos) {
        var state = submit(owner, work, key, command, photos).state();
        if ("QUEUED".equals(state.status()) || "PENDING".equals(state.status())) {
            return AgentLyricsWait.await(work, key, () -> awaitResult(owner, work, key, state));
        }
        return awaitResult(owner, work, key, state);
    }

    private LyricsApi.Revision awaitResult(String owner, String work, String key, LyricsCompositionService.RequestState state) {
        long deadline = System.nanoTime() + Duration.ofSeconds(115).toNanos();
        for (;;) {
            if (!"QUEUED".equals(state.status()) && !"PENDING".equals(state.status()) || System.nanoTime() >= deadline)
                return readTerminal(owner, work, state);
            com.qingfeng.music.common.concurrent.AgentRequestScope.check();
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(1100, 1900));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DomainException("LYRICS_REQUEST_PENDING", "歌词请求已受理，请查看原请求状态。");
            }
            state = composer.requestState(owner, work, key);
        }
    }

    public LyricsApi.Revision completed(Job job) {
        var state = composer.requestState(job.owner(), job.scope(), job.requestKey());
        return readTerminal(job.owner(), job.scope(), state);
    }

    private LyricsApi.Revision readTerminal(String owner, String work, LyricsCompositionService.RequestState state) {
        if ("SUCCEEDED".equals(state.status())) return composer.result(owner, work, state.revisionId());
        if ("FAILED".equals(state.status()))
            throw new DomainException(state.errorCode() == null ? "LYRICS_REQUEST_FAILED" : state.errorCode(),
                "歌词编写未完成，请查看原请求状态。");
        throw new DomainException("LYRICS_REQUEST_PENDING", "歌词请求已受理，请查看原请求状态。");
    }

    @Override public AiCapacityWorker.Completion run(Job job) {
        Input input = json.readValue(job.payload(), Input.class);
        try (var usage = AiModelUsage.open(quota)) {
            var prior = requests.find(job.scope(), job.requestKey()).orElseThrow();
            if ("QUEUED".equals(prior.status())) {
                var photos = storage.read(input.photos());
                composer.executeQueued(job.owner(), job.scope(), job.requestKey(), input.command(), photos);
            }
        } catch (DomainException failure) {
            requests.failQueued(job.scope(), job.requestKey(), failure.code());
        }
        var saved = requests.find(job.scope(), job.requestKey()).orElseThrow();
        if ("QUEUED".equals(saved.status())) return AiCapacityWorker.Completion.waitFor(Stage.START, Duration.ofSeconds(2));
        if ("PENDING".equals(saved.status())) return AiCapacityWorker.Completion.unknown("LYRICS_REQUEST_PENDING");
        storage.discard(input.photos());
        return switch (saved.status()) {
            case "SUCCEEDED" -> AiCapacityWorker.Completion.success();
            case "FAILED" -> AiCapacityWorker.Completion.failed(saved.errorCode());
            default -> AiCapacityWorker.Completion.unknown(saved.errorCode());
        };
    }

    @Override public void expired(Job job) {
        requests.failQueued(job.scope(), job.requestKey(), "AI_QUEUE_EXPIRED");
        discardAfterCommit(job);
    }

    @Override public Recovery interrupted(Job job) {
        var existing = requests.find(job.scope(), job.requestKey()).orElseThrow();
        if ("QUEUED".equals(existing.status())) return Recovery.resume(Stage.START);
        discardAfterCommit(job);
        return switch (existing.status()) {
            case "SUCCEEDED" -> Recovery.success();
            case "FAILED" -> new Recovery(State.FAILED, null, existing.errorCode());
            default -> {
                requests.unknown(job.scope(), job.requestKey(), "LYRICS_REQUEST_INTERRUPTED");
                yield Recovery.unknown();
            }
        };
    }

    private void discardAfterCommit(Job job) {
        var input = json.readValue(job.payload(), Input.class);
        if (input.photos().isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { storage.discard(input.photos()); }
        });
    }

    private static String digest(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
