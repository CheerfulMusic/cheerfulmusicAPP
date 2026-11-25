package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Recovery;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Stage;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import com.qingfeng.music.server.ai.AiCapacity;
import com.qingfeng.music.server.ai.AiCapacityWorker;
import com.qingfeng.music.server.ai.AiTextQuota;
import com.qingfeng.music.server.ai.AgentLyricsWait;
import com.qingfeng.music.infrastructure.provider.AiModelUsage;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = {"qingfeng.ai.capacity.enabled", "qingfeng.agent.chat.enabled"}, havingValue = "true")
public final class AgentCapacityHandler implements AiCapacityWorker.Handler {
    public record Input(String text, String locale, boolean paidConfirmed, AgentChatService.DisplayedContext displayedContext) {}
    public record Receipt(String requestKey, String status, String errorCode) {}

    private final AgentChatService chat;
    private final JdbcAgentAdmission admission;
    private final JdbcAiWorkQueue queue;
    private final AiCapacity capacity;
    private final AgentAdmission.Limits admissionLimits;
    private final Duration deadline;
    private final AiTextQuota quota;
    private final ObjectMapper json = new ObjectMapper();

    public AgentCapacityHandler(AgentChatService chat, JdbcAgentAdmission admission, JdbcAiWorkQueue queue,
                                AiCapacity capacity, AgentAdmission.Limits admissionLimits, Environment environment, AiTextQuota quota) {
        this.chat = chat;
        this.admission = admission;
        this.queue = queue;
        this.capacity = capacity;
        this.admissionLimits = admissionLimits;
        this.quota = quota;
        this.deadline = Duration.ofSeconds(environment.getProperty("qingfeng.agent.admission.deadline-seconds", Long.class, 150L));
    }

    @Override public Lane lane() { return Lane.AGENT; }

    public Job submit(String owner, String conversation, String requestKey, Input input) {
        validate(conversation, requestKey);
        if (input.text() == null || input.text().isBlank() || input.text().length() > 4000)
            throw new DomainException("INVALID_AGENT_MESSAGE", "请输入 1–4000 字的消息。");
        var normalized = new Input(input.text().trim(), input.locale() == null ? "zh-CN" : input.locale(),
            input.paidConfirmed(), input.displayedContext());
        String payload = json.writeValueAsString(normalized);
        String turnKey = AgentCreationRequest.turnKey(normalized.text(), requestKey);
        return queue.submit(lane(), owner, conversation, turnKey, payload, capacity.limits(lane()),
            job -> admission.acquireQueued(owner, conversation, turnKey, normalized.text(), normalized.locale(),
                input.paidConfirmed(), job.queueUntil()));
    }

    public Receipt receipt(Job job) {
        return new Receipt(job.requestKey(), job.state().name(), job.errorCode());
    }

    public Job find(String owner, String conversation, String requestKey) {
        validate(conversation, requestKey);
        return queue.find(lane(), owner, conversation, requestKey)
            .orElseThrow(() -> new DomainException("AGENT_TURN_NOT_FOUND", "对话请求不存在。"));
    }

    @Override public AiCapacityWorker.Completion run(Job job) {
        if (chat.hasCompleted(job.owner(), job.scope(), job.requestKey())) {
            admission.finish(ticket(job), true);
            return AiCapacityWorker.Completion.success();
        }
        var input = json.readValue(job.payload(), Input.class);
        var ticket = ticket(job);
        var started = new AtomicBoolean();
        try (var ignored = new AgentRequestScope(deadline, admissionLimits.maxPromptBytes()).enter();
             var usage = AiModelUsage.open(quota);
             var wait = AgentLyricsWait.open(queue, job, capacity.limits(lane()))) {
            chat.send(job.owner(), job.scope(), input.text(), job.requestKey(), input.locale(), input.displayedContext(),
                claim -> {
                    var result = admission.startQueuedTurn(ticket, job.scope(), claim);
                    started.set(result.fresh());
                    return result;
                });
            admission.finish(ticket, true);
            return AiCapacityWorker.Completion.success();
        } catch (DomainException error) {
            if (!started.get()) {
                admission.abandonQueued(ticket);
                return AiCapacityWorker.Completion.failed(error.code());
            }
            admission.finish(ticket, false);
            return AiCapacityWorker.Completion.unknown(error.code());
        }
    }

    @Override public void expired(Job job) {
        admission.abandonQueued(ticket(job));
    }

    @Override public Recovery interrupted(Job job) {
        if (chat.hasCompleted(job.owner(), job.scope(), job.requestKey())) {
            admission.finish(ticket(job), true);
            return Recovery.success();
        }
        if (job.state() == State.SUSPENDED) {
            var lyrics = queue.lyricsDependency(job);
            if (lyrics.isPresent() && lyrics.get().state() == State.SUCCEEDED
                    && chat.reconcileLyrics(job.owner(), job.scope(), job.requestKey(), lyrics.get().scope())) {
                admission.finish(ticket(job), true);
                return Recovery.success();
            }
        }
        return switch (admission.queuedState(ticket(job))) {
            case "RESERVED" -> Recovery.resume(Stage.START);
            case "CANCELLED", "ABANDONED" -> new Recovery(State.FAILED, null, "AGENT_PLAN_STALE");
            default -> {
                admission.finish(ticket(job), false);
                yield Recovery.unknown();
            }
        };
    }

    private static AgentAdmission.Ticket ticket(Job job) {
        return new AgentAdmission.Ticket(JdbcAgentAdmission.hash(job.scope() + ":" + job.requestKey()), job.owner(), false);
    }

    private static void validate(String conversation, String key) {
        try {
            if (conversation.length() != 36 || key.length() != 36) throw new IllegalArgumentException();
            UUID.fromString(conversation);
            UUID.fromString(key);
        } catch (RuntimeException invalid) {
            throw new DomainException("INVALID_REQUEST_KEY", "会话或消息标识无效。");
        }
    }
}
