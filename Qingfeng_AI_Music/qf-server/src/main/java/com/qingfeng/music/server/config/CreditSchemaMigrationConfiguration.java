package com.qingfeng.music.server.config;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Runtime startup cannot perform the separately approved financial cutover. */
@Configuration(proxyBeanMethods = false)
@Profile("mysql")
public class CreditSchemaMigrationConfiguration {
    private static final MigrationVersion CORE40 = MigrationVersion.fromVersion("40");
    private static final String CUTOVER_REQUIRED = "Financial V41 requires separately approved migration, historical mapping and activation before application startup";

    @Bean
    FlywayMigrationStrategy creditSchemaMigrationStrategy(Environment environment) {
        return flyway -> {
            var target = flyway.getConfiguration().getTarget();
            // Null/latest/current/next are not a fixed core-only target.
            if (target != null && !target.isPredefined() && target.compareTo(CORE40) <= 0) {
                flyway.migrate();
                return;
            }
            if (!environment.getProperty("qingfeng.credits.writer-fence.enabled", Boolean.class, false))
                throw new IllegalStateException("A legacy credit writer cannot automatically apply financial V41. " + CUTOVER_REQUIRED);
            String expectedDatabase = environment.getProperty("qingfeng.credits.writer-fence.expected-database", "");
            int expectedPort = environment.getProperty("qingfeng.credits.writer-fence.expected-port", Integer.class, 0);
            if (expectedDatabase.isBlank() || expectedPort < 1)
                throw new IllegalStateException("Explicit financial database identity is required. " + CUTOVER_REQUIRED);
            var source = flyway.getConfiguration().getDataSource();
            if (source == null) throw new IllegalStateException("Financial datasource identity is unknown. " + CUTOVER_REQUIRED);
            var db = new JdbcTemplate(source);
            try {
                var identity = db.queryForMap("SELECT DATABASE() database_name,@@port database_port");
                if (!expectedDatabase.equals(identity.get("database_name"))
                        || !(identity.get("database_port") instanceof Number port) || port.intValue() != expectedPort)
                    throw new IllegalStateException("Financial database identity does not match");
                // Never install, repair, map, or activate V41 here. Only an already
                // exact ACTIVE installation may continue through normal validation.
                CreditSchemaReadiness.requireActive(db);
            } catch (RuntimeException incomplete) {
                throw new IllegalStateException(CUTOVER_REQUIRED, incomplete);
            }
            flyway.migrate();
        };
    }
}
