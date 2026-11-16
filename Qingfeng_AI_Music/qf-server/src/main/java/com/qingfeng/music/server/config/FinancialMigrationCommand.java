package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.persistence.credits.CreditCarryForwardCommand;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.regex.*;
import java.util.zip.CRC32;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogCreator;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit core44 -> financial45 operator command. No Spring context, supplier calls,
 * history repair, activation, historical mapping, or automatic application startup.
 */
public final class FinancialMigrationCommand {
    public static final String FINANCIAL_SHA256 = "3dd5cebf19dee960ecefb2239668139918d363496fe87710f817c5b3a3241685";
    public static final String CORE_MANIFEST_SHA256 = "736294575e354900170114f8eb9568abaf15223a0bf7c9872d314d65559905e1";
    private static final String LOCATION = "db/migration/";
    private static final String COMMAND_ENTRY = "com/qingfeng/music/server/config/FinancialMigrationCommand.class";
    private static final String LOCK = "qingfeng-financial-v45-migration";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ThreadLocal<Diagnostics> CLI_DIAGNOSTICS = new ThreadLocal<>();
    private FinancialMigrationCommand() {}

    public static void main(String[] args) {
        Diagnostics previous = CLI_DIAGNOSTICS.get();
        CLI_DIAGNOSTICS.set(new Diagnostics());
        try {
            require(args.length == 3, "EXPECTED_MODE_DB_PROPERTIES_APPROVAL_PROPERTIES");
            System.out.println(JSON.writeValueAsString(execute(args[0], properties(Path.of(args[1])), properties(Path.of(args[2])))));
        } catch (Exception failure) {
            // Supplier/driver exceptions may contain connection data. Never expose their text.
            String code = failure instanceof IllegalStateException && failure.getMessage() != null
                    && failure.getMessage().matches("[A-Z0-9_]{1,128}") ? failure.getMessage() : "FINANCIAL_MIGRATION_COMMAND_FAILED";
            Diagnostics diagnostics = CLI_DIAGNOSTICS.get();
            System.out.println("{\"status\":\"BLOCKED\",\"failure\":\"" + code
                    + "\",\"flywayWarnings\":" + diagnostics.warnings + ",\"flywayErrors\":" + diagnostics.errors + "}");
            System.exit(2);
        } finally {
            if (previous == null) CLI_DIAGNOSTICS.remove(); else CLI_DIAGNOSTICS.set(previous);
        }
    }

    public static Map<String, Object> execute(String mode, Properties connection, Properties contract) throws Exception {
        require(Set.of("validate", "migrate").contains(mode), "INVALID_FINANCIAL_MIGRATION_MODE");
        require("45".equals(required(contract, "migration.version"))
                && FINANCIAL_SHA256.equals(required(contract, "migration.sha256"))
                && CORE_MANIFEST_SHA256.equals(required(contract, "core.manifest.sha256")), "FINANCIAL_MIGRATION_CONTRACT_MISMATCH");
        String evidence = required(contract, "approval.evidence");
        require(evidence.length() <= 128, "FINANCIAL_MIGRATION_APPROVAL_REQUIRED");
        String sourceSha = required(contract, "source.sha256");
        require(isSha(sourceSha), "FINANCIAL_MIGRATION_SOURCE_REQUIRED");
        String jarSha = required(contract, "candidate.jar.sha256");
        Path jar = Path.of(required(contract, "candidate.jar.path"));
        require(isSha(jarSha) && Files.isRegularFile(jar) && jarSha.equals(sha(Files.readAllBytes(jar))), "FINANCIAL_MIGRATION_CANDIDATE_JAR_MISMATCH");
        verifyArtifact(jar);

        String url = required(connection, "spring.datasource.url");
        Matcher target = Pattern.compile("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::([0-9]+))?/([A-Za-z0-9_]+)(?:\\?[^\\r\\n]*)?").matcher(url);
        require(target.matches(), "FINANCIAL_MIGRATION_REQUIRES_LOCAL_MYSQL_TARGET");
        int jdbcPort = target.group(1) == null ? 3306 : Integer.parseInt(target.group(1));
        String database = required(contract, "expected.database");
        int port = Integer.parseInt(required(contract, "expected.port"));
        String uuid = required(contract, "expected.uuid");
        require(database.equals(target.group(2)) && port == jdbcPort
                && uuid.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"), "FINANCIAL_MIGRATION_JDBC_TARGET_MISMATCH");
        if ("migrate".equals(mode)) require("true".equals(required(contract, "migration.authorized")), "FINANCIAL_MIGRATION_NOT_AUTHORIZED");

        var driver = new DriverManagerDataSource(url, required(connection, "spring.datasource.username"), CreditCarryForwardCommand.connectionPassword(connection, contract));
        var options = new Properties();
        options.setProperty("connectionTimeZone", "UTC");
        options.setProperty("forceConnectionTimeZoneToSession", "true");
        driver.setConnectionProperties(options);
        try (var physical = driver.getConnection()) {
            var source = new SingleConnectionDataSource(physical, true);
            var db = new JdbcTemplate(source);
            boolean locked = false;
            try {
                physical.setReadOnly(true);
                CreditCarryForwardCommand.verifyJdbcClock(db);
                CreditCarryForwardCommand.verifyOperatorIdentity(db, connection, contract);
                var tx = new TransactionTemplate(new JdbcTransactionManager(source));
                var cutover = new CreditHistoricalCutover(db, tx);
                var snapshot = cutover.inventory();
                var expected = new CreditHistoricalCutover.Identity("MySQL", "8.0.46", database, uuid, port);
                require(snapshot.identity().equals(expected), "FINANCIAL_MIGRATION_DATABASE_IDENTITY_CHANGED");
                require(sourceSha.equals(snapshot.sha256()), "FINANCIAL_MIGRATION_SOURCE_DRIFT");
                int before = schemaVersion(db);
                verifySchema(db, before);
                flyway(source, before).validate();
                int executed = 0;
                TriggerDdlCapability triggerCapability = null;
                if ("migrate".equals(mode)) {
                    CreditCarryForwardCommand.validateReceipts(contract, expected, sourceSha, jarSha);
                    require(Integer.valueOf(0).equals(db.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND (DB=? OR USER=SUBSTRING_INDEX(CURRENT_USER(),'@',1))",
                            Integer.class, database)), "FINANCIAL_MIGRATION_OTHER_CONNECTIONS_PRESENT");
                    require(Integer.valueOf(1).equals(db.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, LOCK)), "FINANCIAL_MIGRATION_LOCK_UNAVAILABLE");
                    locked = true;
                    // Recheck the frozen source after acquiring the migration mutex, before DDL.
                    require(sourceSha.equals(cutover.inventory().sha256()), "FINANCIAL_MIGRATION_SOURCE_DRIFT");
                    triggerCapability = verifyTriggerDdlCapability(db);
                    physical.setReadOnly(false);
                    executed = flyway(source, 45).migrate().migrationsExecuted;
                    require(executed == (before == 44 ? 1 : 0), "FINANCIAL_MIGRATION_EXECUTION_COUNT_MISMATCH");
                    physical.setReadOnly(true);
                }
                int after = schemaVersion(db);
                require(after == ("migrate".equals(mode) ? 45 : before), "FINANCIAL_MIGRATION_SCHEMA_VERSION_MISMATCH");
                verifySchema(db, after);
                flyway(source, after).validate();
                require(sourceSha.equals(cutover.inventory().sha256()), "FINANCIAL_MIGRATION_SOURCE_MUTATED");
                var result = new LinkedHashMap<String, Object>();
                result.put("status", "PASS"); result.put("mode", mode);
                result.put("databaseUuid", uuid); result.put("database", database); result.put("port", port);
                result.put("engine", "MySQL"); result.put("databaseVersion", "8.0.46");
                result.put("coreVersion", 44); result.put("schemaVersion", after); result.put("migrationsExecuted", executed);
                result.put("candidateJarSha256", jarSha); result.put("sourceSha256", sourceSha);
                result.put("financialMigrationSha256", FINANCIAL_SHA256);
                result.put("financialMigrationChecksum", CreditSchemaReadiness.V45_CHECKSUM);
                result.put("coreManifestSha256", CORE_MANIFEST_SHA256);
                if (triggerCapability != null) result.put("triggerDdlPreflight", triggerCapability);
                String state = after == 45 ? db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1", String.class) : "NOT_INSTALLED";
                result.put("financialState", state); result.put("state", state);
                Diagnostics diagnostics = CLI_DIAGNOSTICS.get();
                if (diagnostics != null) {
                    require(diagnostics.errors == 0, "FINANCIAL_MIGRATION_LOGGED_ERROR");
                    result.put("flywayWarnings", diagnostics.warnings); result.put("flywayErrors", diagnostics.errors);
                }
                result.put("recordedAt", java.time.Instant.now().toString());
                return Collections.unmodifiableMap(result);
            } finally {
                if (locked) db.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK);
            }
        }
    }

    private static Flyway flyway(javax.sql.DataSource source, int target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .table("flyway_schema_history").target(Integer.toString(target))
                .validateOnMigrate(true).baselineOnMigrate(false).outOfOrder(false)
                .cleanDisabled(true).ignoreMigrationPatterns(new String[0])
                .loggers(CLI_DIAGNOSTICS.get() == null ? "auto" : JsonOnlyLogCreator.class.getName()).load();
    }

    public record TriggerDdlCapability(boolean binaryLoggingEnabled, boolean trustFunctionCreators,
                                       boolean globalSuperRequired, boolean globalSuperVerified) {}

    /**
     * MySQL 8.0 CREATE TRIGGER requires SUPER when binary logging is enabled and
     * log_bin_trust_function_creators is off. Inspect the current operator's grants,
     * never change the server variable or grant privileges to the runtime account.
     */
    public static TriggerDdlCapability verifyTriggerDdlCapability(JdbcTemplate db) {
        Boolean binary = db.queryForObject("SELECT @@log_bin", Boolean.class);
        Boolean trusted = db.queryForObject("SELECT @@log_bin_trust_function_creators", Boolean.class);
        require(binary != null && trusted != null, "FINANCIAL_MIGRATION_TRIGGER_CAPABILITY_UNKNOWN");
        boolean required = binary && !trusted;
        boolean superVerified = false;
        if (required) {
            Pattern global = Pattern.compile("^GRANT\\s+(.+?)\\s+ON\\s+\\*\\.\\*\\s+TO\\s+.+$", Pattern.CASE_INSENSITIVE);
            for (String grant : db.queryForList("SHOW GRANTS FOR CURRENT_USER()", String.class)) {
                Matcher match = global.matcher(grant.replace("`", ""));
                if (!match.matches()) continue;
                Set<String> privileges = new HashSet<>();
                for (String privilege : match.group(1).split(",")) privileges.add(privilege.strip().toUpperCase(Locale.ROOT));
                if (privileges.contains("SUPER") || privileges.contains("ALL PRIVILEGES")) superVerified = true;
            }
            require(superVerified, "FINANCIAL_MIGRATION_TRIGGER_SUPER_REQUIRED");
        }
        return new TriggerDdlCapability(binary, trusted, required, superVerified);
    }

    private static final class Diagnostics { int warnings; int errors; }

    /**
     * Flyway 11.7.2 accepts a LogCreator class name, not "none".
     * Only this standalone CLI suppresses raw diagnostic text; all failures still
     * propagate, and outside its thread context logging retains normal SLF4J behavior.
     */
    public static final class JsonOnlyLogCreator implements LogCreator {
        public JsonOnlyLogCreator() {}
        @Override public Log createLogger(Class<?> type) {
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(type);
            return new Log() {
                public boolean isDebugEnabled() { return CLI_DIAGNOSTICS.get() == null && logger.isDebugEnabled(); }
                public void debug(String message) { if (CLI_DIAGNOSTICS.get() == null) logger.debug(message); }
                public void info(String message) { if (CLI_DIAGNOSTICS.get() == null) logger.info(message); }
                public void notice(String message) { if (CLI_DIAGNOSTICS.get() == null) logger.info(message); }
                public void warn(String message) {
                    Diagnostics diagnostics = CLI_DIAGNOSTICS.get();
                    if (diagnostics == null) logger.warn(message); else diagnostics.warnings++;
                }
                public void error(String message) {
                    Diagnostics diagnostics = CLI_DIAGNOSTICS.get();
                    if (diagnostics == null) logger.error(message); else diagnostics.errors++;
                }
                public void error(String message, Exception failure) {
                    Diagnostics diagnostics = CLI_DIAGNOSTICS.get();
                    if (diagnostics == null) logger.error(message, failure); else diagnostics.errors++;
                }
            };
        }
    }

    private static int schemaVersion(JdbcTemplate db) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        require(count != null && (count == 44 || count == 45), "FINANCIAL_MIGRATION_HISTORY_LENGTH_MISMATCH");
        return count;
    }

    private static void verifySchema(JdbcTemplate db, int version) {
        if (version == 44) {
            require(!CreditSchemaReadiness.installed(db), "FINANCIAL_MIGRATION_PARTIAL_SCHEMA_PRESENT");
            CreditSchemaReadiness.requireCore44Ready(db);
        } else CreditSchemaReadiness.requireInstalled(db);
    }

    private record SqlResource(int version, String name, int checksum, String sha256) {}

    private static void verifyArtifact(Path jarPath) throws Exception {
        var loaded = new TreeMap<Integer, SqlResource>();
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
            try (var input = resource.getInputStream()) {
                SqlResource sql = sql(Objects.requireNonNull(resource.getFilename()), input.readAllBytes());
                require(loaded.put(sql.version(), sql) == null, "FINANCIAL_MIGRATION_DUPLICATE_RESOURCE");
            }
        }
        verifyResources(loaded);
        var packaged = new TreeMap<Integer, SqlResource>();
        try (var jar = new JarFile(jarPath.toFile())) {
            for (var entry : Collections.list(jar.entries())) {
                if (entry.getName().startsWith("BOOT-INF/classes/" + LOCATION) && entry.getName().endsWith(".sql")) {
                    String name = entry.getName().substring(("BOOT-INF/classes/" + LOCATION).length());
                    SqlResource sql = sql(name, jar.getInputStream(entry).readAllBytes());
                    require(packaged.put(sql.version(), sql) == null, "FINANCIAL_MIGRATION_DUPLICATE_ARTIFACT_RESOURCE");
                } else if (entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")) {
                    // The real application keeps infrastructure SQL in a nested module JAR.
                    // Inspect all library migration resources, so an unexpected duplicate cannot hide.
                    try (var nested = new JarInputStream(jar.getInputStream(entry))) {
                        java.util.jar.JarEntry child;
                        while ((child = nested.getNextJarEntry()) != null) {
                            if (!child.isDirectory() && child.getName().startsWith(LOCATION) && child.getName().endsWith(".sql")) {
                                SqlResource sql = sql(child.getName().substring(LOCATION.length()), nested.readAllBytes());
                                require(packaged.put(sql.version(), sql) == null, "FINANCIAL_MIGRATION_DUPLICATE_ARTIFACT_RESOURCE");
                            }
                        }
                    }
                }
            }
            verifyResources(packaged);
            require(loaded.equals(packaged), "FINANCIAL_MIGRATION_ARTIFACT_RESOURCE_MISMATCH");
            var command = jar.getJarEntry("BOOT-INF/classes/" + COMMAND_ENTRY);
            require(command != null, "FINANCIAL_MIGRATION_COMMAND_ARTIFACT_MISSING");
            try (var actual = FinancialMigrationCommand.class.getResourceAsStream("/" + COMMAND_ENTRY)) {
                require(actual != null && Arrays.equals(actual.readAllBytes(), jar.getInputStream(command).readAllBytes()), "FINANCIAL_MIGRATION_COMMAND_ARTIFACT_MISMATCH");
            }
        }
    }

    private static SqlResource sql(String name, byte[] bytes) throws Exception {
        require(name.matches("V[1-9][0-9]*__[A-Za-z0-9_]+\\.sql"), "FINANCIAL_MIGRATION_UNEXPECTED_RESOURCE");
        int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        CRC32 crc = new CRC32();
        text.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
        return new SqlResource(version, name, (int) crc.getValue(), sha(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void verifyResources(SortedMap<Integer, SqlResource> resources) throws Exception {
        // The V45 cutover seals V1–V45; later application migrations may be packaged too.
        var core = new StringBuilder();
        for (int version = 1; version <= 45; version++) {
            SqlResource sql = resources.get(version);
            require(sql != null, "FINANCIAL_MIGRATION_RESOURCE_CHAIN_INCOMPLETE");
            if (version <= 44) core.append(version).append('\t').append(sql.name()).append('\t')
                    .append(sql.checksum()).append('\t').append(sql.sha256()).append('\n');
        }
        require(CORE_MANIFEST_SHA256.equals(sha(core.toString().getBytes(StandardCharsets.UTF_8))), "FINANCIAL_MIGRATION_CORE_MANIFEST_MISMATCH");
        SqlResource financial = resources.get(45);
        require("V45__credit_entitlement_integration.sql".equals(financial.name())
                && financial.checksum() == CreditSchemaReadiness.V45_CHECKSUM
                && FINANCIAL_SHA256.equals(financial.sha256()), "FINANCIAL_MIGRATION_RESOURCE_MISMATCH");
    }

    private static boolean isSha(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static Properties properties(Path path) throws Exception {
        var result = new Properties();
        try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(input); }
        return result;
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank() && !value.contains("${"), "FINANCIAL_MIGRATION_PROPERTY_REQUIRED");
        return value;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalStateException(code); }
}
