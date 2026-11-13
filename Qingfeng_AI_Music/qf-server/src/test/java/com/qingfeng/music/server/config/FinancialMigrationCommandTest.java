package com.qingfeng.music.server.config;

import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline rejection tests only; no database properties, secrets, or provider calls. */
class FinancialMigrationCommandTest {
    private JdbcTemplate triggerDatabase(Boolean binary,Boolean trusted) {
        var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT @@log_bin",Boolean.class)).thenReturn(binary);
        when(db.queryForObject("SELECT @@log_bin_trust_function_creators",Boolean.class)).thenReturn(trusted);return db;
    }
    @Test void triggerDdlWithNoSuperRequirementOnlyReadsActualServerFlags() {
        for(Boolean[] flags:new Boolean[][]{{false,false},{false,true},{true,true}}){
            var db=triggerDatabase(flags[0],flags[1]);var capability=FinancialMigrationCommand.verifyTriggerDdlCapability(db);
            assertEquals(flags[0],capability.binaryLoggingEnabled());assertEquals(flags[1],capability.trustFunctionCreators());
            assertFalse(capability.globalSuperRequired());assertFalse(capability.globalSuperVerified());
            verify(db).queryForObject("SELECT @@log_bin",Boolean.class);verify(db).queryForObject("SELECT @@log_bin_trust_function_creators",Boolean.class);
            verifyNoMoreInteractions(db);
        }
    }
    @Test void unknownFlagsOrSchemaOnlyAndLookalikePrivilegesDoNotAuthorizeTriggerDdl() {
        for(Boolean[] flags:new Boolean[][]{{null,false},{true,null},{null,null}}){
            var db=triggerDatabase(flags[0],flags[1]);
            assertEquals("FINANCIAL_MIGRATION_TRIGGER_CAPABILITY_UNKNOWN",assertThrows(IllegalStateException.class,
                ()->FinancialMigrationCommand.verifyTriggerDdlCapability(db)).getMessage());
        }
        for(String grant:List.of("GRANT ALL PRIVILEGES ON `isolated`.* TO `isolated`@`127.0.0.1`",
                "GRANT SUPER ON `isolated`.* TO `isolated`@`127.0.0.1`", "GRANT SUPER_READ_ONLY ON *.* TO `isolated`@`127.0.0.1`",
                "GRANT SYSTEM_USER, BINLOG_ADMIN ON *.* TO `isolated`@`127.0.0.1`", "GRANT USAGE ON *.* TO `isolated`@`127.0.0.1`")){
            var db=triggerDatabase(true,false);when(db.queryForList("SHOW GRANTS FOR CURRENT_USER()",String.class)).thenReturn(List.of(grant));
            assertEquals("FINANCIAL_MIGRATION_TRIGGER_SUPER_REQUIRED",assertThrows(IllegalStateException.class,
                ()->FinancialMigrationCommand.verifyTriggerDdlCapability(db)).getMessage(),grant);
        }
    }
    @Test void actualGlobalSuperOrGlobalAllPrivilegesPassTheTriggerPreflight() {
        for(String grant:List.of("GRANT SELECT, SUPER ON *.* TO `root`@`localhost`",
                "GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost`", "grant super on *.* to 'root'@'localhost'")){
            var db=triggerDatabase(true,false);when(db.queryForList("SHOW GRANTS FOR CURRENT_USER()",String.class)).thenReturn(List.of(grant));
            var capability=FinancialMigrationCommand.verifyTriggerDdlCapability(db);
            assertTrue(capability.binaryLoggingEnabled());assertFalse(capability.trustFunctionCreators());
            assertTrue(capability.globalSuperRequired());assertTrue(capability.globalSuperVerified());
            verify(db).queryForObject("SELECT @@log_bin",Boolean.class);verify(db).queryForObject("SELECT @@log_bin_trust_function_creators",Boolean.class);
            verify(db).queryForList("SHOW GRANTS FOR CURRENT_USER()",String.class);verifyNoMoreInteractions(db);
        }
    }
    @Test void actualFlywayCanConstructTheSupportedLoggerAndThenUseItsNormalLogger() throws Exception {
        Path scripts=Files.createDirectories(Path.of("target","financial-logger-test-"+UUID.randomUUID()).toAbsolutePath());
        Files.writeString(scripts.resolve("V1__logger_fixture.sql"),"CREATE TABLE logger_fixture(id INT PRIMARY KEY);",StandardOpenOption.CREATE_NEW);
        String url="jdbc:h2:mem:financial_logger_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var custom=Flyway.configure().dataSource(url,"sa","").locations("filesystem:"+scripts)
            .cleanDisabled(true).loggers(FinancialMigrationCommand.JsonOnlyLogCreator.class.getName()).load();
        assertEquals(1,custom.migrate().migrationsExecuted);custom.validate();
        var normal=Flyway.configure().dataSource(url,"sa","").locations("filesystem:"+scripts)
            .cleanDisabled(true).loggers("auto").load();
        assertEquals(0,normal.migrate().migrationsExecuted);normal.validate();
    }
    private Properties contract() {
        var properties=new Properties();properties.setProperty("migration.version","45");
        properties.setProperty("migration.sha256",FinancialMigrationCommand.FINANCIAL_SHA256);
        properties.setProperty("core.manifest.sha256",FinancialMigrationCommand.CORE_MANIFEST_SHA256);
        properties.setProperty("approval.evidence","SYNTHETIC_OFFLINE_NEGATIVE_TEST");
        properties.setProperty("source.sha256","a".repeat(64));return properties;
    }
    @Test void invalidModeAndMissingApprovalCannotReachAConnection() {
        assertEquals("INVALID_FINANCIAL_MIGRATION_MODE",assertThrows(IllegalStateException.class,
            ()->FinancialMigrationCommand.execute("repair",new Properties(),new Properties())).getMessage());
        assertEquals("FINANCIAL_MIGRATION_PROPERTY_REQUIRED",assertThrows(IllegalStateException.class,
            ()->FinancialMigrationCommand.execute("validate",new Properties(),new Properties())).getMessage());
        for(String key:new String[]{"migration.version","migration.sha256","core.manifest.sha256","approval.evidence","source.sha256"}){
            var approval=contract();approval.remove(key);
            assertEquals("FINANCIAL_MIGRATION_PROPERTY_REQUIRED",assertThrows(IllegalStateException.class,
                ()->FinancialMigrationCommand.execute("validate",new Properties(),approval)).getMessage(),key);
        }
    }
    @Test void oldFinancial44AndWrongManifestFailBeforeReadingAnyDatabaseSettings() {
        for(String key:new String[]{"migration.version","migration.sha256","core.manifest.sha256"}){
            var approval=contract();approval.setProperty(key,key.equals("migration.version")?"44":"0".repeat(64));
            assertEquals("FINANCIAL_MIGRATION_CONTRACT_MISMATCH",assertThrows(IllegalStateException.class,
                ()->FinancialMigrationCommand.execute("migrate",new Properties(),approval)).getMessage(),key);
        }
    }
    @Test void changedOrMissingCandidateArtifactCannotBecomeAMigrationApproval() throws Exception {
        Path folder=Files.createDirectories(Path.of("target","financial-command-negative-"+UUID.randomUUID()).toAbsolutePath());
        var approval=contract();approval.setProperty("candidate.jar.path",folder.resolve("missing.jar").toString());
        approval.setProperty("candidate.jar.sha256","b".repeat(64));
        assertEquals("FINANCIAL_MIGRATION_CANDIDATE_JAR_MISMATCH",assertThrows(IllegalStateException.class,
            ()->FinancialMigrationCommand.execute("migrate",new Properties(),approval)).getMessage());
        Path fake=folder.resolve("changed.jar");Files.write(fake,new byte[]{1,2,3},StandardOpenOption.CREATE_NEW);
        approval.setProperty("candidate.jar.path",fake.toString());
        assertEquals("FINANCIAL_MIGRATION_CANDIDATE_JAR_MISMATCH",assertThrows(IllegalStateException.class,
            ()->FinancialMigrationCommand.execute("migrate",new Properties(),approval)).getMessage());
    }
}
