package com.qingfeng.music.server.config;

import com.qingfeng.music.creation.QuickCreationRepository;
import com.qingfeng.music.creation.QuickCreationService;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserves requests received by this local API before shared workers can claim them. */
@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.quick.local-execution-enabled", havingValue = "true")
public final class LocalQuickCreation {
    private static final System.Logger LOG = System.getLogger(LocalQuickCreation.class.getName());
    private static final Set<String> ACTIVE = Set.of("QUEUED", "WRITING", "LYRICS_READY", "SUBMITTING_MUSIC");
    private final QuickCreationService service;
    private final QuickCreationRepository repository;
    private final TransactionTemplate transactions;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        var thread = new Thread(task, "local-quick-creation");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "local-quick-retry");
        thread.setDaemon(true);
        return thread;
    });

    public LocalQuickCreation(QuickCreationService service, QuickCreationRepository repository,
                              PlatformTransactionManager manager) {
        this.service = service;
        this.repository = repository;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public QuickCreationService.View start(String owner, String work, long version, String key, String language) {
        return accept(() -> service.start(owner, work, version, key, language));
    }

    public QuickCreationService.View retry(String owner, String work, String id) {
        return accept(() -> service.retry(owner, work, id));
    }

    private record Accepted(QuickCreationService.View view, QuickCreationRepository.Entry claimed) {}

    private QuickCreationService.View accept(Supplier<QuickCreationService.View> request) {
        String token = UUID.randomUUID().toString();
        var accepted = transactions.execute(status -> {
            var view = request.get();
            return new Accepted(view, repository.claim(view.workId(), view.id(), token).orElse(null));
        });
        if (accepted.claimed() != null) dispatch(accepted.claimed(), token);
        return accepted.view();
    }

    private void dispatch(QuickCreationRepository.Entry entry, String token) {
        try {
            workers.execute(() -> run(entry, token));
        } catch (RuntimeException failed) {
            repository.release(entry.id(), token);
            throw failed;
        }
    }

    private void run(QuickCreationRepository.Entry entry, String token) {
        boolean continuing = false;
        try {
            if (!service.runClaimed(entry.workId(), entry.id(), token)) return;
            var current = repository.find(entry.workId(), entry.id()).orElseThrow();
            if (ACTIVE.contains(current.status()) && token.equals(current.leaseToken())) {
                retries.schedule(() -> dispatch(current, token), 5, TimeUnit.SECONDS);
                continuing = true;
            }
        } catch (RuntimeException failed) {
            LOG.log(System.Logger.Level.WARNING, "Local quick creation interrupted: id={0}, type={1}",
                    entry.id(), failed.getClass().getSimpleName());
        } finally {
            if (!continuing) repository.release(entry.id(), token);
        }
    }

    @PreDestroy
    public void close() {
        retries.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(115, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
