package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure configuration gates. No database, provider, migration, or Spring application is started. */
class CreditSchemaRolloutConfigurationTest {
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);

    private MockEnvironment environment(String... profiles) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JdbcTemplate database(String product) throws Exception {
        var db = mock(JdbcTemplate.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(db.execute(any(ConnectionCallback.class))).thenAnswer(call ->
            ((ConnectionCallback<?>) call.getArgument(0)).doInConnection(connection));
        when(db.queryForObject(CreditSchemaReadiness.INSTALLED_SQL, Integer.class)).thenReturn(0);
        when(db.queryForObject(CreditSchemaReadiness.INSTALLED_HISTORY_SQL, Integer.class)).thenReturn(0);
        return db;
    }

    private void allow(JdbcTemplate db, Map<String, CreditSchemaReadiness.Check> checks) {
        checks.values().forEach(check -> when(db.queryForObject(check.sql(), Integer.class)).thenReturn(check.expected()));
    }

    private UsageConfiguration configuration(boolean fenced) {
        var configuration = new UsageConfiguration();
        ReflectionTestUtils.setField(configuration, "fenced", fenced);
        ReflectionTestUtils.setField(configuration, "expectedDatabase", "isolated_config_contract");
        ReflectionTestUtils.setField(configuration, "expectedPort", 23316);
        return configuration;
    }

    private void matchedIdentity(JdbcTemplate db) {
        when(db.queryForMap("SELECT DATABASE() database_name,@@port database_port"))
            .thenReturn(Map.of("database_name", "isolated_config_contract", "database_port", 23316));
    }

    @Test void defaultTargetIs40And41RequiresExplicitOptInWithoutEnablingTheWriter() throws Exception {
        var environment = environment();
        for (var source : new YamlPropertySourceLoader().load("committed-defaults", new ClassPathResource("application.yml")))
            environment.getPropertySources().addLast(source);
        assertEquals("44", environment.getProperty("spring.flyway.target"));
        assertEquals("false", environment.getProperty("qingfeng.credits.writer-fence.enabled"));
        assertEquals("false", environment.getProperty("qingfeng.stripe.enabled"));
        environment.setProperty("CREDIT_SCHEMA_TARGET", "45");
        assertEquals("45", environment.getProperty("spring.flyway.target"));
        assertEquals("false", environment.getProperty("qingfeng.credits.writer-fence.enabled"));
        assertEquals("false", environment.getProperty("qingfeng.stripe.enabled"));
    }

    @Test void verifiedUninstalledMysqlCore40KeepsTheExistingWriter() throws Exception {
        var db = database("MySQL");
        allow(db, CreditSchemaReadiness.CORE44_CHECKS);
        assertInstanceOf(JdbcCreditsLedger.class, configuration(false).creditsLedger(db, manager, environment("mysql")));
        for (var check : CreditSchemaReadiness.CORE44_CHECKS.values())
            verify(db).queryForObject(check.sql(), Integer.class);
        verify(db, never()).queryForObject(CreditSchemaReadiness.CHECKS.get("v45_exact_history").sql(), Integer.class);
        verifyNoInteractions(manager);
    }

    @Test void MysqlUnderTestProfileStillRequiresTheActualCore40Gate() throws Exception {
        var db = database("MySQL");
        allow(db, CreditSchemaReadiness.CORE44_CHECKS);
        var missing = CreditSchemaReadiness.CORE44_CHECKS.get("core_account_primary_key");
        when(db.queryForObject(missing.sql(), Integer.class)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> configuration(false).creditsLedger(db, manager, environment("test")));
    }

    @Test void explicitH2TestCompatibilityDoesNotClaimMysqlReadiness() throws Exception {
        var db = database("H2");
        assertInstanceOf(JdbcCreditsLedger.class, configuration(false).creditsLedger(db, manager, environment("test")));
        for (var check : CreditSchemaReadiness.CORE44_CHECKS.values())
            verify(db, never()).queryForObject(check.sql(), Integer.class);
        assertThrows(IllegalStateException.class, () -> configuration(false).creditsLedger(db, manager, environment("mysql")));
    }

    @Test void anyInstalledOrPartialP0ObjectBlocksTheOldWriterRegardlessOfProfile() throws Exception {
        for (String profile : new String[]{"mysql", "test"}) {
            var db = database("MySQL");
            when(db.queryForObject(CreditSchemaReadiness.INSTALLED_SQL, Integer.class)).thenReturn(1);
            assertThrows(IllegalStateException.class, () -> configuration(false).creditsLedger(db, manager, environment(profile)));
        }
    }

    @Test void pendingOrFailed41HistoryCannotFallBackToLegacyEvenWithoutTables() throws Exception {
        var db = database("MySQL");
        when(db.queryForObject(CreditSchemaReadiness.INSTALLED_HISTORY_SQL, Integer.class)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> configuration(false).creditsLedger(db, manager, environment("mysql")));
    }

    @Test void unknownInstallationStatusNeverCreatesALegacyFallback() throws Exception {
        var db = database("MySQL");
        when(db.queryForObject(CreditSchemaReadiness.INSTALLED_SQL, Integer.class)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> configuration(false).creditsLedger(db, manager, environment("mysql")));
    }

    @Test void everyCore40HistoryStructureAndPartialInboxCheckIsRequired() throws Exception {
        for (var entry : CreditSchemaReadiness.CORE44_CHECKS.entrySet()) {
            var db = database("MySQL");
            allow(db, CreditSchemaReadiness.CORE44_CHECKS);
            when(db.queryForObject(entry.getValue().sql(), Integer.class)).thenReturn(entry.getValue().expected() + 1);
            var failure = assertThrows(IllegalStateException.class,
                () -> configuration(false).creditsLedger(db, manager, environment("mysql")), entry.getKey());
            assertTrue(failure.getMessage().contains(entry.getKey()), entry.getKey());
        }
    }

    @Test void writerOptInWithPreparingStateStillRefusesStartup() throws Exception {
        var db = database("MySQL");
        matchedIdentity(db);
        allow(db, CreditSchemaReadiness.CHECKS);
        var preparing = CreditSchemaReadiness.CHECKS.get("protocol3_active");
        when(db.queryForObject(preparing.sql(), Integer.class)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> configuration(true).creditsLedger(db, manager, environment("mysql")));
    }

    @Test void writerOptInNeverBypassesTheExactDatabaseAndPort() throws Exception {
        for (var identity : new Map[]{Map.of("database_name", "another_database", "database_port", 23316),
                Map.of("database_name", "isolated_config_contract", "database_port", 3306)}) {
            var db = database("MySQL");
            when(db.queryForMap("SELECT DATABASE() database_name,@@port database_port")).thenReturn(identity);
            assertThrows(IllegalStateException.class, () -> configuration(true).creditsLedger(db, manager, environment("mysql")));
            verify(db, never()).queryForObject(CreditSchemaReadiness.CHECKS.get("v45_exact_history").sql(), Integer.class);
        }
    }

    @Test void writerOptInRetainsEveryExisting41SchemaCheck() throws Exception {
        for (var entry : CreditSchemaReadiness.CHECKS.entrySet()) {
            var db = database("MySQL");
            matchedIdentity(db);
            allow(db, CreditSchemaReadiness.CHECKS);
            when(db.queryForObject(entry.getValue().sql(), Integer.class)).thenReturn(entry.getValue().expected() + 1);
            var failure = assertThrows(IllegalStateException.class,
                () -> configuration(true).creditsLedger(db, manager, environment("mysql")), entry.getKey());
            assertTrue(failure.getMessage().contains(entry.getKey()), entry.getKey());
        }
    }

    @Test void matchingDatabaseAndCompleteExplicitlyActive41SelectsTheFencedWriter() throws Exception {
        var db = database("MySQL");
        matchedIdentity(db);
        allow(db, CreditSchemaReadiness.CHECKS);
        assertInstanceOf(JdbcFencedCreditsLedger.class, configuration(true).creditsLedger(db, manager, environment("mysql")));
        verifyNoInteractions(manager);
    }
}
