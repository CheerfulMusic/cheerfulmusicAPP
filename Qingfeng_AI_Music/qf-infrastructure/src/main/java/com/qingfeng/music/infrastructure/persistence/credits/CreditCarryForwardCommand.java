package com.qingfeng.music.infrastructure.persistence.credits;

import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit offline operator command. No Spring context, migration, supplier call or automatic startup. */
public final class CreditCarryForwardCommand {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private CreditCarryForwardCommand() {}

    public static void main(String[] args) {
        try {
            if (args.length != 3 || !Set.of("plan", "apply", "activate", "verify").contains(args[0]))
                throw new IllegalStateException("EXPECTED_MODE_DB_PROPERTIES_APPROVAL_PROPERTIES");
            Map<String, Object> result = execute(args[0], properties(Path.of(args[1])), properties(Path.of(args[2])));
            System.out.println(JSON.writeValueAsString(result));
        } catch (Exception failure) {
            // Driver exceptions can contain URLs or credentials. Never print raw exception messages.
            String code = failure instanceof IllegalStateException && failure.getMessage() != null
                    && failure.getMessage().matches("[A-Z0-9_]{1,128}") ? failure.getMessage() : "CARRY_FORWARD_COMMAND_FAILED";
            System.out.println("{\"status\":\"BLOCKED\",\"failure\":\"" + code + "\"}");
            System.exit(2);
        }
    }

    public static Map<String, Object> execute(String mode, Properties connection, Properties contract) throws Exception {
        require(Set.of("plan", "apply", "activate", "verify").contains(mode), "INVALID_CARRY_FORWARD_MODE");
        String url = required(connection, "spring.datasource.url");
        Matcher target = Pattern.compile("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::([0-9]+))?/([A-Za-z0-9_]+)(?:\\?[^\\r\\n]*)?").matcher(url);
        require(target.matches(), "CARRY_FORWARD_REQUIRES_EXPLICIT_LOCAL_MYSQL_TARGET");
        int port = target.group(1) == null ? 3306 : Integer.parseInt(target.group(1));
        String database = required(contract, "expected.database");
        require(database.equals(target.group(2)) && port == Integer.parseInt(required(contract, "expected.port")), "CARRY_FORWARD_JDBC_TARGET_MISMATCH");
        Identity destination = new Identity("MySQL", "8.0.46", database, required(contract, "expected.uuid"), port);
        var source = new DriverManagerDataSource(url, required(connection, "spring.datasource.username"), connectionPassword(connection, contract));
        var driver = new Properties();
        driver.setProperty("connectionTimeZone", "UTC");
        driver.setProperty("forceConnectionTimeZoneToSession", "true");
        source.setConnectionProperties(driver);
        var db = new JdbcTemplate(source);
        verifyJdbcClock(db);
        verifyOperatorIdentity(db, connection, contract);
        var cutover = new CreditHistoricalCutover(db, new TransactionTemplate(new JdbcTransactionManager(source)));
        Snapshot snapshot = cutover.inventory();
        require(snapshot.identity().equals(destination), "CARRY_FORWARD_DATABASE_IDENTITY_CHANGED");
        Plan plan = CreditHistoricalCutover.carryForwardPlan(snapshot, required(contract, "approval.evidence"));
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode); result.put("databaseUuid", destination.serverUuid());
        result.put("database", database); result.put("port", port); result.put("engine", destination.engine());
        result.put("sourceSha256", snapshot.sha256()); result.put("planSha256", plan.sha256());
        result.put("owners", plan.openings().size()); result.put("debitMappings", plan.debits().size());
        result.put("preservedOrders", snapshot.orders().size());
        result.put("issues", plan.issues().stream().map(Issue::code).distinct().sorted().toList());
        if ("plan".equals(mode)) {
            result.put("status", plan.ready() ? "PASS" : "BLOCKED");
            result.put("state", "READ_ONLY_PLAN"); return result;
        }
        require(plan.ready(), "INVALID_OR_BLOCKED_CARRY_FORWARD_PLAN");
        require(snapshot.sha256().equals(required(contract, "source.sha256"))
                && plan.sha256().equals(required(contract, "plan.sha256")), "CARRY_FORWARD_APPROVAL_MISMATCH");
        String jarSha = required(contract, "candidate.jar.sha256");
        require(isSha(jarSha) && jarSha.equals(sha256(Path.of(required(contract, "candidate.jar.path")))), "CARRY_FORWARD_CANDIDATE_JAR_MISMATCH");
        Map<String, String> proofs = validateReceipts(contract, destination, snapshot.sha256(), jarSha);
        var approval = new OperationalApproval(destination, snapshot.sha256(), plan.sha256(), proofs.get("writers"),
                proofs.get("backup-restore"), proofs.get("rollback"), proofs.get("legacy-boundary"));
        Applied applied = switch (mode) {
            case "apply" -> cutover.applyCarryForward(plan, approval);
            case "activate" -> cutover.activateCarryForward(plan, approval);
            case "verify" -> cutover.verifyCarryForward(plan, approval);
            default -> throw new IllegalStateException("INVALID_CARRY_FORWARD_MODE");
        };
        result.put("status", "PASS"); result.put("state", applied.state()); result.put("protocol", 3);
        result.put("journalTotal", applied.journalTotal()); result.put("replay", applied.replay());
        result.put("candidateJarSha256", jarSha);
        result.put("oldWriterStoppedEvidenceSha256", proofs.get("writers"));
        result.put("backupRestoreEvidenceSha256", proofs.get("backup-restore"));
        result.put("rollbackCompatibilityEvidenceSha256", proofs.get("rollback"));
        result.put("legacyOrderIsolationEvidenceSha256", proofs.get("legacy-boundary"));
        return result;
    }

    /** Check the actual connection decoder, before reading even the historical snapshot. */
    public static void verifyJdbcClock(JdbcTemplate db) {
        db.execute((ConnectionCallback<Void>) connection -> {
            require("MySQL".equals(connection.getMetaData().getDatabaseProductName()), "CARRY_FORWARD_REQUIRES_MYSQL");
            try (var statement = connection.createStatement(); var row = statement.executeQuery(
                    "SELECT @@session.time_zone,CURRENT_TIMESTAMP(6),UNIX_TIMESTAMP(CURRENT_TIMESTAMP(6))")) {
                require(row.next(), "CARRY_FORWARD_JDBC_CLOCK_UNAVAILABLE");
                require(Set.of("UTC", "+00:00").contains(row.getString(1)), "CARRY_FORWARD_SESSION_NOT_UTC");
                var timestamp = row.getTimestamp(2); var epoch = row.getBigDecimal(3);
                require(timestamp != null && epoch != null, "CARRY_FORWARD_JDBC_CLOCK_UNAVAILABLE");
                Instant decoded = timestamp.toInstant();
                BigDecimal decodedEpoch = BigDecimal.valueOf(decoded.getEpochSecond()).add(BigDecimal.valueOf(decoded.getNano(), 9));
                require(decodedEpoch.subtract(epoch).abs().compareTo(BigDecimal.ONE) < 0, "CARRY_FORWARD_JDBC_TIMEZONE_DECODE_MISMATCH");
            }
            return null;
        });
    }

    /**
     * Operator-only exception for an explicitly approved, existing local root account.
     * It is never an application configuration or permission change. A missing password
     * property, a blank nonempty password, another account or a URL credential override
     * cannot use this exception.
     */
    public static String connectionPassword(Properties connection, Properties contract) {
        String url = required(connection, "spring.datasource.url");
        int query = url.indexOf('?');
        if (query >= 0) for (String option : url.substring(query + 1).split("[&;]", -1)) {
            int equals = option.indexOf('=');
            String key;
            try { key = java.net.URLDecoder.decode(equals < 0 ? option : option.substring(0, equals), StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("CARRY_FORWARD_INVALID_JDBC_OPTION"); }
            require(!Set.of("user", "username", "password", "password1", "password2", "password3", "pwd").contains(key),
                    "CARRY_FORWARD_JDBC_CREDENTIAL_OVERRIDE_FORBIDDEN");
        }
        require(connection.containsKey("spring.datasource.password"), "CARRY_FORWARD_PROPERTY_REQUIRED");
        String password = connection.getProperty("spring.datasource.password");
        require(password != null && !password.contains("${"), "CARRY_FORWARD_PROPERTY_REQUIRED");
        if (!password.isBlank()) return password;
        require(password.isEmpty()
                && "true".equals(contract.getProperty("allow-local-root-with-empty-password"))
                && "root".equals(required(connection, "spring.datasource.username"))
                && url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/[A-Za-z0-9_]+(?:\\?[^\\r\\n#]*)?"),
                "CARRY_FORWARD_EMPTY_PASSWORD_NOT_APPROVED");
        return password;
    }

    /** Confirm the server-authenticated account for the exceptional root path. */
    public static void verifyOperatorIdentity(JdbcTemplate db, Properties connection, Properties contract) {
        if (connectionPassword(connection, contract).isEmpty())
            require("root".equals(db.queryForObject("SELECT SUBSTRING_INDEX(CURRENT_USER(),'@',1)", String.class)),
                    "CARRY_FORWARD_OPERATOR_IDENTITY_MISMATCH");
    }

    /** Every receipt binds this exact physical database, frozen source and candidate artifact. */
    public static Map<String, String> validateReceipts(Properties contract, Identity identity, String sourceSha, String jarSha) throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String kind : List.of("writers", "backup-restore", "rollback", "legacy-boundary")) {
            Path path = Path.of(required(contract, "receipt." + kind + ".path"));
            String expected = required(contract, "receipt." + kind + ".sha256");
            require(isSha(expected) && expected.equals(sha256(path)), "CARRY_FORWARD_RECEIPT_HASH_MISMATCH");
            JsonNode receipt = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            require("PASS".equals(receipt.path("status").asText())
                    && identity.serverUuid().equals(receipt.path("databaseUuid").asText())
                    && identity.database().equals(receipt.path("database").asText())
                    && identity.port() == receipt.path("port").asInt()
                    && sourceSha.equals(receipt.path("sourceSha256").asText())
                    && jarSha.equals(receipt.path("candidateJarSha256").asText()), "CARRY_FORWARD_RECEIPT_SCOPE_MISMATCH");
            Instant recorded = Instant.parse(receipt.path("recordedAt").asText());
            require(!recorded.isAfter(Instant.now().plusSeconds(60)) && !recorded.isBefore(Instant.now().minus(Duration.ofHours(24))), "CARRY_FORWARD_RECEIPT_STALE");
            switch (kind) {
                case "writers" -> {
                    require(!recorded.isBefore(Instant.now().minus(Duration.ofMinutes(5))), "CARRY_FORWARD_WRITER_OBSERVATION_STALE");
                    require(truth(receipt, "serviceInactive") && truth(receipt, "otherWritersStopped") && truth(receipt, "connectionsDrained")
                            && truth(receipt, "globalProcesslistVerified") && truth(receipt, "observerHasProcessPrivilege")
                            && zero(receipt, "otherSchemaConnections") && zero(receipt, "otherApplicationAccountConnections"), "CARRY_FORWARD_WRITERS_NOT_STOPPED");
                }
                case "backup-restore" -> require(truth(receipt, "restoreVerified") && truth(receipt, "originalJournalUnchanged"), "CARRY_FORWARD_BACKUP_RESTORE_NOT_VERIFIED");
                case "rollback" -> require(receipt.path("protocol").asInt() == 3 && receipt.path("schemaVersion").asInt() == 45
                        && truth(receipt, "newPurchasesDisabled") && truth(receipt, "restoredStartupVerified")
                        && isSha(receipt.path("rollbackJarSha256").asText()), "CARRY_FORWARD_ROLLBACK_NOT_VERIFIED");
                case "legacy-boundary" -> require(receipt.path("contractVersion").asInt() == 2
                        && truth(receipt, "legacyOrdersReadOnly") && truth(receipt, "balancesPreserved")
                        && truth(receipt, "allNewOrdersUseFencedLedger")
                        && "LIVE".equals(receipt.path("paymentEnvironment").asText())
                        && validPaymentProviders(receipt.path("allowedNewPaymentProviders")), "CARRY_FORWARD_LEGACY_BOUNDARY_NOT_VERIFIED");
                default -> throw new IllegalStateException("INVALID_CARRY_FORWARD_RECEIPT");
            }
            hashes.put(kind, expected);
        }
        return Map.copyOf(hashes);
    }

    private static boolean validPaymentProviders(JsonNode providers) {
        if (!providers.isArray() || providers.size() != 2) return false;
        var values = new HashSet<String>();
        for (JsonNode provider : providers) if (!provider.isString() || !values.add(provider.asText())) return false;
        return values.equals(Set.of("STRIPE_LIVE", "PAYPAL_LIVE"));
    }
    private static boolean truth(JsonNode receipt, String field) { return receipt.path(field).isBoolean() && receipt.path(field).asBoolean(); }
    private static boolean zero(JsonNode receipt, String field) { return receipt.path(field).isIntegralNumber() && receipt.path(field).asLong() == 0; }
    private static boolean isSha(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static Properties properties(Path path) throws Exception {
        var result = new Properties(); try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { result.load(input); } return result;
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank() && !value.contains("${"), "CARRY_FORWARD_PROPERTY_REQUIRED"); return value;
    }
    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalStateException(code); }
}
