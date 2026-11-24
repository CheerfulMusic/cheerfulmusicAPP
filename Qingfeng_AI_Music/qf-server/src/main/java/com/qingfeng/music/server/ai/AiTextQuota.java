package com.qingfeng.music.server.ai;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.infrastructure.provider.AiModelUsage;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongConsumer;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class AiTextQuota implements AiModelUsage.Gate {
    private static final System.Logger LOG = System.getLogger(AiTextQuota.class.getName());
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Environment environment;

    public AiTextQuota(JdbcTemplate db, TransactionTemplate transactions, Environment environment) {
        this.db = db;
        this.tx = new TransactionTemplate(transactions.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(4);
        this.environment = environment;
    }

    @Override public LongConsumer reserve(String provider, URI endpoint, long estimatedTokens) {
        String host = Objects.requireNonNull(endpoint.getHost()).toLowerCase(Locale.ROOT).replace('.', '-');
        provider = environment.getProperty("qingfeng.ai.capacity.text.endpoint-groups." + host, provider);
        String prefix = "qingfeng.ai.capacity.text." + provider + ".";
        String scope = environment.getProperty(prefix + "account", provider);
        if (!scope.matches("[a-zA-Z0-9_-]{1,70}")) throw new IllegalArgumentException("Invalid AI quota account");
        long requestsPerMinute = environment.getProperty(prefix + "rpm", Long.class, 60L);
        long tokensPerMinute = environment.getProperty(prefix + "tpm", Long.class, 1_000_000L);
        long tokensPerDay = environment.getProperty(prefix + "daily-tokens", Long.class, 100_000_000L);
        if (estimatedTokens < 1 || requestsPerMinute < 1 || tokensPerMinute < estimatedTokens || tokensPerDay < estimatedTokens)
            throw limited();

        Instant now = Objects.requireNonNull(db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class)).toInstant();
        Timestamp day = Timestamp.from(now.truncatedTo(ChronoUnit.DAYS));
        Timestamp minute = Timestamp.from(now.truncatedTo(ChronoUnit.MINUTES));
        tx.executeWithoutResult(status -> {
            reserveWindow(scope, "DAY", day, estimatedTokens, tokensPerDay, Long.MAX_VALUE);
            reserveWindow(scope, "MINUTE", minute, estimatedTokens, tokensPerMinute, requestsPerMinute);
        });
        return actual -> {
            try {
                long delta = Math.subtractExact(actual, estimatedTokens);
                tx.executeWithoutResult(status -> {
                    adjust(scope, "DAY", day, delta);
                    adjust(scope, "MINUTE", minute, delta);
                });
            } catch (RuntimeException error) {
                // Keep the pessimistic reservation when usage cannot be safely settled.
                LOG.log(System.Logger.Level.WARNING, "AI quota settlement unavailable: {0}", error.getClass().getSimpleName());
            }
        };
    }

    private void reserveWindow(String scope, String kind, Timestamp window, long estimate, long tokens, long requests) {
        db.update("""
            INSERT INTO ai_capacity_model_usage(account_scope,period_kind,period_start,requests,tokens)
            VALUES(?,?,?,0,0) ON DUPLICATE KEY UPDATE account_scope=account_scope
            """, scope, kind, window);
        if (db.update("""
            UPDATE ai_capacity_model_usage SET requests=requests+1,tokens=tokens+?
            WHERE account_scope=? AND period_kind=? AND period_start=? AND requests<? AND tokens<=?
            """, estimate, scope, kind, window, requests, tokens - estimate) != 1) throw limited();
    }

    private void adjust(String scope, String kind, Timestamp window, long delta) {
        db.update("""
            UPDATE ai_capacity_model_usage SET tokens=GREATEST(0,tokens+?)
            WHERE account_scope=? AND period_kind=? AND period_start=?
            """, delta, scope, kind, window);
    }

    private static DomainException limited() {
        return new DomainException("AI_PROVIDER_RATE_LIMITED", "模型服务当前额度已满，请稍后重试。");
    }
}
