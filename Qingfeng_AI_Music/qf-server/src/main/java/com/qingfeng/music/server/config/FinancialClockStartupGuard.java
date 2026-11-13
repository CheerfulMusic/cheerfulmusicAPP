package com.qingfeng.music.server.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Checks the actual financial application's connection; a URL label is insufficient. */
@Component
@Profile("mysql")
@ConditionalOnProperty(name="qingfeng.credits.writer-fence.enabled",havingValue="true")
public final class FinancialClockStartupGuard implements ApplicationRunner {
    private final JdbcTemplate database;
    public FinancialClockStartupGuard(JdbcTemplate database) { this.database=database; }
    @Override public void run(ApplicationArguments arguments) {
        database.execute((ConnectionCallback<Void>) connection -> {
            if(!"MySQL".equals(connection.getMetaData().getDatabaseProductName()))
                throw new IllegalStateException("FINANCIAL_CLOCK_REQUIRES_MYSQL");
            try(var statement=connection.createStatement();var row=statement.executeQuery(
                    "SELECT @@session.time_zone,CURRENT_TIMESTAMP(6),UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))")) {
                if(!row.next())throw new IllegalStateException("FINANCIAL_CLOCK_UNAVAILABLE");
                verify(row.getString(1),row.getTimestamp(2).toInstant(),row.getBigDecimal(3),Instant.now());
            }
            return null;
        });
    }
    static void verify(String zone,Instant jdbcInstant,BigDecimal serverEpoch,Instant applicationInstant) {
        if(!Set.of("+00:00","UTC").contains(zone)||jdbcInstant==null||serverEpoch==null||applicationInstant==null)
            throw new IllegalStateException("FINANCIAL_SESSION_MUST_USE_EXPLICIT_UTC");
        long seconds=serverEpoch.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
        if(Math.abs(Math.subtractExact(jdbcInstant.getEpochSecond(),seconds))>1
                ||Math.abs(Math.subtractExact(applicationInstant.getEpochSecond(),seconds))>15)
            throw new IllegalStateException("FINANCIAL_JDBC_OR_APPLICATION_CLOCK_MISMATCH");
    }
}
