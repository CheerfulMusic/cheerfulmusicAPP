package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.server.config.CreditSchemaReadiness;
import com.qingfeng.music.server.config.FinancialMigrationCommand;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Historical CI selector retained: actual canonical V1..V44 -> V45 on fresh isolated schemas.
 * No application/provider client. The original isolated payment40 history is separately rejected. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditEntitlementV40MySqlMigrationTest {
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static JsonNode reference;
    private static String owner, order;
    private static final String NATIVE = "txn_migration_identity";
    private static final String MERCHANT = "acct_migration_offline";
    private static final String CAPTURE = "pi_migration_offline";

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Explicit isolated migration environment required: " + key);
        return value;
    }
    private static long number(String sql, Object... args) { return Objects.requireNonNull(db.queryForObject(sql, Long.class, args)); }

    @BeforeAll static void migrateActualCanonicalChain() throws Exception {
        String database = required("P0_INTEGRATION_MIGRATION_MYSQL_DATABASE");
        String url = required("P0_INTEGRATION_MIGRATION_MYSQL_URL");
        if (!database.matches("p0_integration_migration_20261112r[0-9]+")
                || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/" + database + "(?:\\?.*)?")
                || url.toLowerCase(Locale.ROOT).contains("password="))
            throw new IllegalStateException("Only the explicitly isolated migration cohort is permitted");
        var source = new DriverManagerDataSource(url, required("P0_INTEGRATION_MIGRATION_MYSQL_USER"), required("P0_INTEGRATION_MIGRATION_MYSQL_PASSWORD"));
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new JdbcTransactionManager(source));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var identity = statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE()")) {
                assertTrue(identity.next()); assertEquals("8.0.46", identity.getString(1));
                assertEquals("05c27d3c-dda3-11f1-aba0-84a938421519", identity.getString(2));
                assertEquals(required("P0_INTEGRATION_MIGRATION_MYSQL_UUID"), identity.getString(2));
                assertEquals("r:/data/", identity.getString(3).replace('\\', '/').toLowerCase(Locale.ROOT));
                assertEquals(23316, identity.getInt(4)); assertEquals("127.0.0.1", identity.getString(5));
                assertEquals(database, identity.getString(6)); assertEquals("NONE", identity.getString(7));
            }
            int grants = 0;
            try (var rows = statement.executeQuery("SHOW GRANTS")) { while (rows.next()) {
                String grant = rows.getString(1);
                assertTrue(grant.startsWith("GRANT USAGE ON *.*") || grant.contains("ON `" + database + "`.*"));
                assertFalse(grant.contains("WITH GRANT OPTION")); grants++;
            }}
            assertEquals(2, grants);
            try (var rows = statement.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA")) { while (rows.next())
                assertTrue(Set.of(database, "information_schema", "performance_schema").contains(rows.getString(1)));
            }
        }
        assertEquals(0, number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"), "Never clear or reuse a prior cohort");
        try (var resource = Objects.requireNonNull(CreditEntitlementV40MySqlMigrationTest.class.getResourceAsStream("/credit-migration-reference.json"))) {
            reference = new ObjectMapper().readTree(resource);
        }
        assertEquals("e6c351f21ece6fb096ff425ca4b690232632de21", reference.path("core_commit").asString());
        assertEquals(44, reference.path("core").size());
        assertEquals("45", reference.path("integration").path("version").asString());
        assertEquals("V45__credit_entitlement_integration.sql", reference.path("integration").path("script").asString());
        assertEquals(-2125061473, reference.path("integration").path("checksum").asInt());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("44").load().migrate();
        assertEquals(44, number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        for (var migration : reference.path("core")) assertHistory(migration);
        assertFalse(CreditSchemaReadiness.installed(db), "The executed Discover V44 is not a financial installation");
        CreditSchemaReadiness.requireCore44Ready(db);
        var discoverHistory=db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        // Real MySQL metadata drift must stop admission before a financial DDL
        // attempt; restore only our synthetic schema, never edit Flyway history.
        db.execute("ALTER TABLE artist_song_release MODIFY discover_published_at TIMESTAMP(0) NULL");
        try {
            var rejected=assertThrows(IllegalStateException.class, () -> CreditSchemaReadiness.requireCore44Ready(db));
            assertTrue(rejected.getMessage().contains("core44_discover_structure"));
            assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='p0_'"));
        } finally {db.execute("ALTER TABLE artist_song_release MODIFY discover_published_at TIMESTAMP(6) NULL");}
        db.execute("ALTER TABLE artist_song_release DROP INDEX idx_artist_release_discover_latest, ADD INDEX idx_artist_release_discover_latest (id,discover_published_at)");
        try {
            var rejected=assertThrows(IllegalStateException.class, () -> CreditSchemaReadiness.requireCore44Ready(db));
            assertTrue(rejected.getMessage().contains("core44_discover_structure"));
            assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='p0_'"));
        } finally {db.execute("ALTER TABLE artist_song_release DROP INDEX idx_artist_release_discover_latest, ADD INDEX idx_artist_release_discover_latest (discover_published_at,id)");}
        CreditSchemaReadiness.requireCore44Ready(db);
        assertEquals(discoverHistory,db.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        owner = UUID.randomUUID().toString(); order = UUID.randomUUID().toString();
        db.update("INSERT INTO creator(id,created_at) VALUES(?,CURRENT_TIMESTAMP)", owner);
        db.update("INSERT INTO credit_ledger(entry_type,reference_id,owner_id,amount,created_at) VALUES('CREDIT','migration-original-credit',?,100,CURRENT_TIMESTAMP)", owner);
        db.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,100,CURRENT_TIMESTAMP)", owner);
        String work = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString(), grant = UUID.randomUUID().toString();
        db.update("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at) VALUES(?,?,'','OTHER','','','','migration download',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", work, owner);
        db.update("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at) VALUES(?,?,'local:migration','IMPORTED','audio/mpeg',1,?,CURRENT_TIMESTAMP)", asset, work, "0".repeat(64));
        db.update("INSERT INTO shared_download_entitlement(id,owner_id,work_id,asset_id,title) VALUES(?,?,?,?,'original synthetic download')", grant, owner, work, asset);
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,status,created_at,provider,provider_capture_id) VALUES(?,?,'starter-USD',?,'USD',100,'PAID',CURRENT_TIMESTAMP,'STRIPE_SANDBOX',?)", order, owner, new BigDecimal("9.90"), CAPTURE);
        db.update("INSERT INTO payment_webhook_inbox(provider,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at) VALUES('STRIPE','evt_migration_legacy','refund.updated','{}',?,'PENDING',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "0".repeat(64));
        var original = db.queryForMap("SELECT entry_type,reference_id,owner_id,amount,created_at FROM credit_ledger WHERE reference_id='migration-original-credit'");
        var originalInbox = db.queryForMap("SELECT provider,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at FROM payment_webhook_inbox WHERE event_id='evt_migration_legacy'");
        var originalDownload = db.queryForMap("SELECT * FROM shared_download_entitlement WHERE id=?", grant);
        verifyExplicitFinancialMigrationCommand(source);
        assertEquals(original, db.queryForMap("SELECT entry_type,reference_id,owner_id,amount,created_at FROM credit_ledger WHERE reference_id='migration-original-credit'"));
        assertEquals(originalInbox, db.queryForMap("SELECT provider,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at FROM payment_webhook_inbox WHERE event_id='evt_migration_legacy'"));
        assertEquals(originalDownload, db.queryForMap("SELECT * FROM shared_download_entitlement WHERE id=?", grant), "V45 must retain every original core42 download grant field");
        assertEquals(1, number("SELECT COUNT(*) FROM shared_download_entitlement"));
        assertEquals(100, number("SELECT balance FROM credit_account WHERE owner_id=?", owner));
        assertEquals(0, number("SELECT COUNT(*) FROM p0_credit_batch"));
        assertEquals(0, number("SELECT COUNT(*) FROM p0_credit_native_identity"));
    }

    /** Uses the actual command and InnoDB, with a clearly synthetic approval bundle.
     * This verifies command contracts, not a production backup or human approval. */
    private static void verifyExplicitFinancialMigrationCommand(DriverManagerDataSource source) throws Exception {
        var inventory=new CreditHistoricalCutover(db,tx).inventory();
        Path proofs=Files.createDirectories(Path.of("target","financial-command-test-"+UUID.randomUUID()).toAbsolutePath());
        Path candidate=proofs.resolve("test-only-candidate.jar");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(candidate,StandardOpenOption.CREATE_NEW))){
            var infrastructure=new java.io.ByteArrayOutputStream();
            try(var nested=new java.util.zip.ZipOutputStream(infrastructure)){
                for(var section:List.of(reference.path("core"),reference.path("integration"))){
                    var migrations=new ArrayList<JsonNode>();
                    if(section.isArray())section.forEach(migrations::add);else migrations.add(section);
                    for(var migration:migrations){
                        String script=migration.path("script").asString();
                        try(var resource=Objects.requireNonNull(CreditEntitlementV40MySqlMigrationTest.class.getResourceAsStream("/db/migration/"+script))){
                            nested.putNextEntry(new java.util.zip.ZipEntry("db/migration/"+script));nested.write(resource.readAllBytes());nested.closeEntry();
                        }
                    }
                }
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("BOOT-INF/lib/qf-infrastructure-test-only.jar"));zip.write(infrastructure.toByteArray());zip.closeEntry();
            String type="com/qingfeng/music/server/config/FinancialMigrationCommand.class";
            try(var resource=Objects.requireNonNull(FinancialMigrationCommand.class.getResourceAsStream("/"+type))){
                zip.putNextEntry(new java.util.zip.ZipEntry("BOOT-INF/classes/"+type));zip.write(resource.readAllBytes());zip.closeEntry();
            }
        }
        String candidateSha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(candidate)));
        var connection=new Properties();connection.setProperty("spring.datasource.url",required("P0_INTEGRATION_MIGRATION_MYSQL_URL"));
        connection.setProperty("spring.datasource.username",required("P0_INTEGRATION_MIGRATION_MYSQL_USER"));
        connection.setProperty("spring.datasource.password",required("P0_INTEGRATION_MIGRATION_MYSQL_PASSWORD"));
        var approval=new Properties();approval.setProperty("expected.uuid",inventory.identity().serverUuid());
        approval.setProperty("expected.database",inventory.identity().database());approval.setProperty("expected.port","23316");
        approval.setProperty("candidate.jar.path",candidate.toString());approval.setProperty("candidate.jar.sha256",candidateSha);
        approval.setProperty("source.sha256",inventory.sha256());
        approval.setProperty("core.manifest.sha256","736294575e354900170114f8eb9568abaf15223a0bf7c9872d314d65559905e1");
        approval.setProperty("migration.version","45");approval.setProperty("migration.sha256",reference.path("integration").path("sha256_lf").asString());
        approval.setProperty("approval.evidence","SYNTHETIC_ISOLATED_COMMAND_CONTRACT_ONLY");
        Path duplicate=proofs.resolve("duplicate-resource-test-only.jar");
        try(var original=new java.util.jar.JarFile(candidate.toFile());var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(duplicate,StandardOpenOption.CREATE_NEW))){
            for(var entry:Collections.list(original.entries())){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));zip.write(original.getInputStream(entry).readAllBytes());zip.closeEntry();}
            String script="V44__discover_song_publication.sql";
            try(var resource=Objects.requireNonNull(CreditEntitlementV40MySqlMigrationTest.class.getResourceAsStream("/db/migration/"+script))){
                zip.putNextEntry(new java.util.zip.ZipEntry("BOOT-INF/classes/db/migration/"+script));zip.write(resource.readAllBytes());zip.closeEntry();
            }
        }
        var duplicateApproval=new Properties();duplicateApproval.putAll(approval);duplicateApproval.setProperty("candidate.jar.path",duplicate.toString());
        duplicateApproval.setProperty("candidate.jar.sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(duplicate))));
        assertEquals("FINANCIAL_MIGRATION_DUPLICATE_ARTIFACT_RESOURCE",assertThrows(IllegalStateException.class,
            ()->FinancialMigrationCommand.execute("validate",connection,duplicateApproval)).getMessage());
        for(String key:List.of("candidate.jar.sha256","source.sha256","core.manifest.sha256","migration.sha256")){
            var wrong=new Properties();wrong.putAll(approval);wrong.setProperty(key,"0".repeat(64));
            assertThrows(IllegalStateException.class,()->FinancialMigrationCommand.execute("validate",connection,wrong),key);
            assertEquals(44,number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
            assertEquals(0,number("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND LEFT(TABLE_NAME,3)='p0_'"));
        }
        var validated=FinancialMigrationCommand.execute("validate",connection,approval);
        assertEquals("PASS",validated.get("status"));assertEquals(44,((Number)validated.get("schemaVersion")).intValue());
        assertThrows(IllegalStateException.class,()->FinancialMigrationCommand.execute("migrate",connection,approval));
        approval.setProperty("migration.authorized","true");
        assertThrows(IllegalStateException.class,()->FinancialMigrationCommand.execute("migrate",connection,approval));
        for(String kind:List.of("writers","backup-restore","rollback","legacy-boundary")){
            var evidence=new LinkedHashMap<String,Object>();evidence.put("status","PASS");evidence.put("databaseUuid",inventory.identity().serverUuid());
            evidence.put("database",inventory.identity().database());evidence.put("port",23316);evidence.put("sourceSha256",inventory.sha256());
            evidence.put("candidateJarSha256",candidateSha);evidence.put("recordedAt",java.time.Instant.now().toString());
            for(String flag:List.of("serviceInactive","otherWritersStopped","connectionsDrained","globalProcesslistVerified","observerHasProcessPrivilege",
                    "restoreVerified","originalJournalUnchanged","newPurchasesDisabled","restoredStartupVerified","legacyOrdersReadOnly","balancesPreserved","allNewOrdersUseFencedLedger"))evidence.put(flag,true);
            evidence.put("otherSchemaConnections",0);evidence.put("otherApplicationAccountConnections",0);evidence.put("protocol",3);evidence.put("schemaVersion",45);
            evidence.put("rollbackJarSha256","c".repeat(64));evidence.put("contractVersion",2);evidence.put("paymentEnvironment","LIVE");
            evidence.put("allowedNewPaymentProviders",List.of("STRIPE_LIVE","PAYPAL_LIVE"));
            evidence.put("testOnly",true);evidence.put("productionApproval",false);
            Path receipt=proofs.resolve(kind+".json");byte[] content=new ObjectMapper().writeValueAsBytes(evidence);
            Files.write(receipt,content,StandardOpenOption.CREATE_NEW);approval.setProperty("receipt."+kind+".path",receipt.toString());
            approval.setProperty("receipt."+kind+".sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        }
        var applied=FinancialMigrationCommand.execute("migrate",connection,approval);
        assertEquals("PASS",applied.get("status"));assertEquals(45,((Number)applied.get("schemaVersion")).intValue());
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
        assertEquals(inventory.sha256(),new CreditHistoricalCutover(db,tx).inventory().sha256());
        var replay=FinancialMigrationCommand.execute("migrate",connection,approval);
        assertEquals("PASS",replay.get("status"));assertEquals(0,((Number)replay.get("migrationsExecuted")).intValue());
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
        assertEquals(inventory.sha256(),new CreditHistoricalCutover(db,tx).inventory().sha256());
    }

    private static void assertHistory(JsonNode migration) {
        var actual = db.queryForMap("SELECT script,checksum,success FROM flyway_schema_history WHERE version=?", migration.path("version").asString());
        assertEquals(migration.path("script").asString(), actual.get("script"));
        assertEquals(migration.path("checksum").asInt(), ((Number) actual.get("checksum")).intValue());
        Object success = actual.get("success");
        assertTrue(Boolean.TRUE.equals(success) || success instanceof Number value && value.intValue() == 1,
            "A migration must have an explicit successful Boolean/numeric JDBC result");
    }

    @Test @Order(1) void canonical44ChecksumsRemainAndActual45PreservesDownloadAndFinancialFacts() {
        assertEquals(45, number("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        for (var migration : reference.path("core")) assertHistory(migration);
        assertHistory(reference.path("integration"));
        assertEquals(0, number("SELECT COUNT(*) FROM flyway_schema_history WHERE script IN ('V33__credit_entitlement_recovery.sql','V34__payment_return_evidence.sql')"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='site_event' AND COLUMN_NAME='country' AND COLUMN_TYPE='varchar(64)' AND IS_NULLABLE='YES'"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='site_event' AND INDEX_NAME='idx_site_event_country' AND COLUMN_NAME='country'"));
        assertEquals(1, number("SELECT COUNT(*) FROM credit_ledger"));
        assertEquals(-500011050, number("SELECT checksum FROM flyway_schema_history WHERE version='42'"));
        assertEquals("V42__shared_download_entitlement.sql", db.queryForObject("SELECT script FROM flyway_schema_history WHERE version='42'", String.class));
        assertEquals(1, number("SELECT COUNT(*) FROM shared_download_entitlement WHERE title='original synthetic download'"));
    }

    @Test @Order(2) void expectedTablesAndAppendOnlyTriggersExistButNoCutoverIsActivated() {
        assertEquals(19, reference.path("integration_tables").size());
        for (var table : reference.path("integration_tables")) assertEquals("InnoDB", db.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", String.class, table.asString()));
        assertEquals(17, reference.path("triggers").size());
        for (var trigger : reference.path("triggers")) {
            var actual = db.queryForMap("SELECT ACTION_TIMING,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?", trigger.asString());
            assertEquals("BEFORE", actual.get("ACTION_TIMING")); assertTrue(actual.get("ACTION_STATEMENT").toString().contains("SIGNAL"));
        }
        assertEquals(3, number("SELECT protocol FROM p0_credit_writer_policy WHERE id=1"));
        assertNotEquals("P0_CREDIT_WRITER_V1", db.queryForObject("SELECT epoch FROM p0_credit_writer_policy WHERE id=1", String.class));
        assertEquals("PREPARING", db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1", String.class));
        assertEquals(0, number("SELECT COUNT(*) FROM p0_credit_cutover_state WHERE activated_at IS NOT NULL"));
    }

    @Test @Order(3) void preparingAndStaleEpochCannotAppendAndJournalCannotBeRewritten() {
        tx.executeWithoutResult(status -> {
            String epoch = db.queryForObject("SELECT epoch FROM p0_credit_writer_policy WHERE id=1", String.class);
            db.update("SET @qf_credit_writer_epoch=?", epoch);
            try {
                assertThrows(DataAccessException.class, () -> db.update("INSERT INTO credit_ledger VALUES('CREDIT','preparing-must-reject',?,1,CURRENT_TIMESTAMP)", owner));
                db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP WHERE id=1");
                db.execute("SET @qf_credit_writer_epoch='P0_CREDIT_WRITER_V1'");
                assertThrows(DataAccessException.class, () -> db.update("INSERT INTO credit_ledger VALUES('CREDIT','old-epoch-must-reject',?,1,CURRENT_TIMESTAMP)", owner));
                assertThrows(DataAccessException.class, () -> db.update("UPDATE credit_ledger SET amount=amount WHERE reference_id='migration-original-credit'"));
                assertThrows(DataAccessException.class, () -> db.update("DELETE FROM credit_ledger WHERE reference_id='migration-original-credit'"));
            } finally { db.execute("SET @qf_credit_writer_epoch=NULL"); status.setRollbackOnly(); }
        });
        assertEquals(1, number("SELECT COUNT(*) FROM credit_ledger"));
        assertEquals("PREPARING", db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1", String.class));
    }

    private static void nativeIdentity(String external, String kind, String resource, long amount) {
        db.update("INSERT INTO p0_credit_native_identity(provider,merchant,environment,external_id,owner_id,order_id,capture_id,kind,resource_id,amount_minor,currency,created_at) VALUES('STRIPE',?,'sandbox',?,?,?,?,?,?,?,'USD',CURRENT_TIMESTAMP)", MERCHANT, external, owner, order, CAPTURE, kind, resource, amount);
    }

    @Test @Order(4) void nativeReturnRegistryAndBindingNamespacesRejectConflictingClaims() {
        nativeIdentity(NATIVE, "REFUND_RETURN", "re_migration", 198);
        assertThrows(DuplicateKeyException.class, () -> nativeIdentity(NATIVE, "DISPUTE_RETURN", "dp_migration", 990));
        assertThrows(DuplicateKeyException.class, () -> nativeIdentity("txn_another", "REFUND_RETURN", "re_migration", 198));
        nativeIdentity("refund:" + "a".repeat(64), "REFUND_BINDING", "re_migration_binding", 198);
        nativeIdentity("dispute:" + "b".repeat(64), "DISPUTE_BINDING", "dp_migration_binding", 990);
        assertThrows(DataAccessException.class, () -> nativeIdentity("invalid_kind", "OTHER", "invalid_resource", 1));
        assertThrows(DataAccessException.class, () -> nativeIdentity("invalid_amount", "REFUND_RETURN", "invalid_amount", 0));
        assertEquals(3, number("SELECT COUNT(*) FROM p0_credit_native_identity"));
    }

    @Test @Order(5) void nativeBindingsAreDatabaseAppendOnly() {
        assertThrows(DataAccessException.class, () -> db.update("UPDATE p0_credit_native_identity SET amount_minor=amount_minor WHERE external_id=?", NATIVE));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM p0_credit_native_identity WHERE external_id=?", NATIVE));
        assertEquals(198, number("SELECT amount_minor FROM p0_credit_native_identity WHERE external_id=?", NATIVE));
    }

    @Test @Order(6) void batchConstraintAllowsConsumedRefundDebtButRejectsUnsupportedHold() {
        tx.executeWithoutResult(status -> {
            db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,created_at) VALUES('migration-debt-check',?,'PAYMENT',?,100,80,40,0,CURRENT_TIMESTAMP)", owner, order);
            assertThrows(DataAccessException.class, () -> db.update("UPDATE p0_credit_batch SET held=1 WHERE reference='migration-debt-check'"));
            assertEquals(80, number("SELECT consumed FROM p0_credit_batch WHERE reference='migration-debt-check'"));
            status.setRollbackOnly();
        });
        assertEquals(0, number("SELECT COUNT(*) FROM p0_credit_batch"));
    }

    @Test @Order(7) void successfulRefundFactsStayAppendOnlyInIntegratedSchema() {
        db.update("INSERT INTO p0_credit_refund VALUES('STRIPE',?,'sandbox','re_fact_migration',?,?,?,198,'USD')", MERCHANT, owner, order, CAPTURE);
        assertThrows(DataAccessException.class, () -> db.update("UPDATE p0_credit_refund SET amount_minor=amount_minor WHERE external_id='re_fact_migration'"));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM p0_credit_refund WHERE external_id='re_fact_migration'"));
        assertEquals(198, number("SELECT amount_minor FROM p0_credit_refund WHERE external_id='re_fact_migration'"));
    }

    private static void inbox(String merchant, String environment, String event) {
        db.update("INSERT INTO payment_webhook_inbox(provider,merchant_scope,env_scope,event_id,event_type,payload,payload_hash,status,attempts,next_attempt_at,created_at,updated_at) VALUES('STRIPE',?,?,?,'refund.updated','{}',?,'MANUAL',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", merchant, environment, event, "1".repeat(64));
    }

    @Test @Order(8) void inboxPreservesUnknownLegacyRowsAndSeparatesCaseSensitiveScopes() {
        var legacy = db.queryForMap("SELECT merchant_scope,env_scope,attempts FROM payment_webhook_inbox WHERE event_id='evt_migration_legacy'");
        assertEquals("LEGACY_UNKNOWN", legacy.get("merchant_scope"));
        assertEquals("LEGACY_UNKNOWN", legacy.get("env_scope"));
        assertEquals(2, ((Number) legacy.get("attempts")).intValue());
        inbox("acct_Scope", "sandbox", "evt_scope_Case");
        assertThrows(DuplicateKeyException.class, () -> inbox("acct_Scope", "sandbox", "evt_scope_Case"));
        inbox("acct_scope", "sandbox", "evt_scope_Case");
        inbox("acct_Scope", "live", "evt_scope_Case");
        inbox("acct_Scope", "sandbox", "evt_scope_case");
        assertEquals(4, number("SELECT COUNT(*) FROM payment_webhook_inbox WHERE event_id IN ('evt_scope_Case','evt_scope_case')"));
        assertEquals(4, number("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='payment_webhook_inbox' AND COLUMN_NAME IN ('provider','merchant_scope','env_scope','event_id') AND COLLATION_NAME='utf8mb4_bin'"));
    }

    @Test @Order(9) void manualReviewAndSystemAttemptAreSeparateAppendOnlyScopedEvidence() {
        inbox("acct_audit", "sandbox", "evt_audit");
        String review = UUID.randomUUID().toString(), attempt = UUID.randomUUID().toString();
        db.update("INSERT INTO payment_inbox_review(id,provider,merchant_scope,env_scope,event_id,reviewer,reason,action,created_at) VALUES(?,'STRIPE','acct_audit','sandbox','evt_audit','operator-test','synthetic approved resume','RESUME',CURRENT_TIMESTAMP)", review);
        db.update("INSERT INTO payment_inbox_attempt(id,provider,merchant_scope,env_scope,event_id,attempt,result,evidence_id,created_at) VALUES(?,'STRIPE','acct_audit','sandbox','evt_audit',0,'WAITING','synthetic verified pending',CURRENT_TIMESTAMP)", attempt);
        assertThrows(DataAccessException.class, () -> db.update("UPDATE payment_inbox_review SET reason=reason WHERE id=?", review));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM payment_inbox_review WHERE id=?", review));
        assertThrows(DataAccessException.class, () -> db.update("UPDATE payment_inbox_attempt SET result=result WHERE id=?", attempt));
        assertThrows(DataAccessException.class, () -> db.update("DELETE FROM payment_inbox_attempt WHERE id=?", attempt));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO payment_inbox_review(id,provider,merchant_scope,env_scope,event_id,reviewer,reason,action,created_at) VALUES(?,'STRIPE','acct_audit','sandbox','evt_audit','SYSTEM','not a human resume','WAITING',CURRENT_TIMESTAMP)", UUID.randomUUID().toString()));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO payment_inbox_attempt(id,provider,merchant_scope,env_scope,event_id,attempt,result,created_at) VALUES(?,'STRIPE','acct_audit','sandbox','evt_audit',-1,'FAILED',CURRENT_TIMESTAMP)", UUID.randomUUID().toString()));
        assertThrows(DataAccessException.class, () -> db.update("INSERT INTO payment_inbox_attempt(id,provider,merchant_scope,env_scope,event_id,attempt,result,created_at) VALUES(?,'STRIPE','wrong_merchant','sandbox','evt_audit',0,'FAILED',CURRENT_TIMESTAMP)", UUID.randomUUID().toString()));
        assertEquals(1, number("SELECT COUNT(*) FROM payment_inbox_review WHERE id=?", review));
        assertEquals(1, number("SELECT COUNT(*) FROM payment_inbox_attempt WHERE id=? AND result='WAITING' AND attempt=0", attempt));
    }

    @Test @Order(10) void opaqueNativeTransactionAndResourceIdsRemainCaseSensitive() {
        nativeIdentity("txn_migration_Case", "REFUND_RETURN", "re_migration_Case", 1);
        nativeIdentity("txn_migration_case", "REFUND_RETURN", "re_migration_case", 1);
        assertEquals(2, number("SELECT COUNT(*) FROM p0_credit_native_identity WHERE external_id IN ('txn_migration_Case','txn_migration_case')"));
        assertEquals(1, number("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='p0_credit_native_identity' AND COLUMN_NAME='external_id' AND COLLATION_NAME='utf8mb4_bin'"));
    }

    @Test @Order(11) void sharedReadinessRequiresEveryRealMysqlPrerequisiteAndAnExplicitActivation() {
        assertTrue(CreditSchemaReadiness.installed(db));
        assertFalse(CreditSchemaReadiness.ready(db), "PREPARING is never a usable financial schema");
        assertThrows(IllegalStateException.class, () -> CreditSchemaReadiness.requireActive(db));
        tx.executeWithoutResult(status -> {
            try {
                assertEquals(1, db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP,evidence='ISOLATED_READINESS_GATE_ONLY' WHERE id=1 AND status='PREPARING'"));
                for (var entry : CreditSchemaReadiness.CHECKS.entrySet()) {
                    Integer actual = db.queryForObject(entry.getValue().sql(), Integer.class);
                    assertEquals(Integer.valueOf(entry.getValue().expected()), actual, entry.getKey());
                }
                CreditSchemaReadiness.requireActive(db);
                assertTrue(CreditSchemaReadiness.ready(db));
            } finally { status.setRollbackOnly(); }
        });
        assertEquals("PREPARING", db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1", String.class));
        assertFalse(CreditSchemaReadiness.ready(db));
    }

    @Test @Order(12) void canonicalValidateAndMigrateRejectActualOriginalPayment40WithoutChangingHistory(@TempDir Path legacyChain) throws Exception {
        String prefix = "P0_INTEGRATION_LEGACY40_MYSQL_";
        String database = required(prefix + "DATABASE"), url = required(prefix + "URL");
        if (!database.matches("p0_integration_legacy40_20261112r[0-9]+")
                || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/" + database + "(?:\\?.*)?")
                || url.toLowerCase(Locale.ROOT).contains("password="))
            throw new IllegalStateException("Only the separate fresh isolated legacy40 rejection cohort is permitted");
        var source = new DriverManagerDataSource(url, required(prefix + "USER"), required(prefix + "PASSWORD"));
        var legacy = new JdbcTemplate(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            try (var identity = statement.executeQuery("SELECT VERSION(),@@server_uuid,@@datadir,@@port,@@bind_address,DATABASE(),CURRENT_ROLE()")) {
                assertTrue(identity.next()); assertEquals("8.0.46", identity.getString(1));
                assertEquals("05c27d3c-dda3-11f1-aba0-84a938421519", identity.getString(2));
                assertEquals(required(prefix + "UUID"), identity.getString(2));
                assertEquals("r:/data/", identity.getString(3).replace('\\', '/').toLowerCase(Locale.ROOT));
                assertEquals(23316, identity.getInt(4)); assertEquals("127.0.0.1", identity.getString(5));
                assertEquals(database, identity.getString(6)); assertEquals("NONE", identity.getString(7));
            }
            int grants = 0;
            try (var rows = statement.executeQuery("SHOW GRANTS")) { while (rows.next()) {
                String grant = rows.getString(1);
                assertTrue(grant.startsWith("GRANT USAGE ON *.*") || grant.contains("ON `" + database + "`.*"));
                assertFalse(grant.contains("WITH GRANT OPTION")); grants++;
            }}
            assertEquals(2, grants);
            try (var rows = statement.executeQuery("SHOW DATABASES")) { while (rows.next())
                assertTrue(Set.of(database, "information_schema", "performance_schema").contains(rows.getString(1)));
            }
        }
        assertEquals(0, legacy.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class), "Never clear or reuse the rejection cohort");
        for (var migration : reference.path("core")) {
            if (Integer.parseInt(migration.path("version").asString()) > 39) continue;
            String script = migration.path("script").asString();
            try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/" + script))) {
                Files.write(legacyChain.resolve(script), resource.readAllBytes(), StandardOpenOption.CREATE_NEW);
            }
        }
        var original = reference.path("legacy_payment_v40");
        String script = original.path("script").asString();
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/legacy-payment-migrations/" + script))) {
            byte[] bytes = resource.readAllBytes();
            assertEquals(original.path("sha256_lf").asString(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            Files.write(legacyChain.resolve(script), bytes, StandardOpenOption.CREATE_NEW);
        }
        Flyway.configure().dataSource(source).locations("filesystem:" + legacyChain.toAbsolutePath()).cleanDisabled(true).target("40").load().migrate();
        assertEquals(40, legacy.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL", Integer.class));
        assertEquals("V40__credit_entitlement_integration.sql", legacy.queryForObject("SELECT script FROM flyway_schema_history WHERE version='40'", String.class));
        assertEquals(-1311214355, legacy.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='40'", Integer.class));
        var history = legacy.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var tables = legacy.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
        var canonical = Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("45").load();
        var validation = canonical.validateWithResult();
        assertFalse(validation.validationSuccessful);
        assertTrue(validation.invalidMigrations.stream().anyMatch(item -> "40".equals(item.version)), "The actual original payment40 history must be identified as incompatible");
        assertThrows(FlywayValidateException.class, canonical::validate);
        assertThrows(FlywayValidateException.class, canonical::migrate);
        assertTrue(CreditSchemaReadiness.installed(legacy));
        assertFalse(CreditSchemaReadiness.ready(legacy));
        assertThrows(IllegalStateException.class, () -> CreditSchemaReadiness.requireActive(legacy));
        assertEquals(history, legacy.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"));
        assertEquals(tables, legacy.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME"));
        assertEquals(0, legacy.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='41'", Integer.class));
        assertEquals(0, legacy.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='45'", Integer.class));
        assertEquals(0, legacy.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='shared_download_entitlement'", Integer.class));
    }
}
