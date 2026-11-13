package com.qingfeng.music.testconfig;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Test-classpath-only H2 bridge. It never executes, records or certifies MySQL V45. */
@AutoConfiguration(before = FlywayAutoConfiguration.class)
@Profile("test")
public class H2InboxCompatibilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FlywayMigrationStrategy.class)
    FlywayMigrationStrategy h2InboxCompatibilityMigrationStrategy() {
        return flyway -> {
            var source = flyway.getConfiguration().getDataSource();
            final boolean h2;
            try (var connection = source.getConnection()) {
                h2 = "H2".equals(connection.getMetaData().getDatabaseProductName());
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException("Cannot identify the explicit test database", failure);
            }
            if (!h2) {
                flyway.migrate();
                return;
            }
            Flyway.configure().configuration(flyway.getConfiguration()).target("44").load().migrate();
            var db = new JdbcTemplate(source);
            Integer scoped = db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=CURRENT_SCHEMA AND table_name='payment_webhook_inbox' AND column_name IN ('merchant_scope','env_scope')", Integer.class);
            if (Integer.valueOf(0).equals(scoped)) {
                new ResourceDatabasePopulator(new ClassPathResource("h2-inbox-compatibility.sql")).execute(source);
            } else {
                Integer audits = db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=CURRENT_SCHEMA AND table_name IN ('payment_inbox_review','payment_inbox_attempt')", Integer.class);
                if (!Integer.valueOf(2).equals(scoped) || !Integer.valueOf(2).equals(audits))
                    throw new IllegalStateException("Partial H2 inbox fixture; preserve the test database rather than repairing it");
            }
        };
    }
}
