package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.exception.DomainException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import com.qingfeng.music.agent.application.AgentReadOnlyLookup;

/**
 * Per-process circuit breaker around the planner, never around business execution.
 * There are no retries or alternate paid providers. Shared admission/budgets remain authoritative.
 */
public final class ResilientAgentChatModel implements AgentChatModel {
    private enum State { CLOSED, OPEN, HALF_OPEN }
    private record Permit(long generation, boolean probe) {}

    private final AgentChatModel delegate;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;
    private State state = State.CLOSED;
    private int failures;
    private long generation;
    private Instant openUntil = Instant.MIN;

    public ResilientAgentChatModel(AgentChatModel delegate, int failureThreshold, Duration cooldown) {
        this(delegate, failureThreshold, cooldown, Clock.systemUTC());
    }

    public ResilientAgentChatModel(AgentChatModel delegate, int failureThreshold, Duration cooldown, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate);
        this.clock = Objects.requireNonNull(clock);
        if (failureThreshold < 1 || failureThreshold > 100) throw new IllegalArgumentException("Invalid planner circuit failure threshold");
        if (cooldown == null || cooldown.compareTo(Duration.ofSeconds(1)) < 0 || cooldown.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid planner circuit cooldown");
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
    }

    @Override public Plan respond(Context context) {
        Objects.requireNonNull(context);
        checkRequest();
        Permit permit = acquire();
        if (permit == null) return fallback(context);
        boolean settled = false;
        try {
            checkRequest();
            Plan plan = Objects.requireNonNull(delegate.respond(context));
            checkRequest();
            succeeded(permit);
            settled = true;
            return plan;
        } catch (DomainException failure) {
            // Request/budget/authorization errors are never disguised as a model outage.
            if (!"AGENT_CHAT_UNAVAILABLE".equals(failure.code())) throw failure;
            checkRequest();
            failed(permit);
            settled = true;
            return fallback(context);
        } finally {
            if (!settled) abandoned(permit);
        }
    }

    private static void checkRequest() {
        AgentRequestScope.check();
        if (Thread.currentThread().isInterrupted()) throw AgentRequestScope.expired();
    }

    private synchronized Permit acquire() {
        if (state == State.CLOSED) return new Permit(generation, false);
        if (state == State.HALF_OPEN || clock.instant().isBefore(openUntil)) return null;
        state = State.HALF_OPEN;
        return new Permit(generation, true);
    }

    private synchronized void succeeded(Permit permit) {
        if (permit.generation() != generation) return;
        if (permit.probe() && state == State.HALF_OPEN) {
            state = State.CLOSED;
            generation++;
        }
        if (state == State.CLOSED) failures = 0;
    }

    private synchronized void failed(Permit permit) {
        if (permit.generation() != generation) return;
        if (permit.probe() || ++failures >= failureThreshold) {
            state = State.OPEN;
            openUntil = clock.instant().plus(cooldown);
            generation++; // Late completions from the old generation cannot close this circuit.
        }
    }

    private synchronized void abandoned(Permit permit) {
        if (permit.probe() && permit.generation() == generation && state == State.HALF_OPEN) {
            // A cancelled/oversized request is not provider failure. Let another valid request probe.
            state = State.OPEN;
            generation++;
        }
    }

    private static Plan fallback(Context context) {
        checkRequest();
        String action=AgentReadOnlyLookup.match(context.text(),context.capabilities(),true);
        if(action!=null) {
            String target=switch(action) {
                case "list_works" -> "你的作品列表";
                case "get_credits" -> "你的积分余额";
                default -> "已接入的功能";
            };
            String reply=english(context)?"The music assistant is temporarily limited. I can run this read-only lookup."
                :"对话理解暂时不可用，我会查询"+target+"。";
            return new Plan(action,Map.of(),reply);
        }
        return Plan.unavailable(english(context)
                ? "The music assistant is temporarily unavailable. This step has not started any new business operation. Please try again later."
                : "对话理解暂时不可用，这一步没有发起新的业务操作。请稍后再试。");
    }

    private static boolean english(Context context) {
        return context.locale() != null && context.locale().toLowerCase(Locale.ROOT).startsWith("en");
    }
}
