package com.qingfeng.music.server.config;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mocked pre-migration checks; never connects to a database or invokes a real Flyway. */
class CreditSchemaMigrationStrategyTest {
    private record Fixture(Flyway flyway, org.flywaydb.core.api.configuration.Configuration settings, DataSource source) {}

    private Fixture fixture(MigrationVersion target) {
        var flyway = mock(Flyway.class);
        var settings = mock(org.flywaydb.core.api.configuration.Configuration.class);
        var source = mock(DataSource.class);
        when(flyway.getConfiguration()).thenReturn(settings);
        when(settings.getTarget()).thenReturn(target);
        when(settings.getDataSource()).thenReturn(source);
        return new Fixture(flyway, settings, source);
    }

    private MockEnvironment approvedRuntime() {
        return new MockEnvironment()
            .withProperty("qingfeng.credits.writer-fence.enabled", "true")
            .withProperty("qingfeng.credits.writer-fence.expected-database", "isolated_strategy_contract")
            .withProperty("qingfeng.credits.writer-fence.expected-port", "23316");
    }

    private MockedConstruction<JdbcTemplate> database(Fixture fixture, Consumer<JdbcTemplate> adjust) {
        return mockConstruction(JdbcTemplate.class, (db, context) -> {
            assertEquals(1, context.arguments().size());
            assertSame(fixture.source(), context.arguments().get(0));
            when(db.queryForMap("SELECT DATABASE() database_name,@@port database_port"))
                .thenReturn(Map.of("database_name", "isolated_strategy_contract", "database_port", 23316));
            CreditSchemaReadiness.CHECKS.values().forEach(check ->
                when(db.queryForObject(check.sql(), Integer.class)).thenReturn(check.expected()));
            adjust.accept(db);
        });
    }

    @Test void strategyAppliesOnlyToTheMysqlProfile() {
        assertArrayEquals(new String[]{"mysql"}, CreditSchemaMigrationConfiguration.class.getAnnotation(Profile.class).value());
    }

    @Test void fixedCoreOnlyTargetsKeepNormalMigrationWithoutConstructingFinancialQueries() {
        for (String target : List.of("0", "1", "34", "40", "40.0")) {
            var fixture = fixture(MigrationVersion.fromVersion(target));
            try (var queries = database(fixture, db -> {})) {
                new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(new MockEnvironment()).migrate(fixture.flyway());
                verify(fixture.flyway()).migrate();
                verify(fixture.settings(), never()).getDataSource();
                assertTrue(queries.constructed().isEmpty());
            }
        }
    }

    @Test void financialLatestAndUnresolvedTargetsCannotMigrateWithALegacyWriter() {
        for (MigrationVersion target : new MigrationVersion[]{MigrationVersion.fromVersion("41"),
                MigrationVersion.LATEST, MigrationVersion.CURRENT, MigrationVersion.NEXT, null}) {
            var fixture = fixture(target);
            try (var queries = database(fixture, db -> {})) {
                var failure = assertThrows(IllegalStateException.class, () ->
                    new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(new MockEnvironment()).migrate(fixture.flyway()));
                assertTrue(failure.getMessage().contains("legacy credit writer"));
                assertTrue(failure.getMessage().contains("separately approved"));
                verify(fixture.flyway(), never()).migrate();
                verify(fixture.settings(), never()).getDataSource();
                assertTrue(queries.constructed().isEmpty());
            }
        }
    }

    @Test void enablingTheWriterWithoutAnExplicitDatabaseIdentityCannotMigrate() {
        var fixture = fixture(MigrationVersion.LATEST);
        var environment = new MockEnvironment().withProperty("qingfeng.credits.writer-fence.enabled", "true");
        assertThrows(IllegalStateException.class, () ->
            new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(environment).migrate(fixture.flyway()));
        verify(fixture.flyway(), never()).migrate();
        verify(fixture.settings(), never()).getDataSource();
    }

    @Test void theFlywayDatasourceMustMatchBothTheApprovedDatabaseAndPort() {
        for (var identity : List.<Map<String, Object>>of(
                Map.of("database_name", "wrong_database", "database_port", 23316),
                Map.of("database_name", "isolated_strategy_contract", "database_port", 3306),
                Map.of("database_name", "isolated_strategy_contract", "database_port", "23316"))) {
            var fixture = fixture(MigrationVersion.fromVersion("41"));
            try (var queries = database(fixture, db -> when(db.queryForMap("SELECT DATABASE() database_name,@@port database_port")).thenReturn(identity))) {
                assertThrows(IllegalStateException.class, () ->
                    new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway()));
                verify(fixture.flyway(), never()).migrate();
                verify(queries.constructed().get(0), never()).queryForObject(CreditSchemaReadiness.CHECKS.get("v41_exact_history").sql(), Integer.class);
            }
        }
    }

    @Test void anUninstalledFinancialSchemaCannotBeInstalledByStartup() {
        var fixture = fixture(MigrationVersion.fromVersion("41"));
        try (var queries = database(fixture, db -> when(db.queryForObject(CreditSchemaReadiness.CHECKS.get("v41_only_once").sql(), Integer.class)).thenReturn(0))) {
            assertThrows(IllegalStateException.class, () ->
                new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway()));
            verify(fixture.flyway(), never()).migrate();
        }
    }

    @Test void preparingCannotBeActivatedOrMigratedByStartup() {
        var fixture = fixture(MigrationVersion.LATEST);
        try (var queries = database(fixture, db -> when(db.queryForObject(CreditSchemaReadiness.CHECKS.get("protocol3_active").sql(), Integer.class)).thenReturn(0))) {
            assertThrows(IllegalStateException.class, () ->
                new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway()));
            verify(fixture.flyway(), never()).migrate();
        }
    }

    @Test void everyExactActive41PrerequisiteIsCheckedBeforeAnyMigration() {
        for (var entry : CreditSchemaReadiness.CHECKS.entrySet()) {
            var fixture = fixture(MigrationVersion.fromVersion("41"));
            try (var queries = database(fixture, db -> when(db.queryForObject(entry.getValue().sql(), Integer.class)).thenReturn(entry.getValue().expected() + 1))) {
                var failure = assertThrows(IllegalStateException.class, () ->
                    new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway()), entry.getKey());
                assertTrue(failure.getMessage().contains("historical mapping"));
                verify(fixture.flyway(), never()).migrate();
            }
        }
    }

    @Test void unreadableMetadataIsNeverTreatedAsApproval() {
        var fixture = fixture(MigrationVersion.LATEST);
        try (var queries = database(fixture, db -> when(db.queryForMap("SELECT DATABASE() database_name,@@port database_port"))
                .thenThrow(new DataAccessResourceFailureException("synthetic unavailable database")))) {
            assertThrows(IllegalStateException.class, () ->
                new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway()));
            verify(fixture.flyway(), never()).migrate();
        }
    }

    @Test void anAlreadyExactActive41MayContinueNormalFlywayValidationAndMigration() {
        for (MigrationVersion target : new MigrationVersion[]{MigrationVersion.fromVersion("41"), MigrationVersion.LATEST}) {
            var fixture = fixture(target);
            try (var queries = database(fixture, db -> {})) {
                new CreditSchemaMigrationConfiguration().creditSchemaMigrationStrategy(approvedRuntime()).migrate(fixture.flyway());
                for (var check : CreditSchemaReadiness.CHECKS.values())
                    verify(queries.constructed().get(0)).queryForObject(check.sql(), Integer.class);
                verify(fixture.flyway()).migrate();
            }
        }
    }
}
