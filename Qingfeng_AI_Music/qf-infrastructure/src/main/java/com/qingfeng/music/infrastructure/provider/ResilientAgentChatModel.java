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
import java.util.regex.Pattern;

/**
 * Per-process circuit breaker around the planner, never around business execution.
 * There are no retries or alternate paid providers. Shared admission/budgets remain authoritative.
 */
public final class ResilientAgentChatModel implements AgentChatModel {
    private enum State { CLOSED, OPEN, HALF_OPEN }
    private record Permit(long generation, boolean probe) {}

    private static final Pattern WORKS = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查看|查询|列出|显示|看看|看一下|看下)?我的(?:作品|歌曲)(?:列表|清单)?"
            + "|(?:请)?(?:查看|查询|查一下|查|列出|显示)作品(?:列表|清单)?"
            + "|我(?:有|创作了)(?:哪些|什么)(?:作品|歌曲)"
            + "|(?:please )?(?:show|list)(?: me)? my (?:songs|works))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDITS = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查询|查看|查一下|查|显示|看看|看一下|看下)(?:我的|我|当前账号的)?(?:积分|积分余额|剩余积分)"
            + "|(?:我的)?(?:积分余额|剩余积分)"
            + "|我(?:还)?有多少积分"
            + "|(?:我的)?积分(?:还)?(?:剩余|剩下|剩)?多少"
            + "|(?:please )?(?:show|check)(?: me)? my (?:credits|credit balance)"
            + "|how many credits do i have)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPABILITIES = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查看|列出|显示|介绍)(?:全部|所有|可用|已接入)?(?:功能|能力)(?:清单|列表)?"
            + "|能力清单|你(?:能|可以)(?:为我|帮我)?做什么|有什么功能"
            + "|(?:please )?(?:show|list)(?: me)? (?:available )?(?:features|capabilities)"
            + "|what can you do)", Pattern.CASE_INSENSITIVE);

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
        String text = context.text() == null ? "" : context.text().strip();
        // Whole-sentence matching deliberately rejects quotes, negation, compound requests,
        // pasted instructions and history/result content. No model-generated arguments are reused.
        if (text.length() <= 160) {
            text = text.replaceFirst("[。.!！?？]+$", "").strip();
            String action = WORKS.matcher(text).matches() ? "list_works"
                    : CREDITS.matcher(text).matches() ? "get_credits"
                    : CAPABILITIES.matcher(text).matches() ? "list_capabilities" : null;
            if (action != null && readCapability(context, action)) {
                String target = switch (action) {
                    case "list_works" -> "你的作品列表";
                    case "get_credits" -> "你的积分余额";
                    default -> "已接入的功能";
                };
                String reply = english(context) ? "The music assistant is temporarily limited. I can run this read-only lookup."
                        : "对话理解暂时不可用，我会查询" + target + "。";
                return new Plan(action, Map.of(), reply);
            }
        }
        return new Plan("chat", Map.of(), english(context)
                ? "The music assistant is temporarily unavailable. This step has not started any new business operation. Please try again later."
                : "对话理解暂时不可用，这一步没有发起新的业务操作。请稍后再试。");
    }

    private static boolean readCapability(Context context, String action) {
        var matching = context.capabilities().stream().filter(c -> action.equals(c.id())).toList();
        return matching.size() == 1 && "read".equals(matching.get(0).effect());
    }

    private static boolean english(Context context) {
        return context.locale() != null && context.locale().toLowerCase(Locale.ROOT).startsWith("en");
    }
}
