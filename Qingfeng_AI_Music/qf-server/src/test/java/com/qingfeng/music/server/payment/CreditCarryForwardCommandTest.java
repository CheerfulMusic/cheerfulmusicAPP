package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import com.qingfeng.music.infrastructure.persistence.credits.CreditCarryForwardCommand;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class CreditCarryForwardCommandTest {
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");
    private static final Identity ID = new Identity("MySQL", "8.0.46", "synthetic", "synthetic-uuid", 23316);
    @TempDir Path directory;
    private static Snapshot legacy(long stored, int frozen) {
        return new Snapshot(ID, List.of(new Owner("owner", frozen, stored)),
                List.of(new Journal("CREDIT", "payment:old", "owner", 100, OLD),
                        new Journal("DEBIT", "old-consumption", "owner", -20, OLD),
                        new Journal("CREDIT", "admin:unattributed", "owner", 25, OLD)),
                List.of(new Order("old", "owner", "PAYPAL_LIVE", "PAID", "capture", "remote", "9.90", "USD", 100, OLD)),
                List.of("uninterpreted-historical-risk-fact"), List.of());
    }
    @Test void explicitCarryForwardPreservesBalancesOrdersAndUnknownProvenance() {
        Snapshot source = legacy(105, 0);
        Plan plan = CreditHistoricalCutover.carryForwardPlan(source, "user-preserve-existing-balances-v1");
        assertTrue(plan.ready(), plan.issues().toString());
        assertEquals(105, plan.openings().get(0).remaining());
        assertEquals(List.of(new Debit("old-consumption", "owner", 20, false)), plan.debits());
        assertEquals(source, plan.source()); assertEquals("PAID", source.orders().get(0).status());
        assertFalse(CreditHistoricalCutover.dryRun(source, plan.approval()).ready(), "The strict attribution contract is not weakened");
        assertNotEquals(CreditHistoricalCutover.dryRun(source, plan.approval()).sha256(), plan.sha256());
    }
    @Test void negativeBalanceRiskAndCacheMismatchStillBlockInsteadOfBeingRepaired() {
        assertFalse(CreditHistoricalCutover.carryForwardPlan(legacy(999, 0), "preserve-v1").ready());
        assertFalse(CreditHistoricalCutover.carryForwardPlan(legacy(105, 1), "preserve-v1").ready());
        var debt = new Snapshot(ID, List.of(new Owner("owner", 0, -5L)),
                List.of(new Journal("CREDIT", "admin:old", "owner", 10, OLD), new Journal("DEBIT", "spent", "owner", -15, OLD)), List.of(), List.of(), List.of());
        Plan plan = CreditHistoricalCutover.carryForwardPlan(debt, "preserve-v1");
        assertFalse(plan.ready());
        assertTrue(plan.issues().stream().anyMatch(i -> i.code().equals("NEGATIVE_OPENING_REQUIRES_EXPLICIT_DEBT_STRUCTURE")));
    }
    @Test void orphanRefundAndCrossOwnerDuplicateCannotHideBehindPreserveScope() {
        Snapshot source = new Snapshot(ID, List.of(new Owner("owner", 0, 1L)),
                List.of(new Journal("REFUND", "missing", "owner", 1, OLD)), List.of(), List.of(), List.of());
        Plan plan = CreditHistoricalCutover.carryForwardPlan(source, "preserve-v1");
        assertFalse(plan.ready()); assertTrue(plan.issues().stream().anyMatch(i -> i.code().equals("ORPHAN_CONSUMPTION_REFUND")));
    }
    @Test void emptyApprovalAndWrongJdbcScopeFailBeforeAnyConnection() {
        assertThrows(IllegalStateException.class, () -> CreditHistoricalCutover.carryForwardPlan(legacy(105, 0), ""));
        Properties connection = new Properties(), contract = new Properties();
        connection.setProperty("spring.datasource.url", "jdbc:mysql://cheerfulmusic.com:3306/qingfeng_music");
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.execute("apply", connection, contract));
        connection.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:23316/one");
        contract.setProperty("expected.database", "other"); contract.setProperty("expected.port", "23316");
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.execute("apply", connection, contract));
    }
    private Map<String, Object> receipt() {
        var result = new HashMap<String, Object>();
        result.put("status", "PASS"); result.put("databaseUuid", ID.serverUuid()); result.put("database", ID.database());
        result.put("port", ID.port()); result.put("sourceSha256", "a".repeat(64)); result.put("candidateJarSha256", "b".repeat(64));
        result.put("recordedAt", Instant.now().toString());
        for (String key : List.of("serviceInactive", "otherWritersStopped", "connectionsDrained", "restoreVerified", "originalJournalUnchanged",
                "newPurchasesDisabled", "restoredStartupVerified", "legacyOrdersReadOnly", "balancesPreserved", "newStripeOnly",
                "globalProcesslistVerified", "observerHasProcessPrivilege")) result.put(key, true);
        result.put("otherSchemaConnections", 0); result.put("otherApplicationAccountConnections", 0);
        result.put("protocol", 3); result.put("schemaVersion", 44); result.put("rollbackJarSha256", "c".repeat(64)); return result;
    }
    private Properties receipts(Map<String, Object> receipt) throws Exception {
        var contract = new Properties();
        for (String kind : List.of("writers", "backup-restore", "rollback", "legacy-boundary")) {
            Path path = directory.resolve(UUID.randomUUID() + ".json");
            byte[] content = JsonMapper.builder().build().writeValueAsBytes(receipt); Files.write(path, content, StandardOpenOption.CREATE_NEW);
            contract.setProperty("receipt." + kind + ".path", path.toString());
            contract.setProperty("receipt." + kind + ".sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        }
        return contract;
    }
    @Test void fullEvidenceMustBindPhysicalDatabaseSourceAndCandidate() throws Exception {
        assertEquals(4, CreditCarryForwardCommand.validateReceipts(receipts(receipt()), ID, "a".repeat(64), "b".repeat(64)).size());
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(receipt()), ID, "d".repeat(64), "b".repeat(64)));
        var other = new Identity(ID.engine(), ID.version(), ID.database(), "other-server", ID.port());
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(receipt()), other, "a".repeat(64), "b".repeat(64)));
    }
    @Test void alteredFileFailedRestoreAndStringBooleanCannotBecomeEvidence() throws Exception {
        Properties contract = receipts(receipt()); Files.writeString(Path.of(contract.getProperty("receipt.writers.path")), "{}");
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(contract, ID, "a".repeat(64), "b".repeat(64)));
        var failed = receipt(); failed.put("restoreVerified", false);
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(failed), ID, "a".repeat(64), "b".repeat(64)));
        var fake = receipt(); fake.put("serviceInactive", "true");
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(fake), ID, "a".repeat(64), "b".repeat(64)));
    }
    @Test void missingLegacyBoundaryOrIncompatibleRollbackAndStaleReceiptBlock() throws Exception {
        for (String key : List.of("legacyOrdersReadOnly", "restoredStartupVerified")) {
            var receipt = receipt(); receipt.put(key, false);
            assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(receipt), ID, "a".repeat(64), "b".repeat(64)));
        }
        var stale = receipt(); stale.put("recordedAt", OLD.toString());
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(stale), ID, "a".repeat(64), "b".repeat(64)));
        var wrongProtocol = receipt(); wrongProtocol.put("protocol", 2);
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(wrongProtocol), ID, "a".repeat(64), "b".repeat(64)));
    }
    @Test void nonPrivilegedPartialVisibilityActiveGlobalConnectionAndExpiredStopObservationBlock() throws Exception {
        for (String key : List.of("globalProcesslistVerified", "observerHasProcessPrivilege")) {
            var partial = receipt(); partial.put(key, false);
            assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(partial), ID, "a".repeat(64), "b".repeat(64)));
        }
        for (String key : List.of("otherSchemaConnections", "otherApplicationAccountConnections")) {
            var active = receipt(); active.put(key, 1);
            assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(active), ID, "a".repeat(64), "b".repeat(64)));
            var untyped = receipt(); untyped.put(key, "0");
            assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(untyped), ID, "a".repeat(64), "b".repeat(64)));
        }
        var expired = receipt(); expired.put("recordedAt", Instant.now().minusSeconds(301).toString());
        assertThrows(IllegalStateException.class, () -> CreditCarryForwardCommand.validateReceipts(receipts(expired), ID, "a".repeat(64), "b".repeat(64)));
    }
}
