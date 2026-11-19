package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

/** Pure, synthetic planning assertions: these do not assert MySQL or production acceptance. */
class CreditHistoricalCutoverTest {
    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");
    private static Journal row(String type, String ref, String owner, long amount) { return new Journal(type, ref, owner, amount, T); }
    private static Snapshot snapshot(List<Owner> owners, Journal... rows) {
        return new Snapshot(new Identity("MODEL", "none", "offline", "none", 0), owners, List.of(rows), List.of(), List.of(), List.of());
    }
    private static Set<String> codes(Plan plan) { var result = new HashSet<String>(); plan.issues().forEach(i -> result.add(i.code())); return result; }
    private static final String V46 = "V46__agent_chat_allowance.sql";
    private static final String V47 = "V47__music_video_projects.sql";
    private static final String V49 = "V49__lyrics_preparation.sql";
    private static List<Resource> classpathMigrations() throws Exception {
        return new ArrayList<>(Arrays.asList(new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")));
    }
    private static List<Resource> migrations(boolean includeV46) throws Exception {
        var resources = classpathMigrations();
        assertTrue(resources.stream().anyMatch(resource -> V46.equals(resource.getFilename())), "Test the real approved V46 resource, not a synthetic guard mock");
        // Explicit V45/V46 artifact-prefix fixtures; the separate main test checks the unfiltered classpath.
        resources.removeIf(resource -> version(resource) > (includeV46 ? 46 : 45));
        return resources;
    }
    private static int version(Resource resource) {
        String name = Objects.requireNonNull(resource.getFilename());
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
    private static int checksum(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var crc = new CRC32(); text.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
        return (int) crc.getValue();
    }
    private static List<History> history(List<Resource> resources, int limit) throws Exception {
        var result = new ArrayList<History>();
        for (var resource : resources) if (version(resource) <= limit) {
            try (var stream = resource.getInputStream()) {
                result.add(new History(version(resource), resource.getFilename(), checksum(stream.readAllBytes())));
            }
        }
        result.sort(Comparator.comparingInt(History::version));
        return result;
    }
    private static Resource resource(String name, byte[] bytes) {
        return new ByteArrayResource(bytes) { @Override public String getFilename() { return name; } };
    }
    private static void verifyHistory(List<History> actual, List<Resource> resources) throws Exception {
        // Execute the same private guard used by inventory(), substituting only its resource input.
        var guard = CreditHistoricalCutover.class.getDeclaredMethod("verifyHistory", List.class, Resource[].class);
        guard.setAccessible(true);
        try { guard.invoke(null, actual, resources.toArray(Resource[]::new)); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(failed.getCause());
        }
    }
    @Test void exactV45AndApprovedV46ArtifactsAcceptOnlyTheOriginalHistoricalSourceVersions() throws Exception {
        for (boolean includeV46 : List.of(false, true)) {
            var resources = migrations(includeV46);
            for (int limit : List.of(44, 45)) {
                var actual = history(resources, limit); assertEquals(limit, actual.size());
                assertDoesNotThrow(() -> verifyHistory(actual, resources));
            }
        }
    }
    @Test void everyOriginalResourceRemainsMandatoryEvenWhenTheSourceIsOnlyV44() throws Exception {
        for (int missing : List.of(1, 22, 45)) {
            var resources = migrations(true); var actual = history(resources, 44);
            resources.removeIf(resource -> version(resource) == missing);
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, resources));
            assertEquals("HISTORY_RESOURCE_CHAIN_INCOMPLETE", failure.getMessage());
        }
    }
    @Test void duplicateCoreAndApprovedExtensionResourcesStillFailClosed() throws Exception {
        for (int duplicate : List.of(1, 45, 46)) {
            var resources = migrations(true); var actual = history(resources, 44);
            resources.add(resources.stream().filter(resource -> version(resource) == duplicate).findFirst().orElseThrow());
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, resources));
            assertEquals("DUPLICATE_CLASSPATH_MIGRATION", failure.getMessage());
        }
    }
    @Test void unreviewedV47IsRejectedForBothPermittedArtifactChains() throws Exception {
        for (boolean includeV46 : List.of(false, true)) {
            var resources = migrations(includeV46); var actual = history(resources, 44);
            resources.add(resource("V47__unreviewed.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8)));
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, resources));
            assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", failure.getMessage());
        }
    }
    @Test void optionalV46MustRetainItsExactNameAndBothOriginalContentPins() throws Exception {
        var original = migrations(true); var actual = history(original, 44);
        byte[] bytes;
        try (var stream = original.stream().filter(item -> V46.equals(item.getFilename())).findFirst().orElseThrow().getInputStream()) { bytes = stream.readAllBytes(); }
        assertEquals(-26150424, checksum(bytes));
        for (String change : List.of("name", "sql", "same-crc")) {
            var changed = migrations(false);
            byte[] content = switch (change) {
                case "sql" -> "SELECT 1;".getBytes(StandardCharsets.UTF_8);
                case "same-crc" -> (new String(bytes, StandardCharsets.UTF_8) + "\n\n").getBytes(StandardCharsets.UTF_8);
                default -> bytes;
            };
            if (change.equals("same-crc")) assertEquals(checksum(bytes), checksum(content), "SHA guard must still reject a change invisible to Flyway's line CRC");
            changed.add(resource(change.equals("name") ? "V46__different_meaning.sql" : V46, content));
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, changed));
            assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", failure.getMessage());
        }
    }
    @Test void optionalV46UsesTheSameBomAndLineEndingNormalizationAsTheArtifactGate() throws Exception {
        var resources = migrations(true); var actual = history(resources, 45);
        var optional = resources.stream().filter(item -> V46.equals(item.getFilename())).findFirst().orElseThrow();
        String text;
        try (var stream = optional.getInputStream()) { text = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        resources.remove(optional);
        resources.add(resource(V46, ("\uFEFF" + text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n")).getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> verifyHistory(actual, resources));
    }
    @Test void actualV46AndChangedOrReorderedHistoricalRowsRemainForbidden() throws Exception {
        var resources = migrations(true);
        for (int limit : List.of(43, 46)) {
            var actual = history(resources, limit);
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, resources));
            assertEquals("HISTORY_CHAIN_LENGTH_MISMATCH", failure.getMessage());
        }
        var changed = history(resources, 45); var prior = changed.get(20);
        changed.set(20, new History(prior.version(), prior.script(), prior.checksum() + 1));
        assertEquals("HISTORY_SCRIPT_OR_CHECKSUM_MISMATCH", assertThrows(IllegalStateException.class, () -> verifyHistory(changed, resources)).getMessage());
        var reordered = history(resources, 45); Collections.swap(reordered, 20, 21);
        assertEquals("HISTORY_SCRIPT_OR_CHECKSUM_MISMATCH", assertThrows(IllegalStateException.class, () -> verifyHistory(reordered, resources)).getMessage());
    }
    @Test void approvedExtensionsMayCoexistWithoutChangingHistoricalSourceVersions() throws Exception {
        var resources = classpathMigrations(); assertEquals(49, resources.size());
        var optional = resources.stream().filter(item -> V47.equals(item.getFilename())).findFirst().orElseThrow();
        try (var stream = optional.getInputStream()) { assertEquals(-48314744, checksum(stream.readAllBytes())); }
        for (int limit : List.of(44, 45)) {
            var actual = history(resources, limit); assertEquals(limit, actual.size());
            assertDoesNotThrow(() -> verifyHistory(actual, resources));
        }
    }
    @Test void mainV47MustKeepItsOriginalNameAndBothContentPins() throws Exception {
        var original = classpathMigrations(); var actual = history(original, 44);
        byte[] bytes;
        try (var stream = original.stream().filter(item -> V47.equals(item.getFilename())).findFirst().orElseThrow().getInputStream()) { bytes = stream.readAllBytes(); }
        for (String change : List.of("name", "sql", "same-crc")) {
            var changed = migrations(true);
            byte[] content = switch (change) {
                case "sql" -> "SELECT 1;".getBytes(StandardCharsets.UTF_8);
                case "same-crc" -> (new String(bytes, StandardCharsets.UTF_8) + "\n\n").getBytes(StandardCharsets.UTF_8);
                default -> bytes;
            };
            if (change.equals("same-crc")) assertEquals(checksum(bytes), checksum(content));
            changed.add(resource(change.equals("name") ? "V47__different_meaning.sql" : V47, content));
            var failure = assertThrows(IllegalStateException.class, () -> verifyHistory(actual, changed));
            assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", failure.getMessage());
        }
    }
    @Test void mainV47CannotSkipV46DuplicateItsVersionPermitV48OrBecomeHistoricalInput() throws Exception {
        var resources = classpathMigrations(); var actual = history(resources, 44);
        var missing46 = new ArrayList<>(resources); missing46.removeIf(resource -> version(resource) == 46);
        assertEquals("HISTORY_RESOURCE_CHAIN_INCOMPLETE", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, missing46)).getMessage());
        var duplicate = new ArrayList<>(resources); duplicate.add(resources.stream().filter(resource -> V47.equals(resource.getFilename())).findFirst().orElseThrow());
        assertEquals("DUPLICATE_CLASSPATH_MIGRATION", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, duplicate)).getMessage());
        var future = new ArrayList<>(resources); future.add(resource("V48__unreviewed.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8)));
        assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, future)).getMessage());
        var actual47 = history(resources, 47); assertEquals(47, actual47.size());
        assertEquals("HISTORY_CHAIN_LENGTH_MISMATCH", assertThrows(IllegalStateException.class, () -> verifyHistory(actual47, resources)).getMessage());
    }
    @Test void phoneBindingResourceIsPinnedAndCannotSkipVideoMigration() throws Exception {
        var resources = classpathMigrations(); var actual = history(resources, 44);
        var missing = new ArrayList<>(resources); missing.removeIf(r -> version(r) == 47);
        assertEquals("HISTORY_RESOURCE_CHAIN_INCOMPLETE", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, missing)).getMessage());
        for (String name : List.of("V48__phone_binding.sql", "V48__different.sql")) {
            var modified = new ArrayList<>(resources); modified.removeIf(r -> version(r) == 48);
            modified.add(resource(name, "SELECT 1;".getBytes(StandardCharsets.UTF_8)));
            assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, modified)).getMessage());
        }
    }

    @Test void lyricsPreparationResourceIsPinnedAndCannotSkipPhoneBinding() throws Exception {
        var resources = classpathMigrations(); var actual = history(resources, 44);
        var missing = new ArrayList<>(resources); missing.removeIf(r -> version(r) == 48);
        assertEquals("HISTORY_RESOURCE_CHAIN_INCOMPLETE", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, missing)).getMessage());
        var modified = new ArrayList<>(resources); modified.removeIf(r -> version(r) == 49);
        modified.add(resource(V49, "SELECT 1;".getBytes(StandardCharsets.UTF_8)));
        assertEquals("HISTORY_UNAPPROVED_RESOURCE_EXTENSION", assertThrows(IllegalStateException.class, () -> verifyHistory(actual, modified)).getMessage());
    }

    @Test void remainingOpeningNeverInventsHistoricalFifoAndRefundsRetainExactReferences() {
        Snapshot source = snapshot(List.of(new Owner("a", 0, 80L)), row("CREDIT", "first-login:a", "a", 100),
                row("DEBIT", "old-1", "a", -30), row("REFUND", "old-1", "a", 30), row("DEBIT", "old-2", "a", -20));
        Plan plan = CreditHistoricalCutover.dryRun(source, Approval.none());
        assertTrue(plan.ready()); assertEquals(80, plan.openings().get(0).remaining());
        assertEquals(List.of(new Debit("old-1", "a", 30, true), new Debit("old-2", "a", 20, false)), plan.debits());
        assertTrue(plan.openings().get(0).reference().matches("legacy-opening:[a-f0-9]{64}"));
        assertEquals(4, source.journal().size()); assertEquals(100, source.journal().stream().filter(r -> r.type().equals("CREDIT")).findFirst().orElseThrow().amount());
    }
    @Test void negativeOpeningIsAnExplicitDebtBlockNeverAnInventedPositiveBatch() {
        var plan = CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 0, -5L)),
                row("CREDIT", "first-login:a", "a", 10), row("DEBIT", "old", "a", -15)), Approval.none());
        assertFalse(plan.ready()); assertTrue(codes(plan).contains("NEGATIVE_OPENING_REQUIRES_EXPLICIT_DEBT_STRUCTURE"));
        assertEquals(-5, plan.openings().get(0).remaining());
    }
    @Test void storedBalanceMismatchAndOverflowBlockInsteadOfRepairingMoney() {
        assertTrue(codes(CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 0, 999L)),
                row("CREDIT", "first-login:a", "a", 10)), Approval.none())).contains("STORED_BALANCE_JOURNAL_MISMATCH"));
        var overflow = CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 0, null)),
                row("CREDIT", "first-login:a", "a", Long.MAX_VALUE), row("CREDIT", "referral:a", "a", 1)), Approval.none());
        assertTrue(codes(overflow).contains("HISTORICAL_BALANCE_OVERFLOW")); assertTrue(overflow.openings().isEmpty());
    }
    @Test void crossOwnerAndPartialConsumptionRefundsAreNotAutomaticallyMerged() {
        var source = snapshot(List.of(new Owner("a", 0, 80L), new Owner("b", 0, 20L)),
                row("CREDIT", "first-login:a", "a", 100), row("DEBIT", "same", "a", -20), row("REFUND", "same", "b", 20));
        assertTrue(codes(CreditHistoricalCutover.dryRun(source, Approval.none())).contains("CONSUMPTION_REFUND_IDENTITY_OR_AMOUNT_CONFLICT"));
        var partial = snapshot(List.of(new Owner("a", 0, 90L)), row("CREDIT", "first-login:a", "a", 100),
                row("DEBIT", "same", "a", -20), row("REFUND", "same", "a", 10));
        assertTrue(codes(CreditHistoricalCutover.dryRun(partial, Approval.none())).contains("CONSUMPTION_REFUND_IDENTITY_OR_AMOUNT_CONFLICT"));
    }
    @Test void orphanAndDuplicateJournalIdentitiesBlock() {
        assertTrue(codes(CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 0, 20L)),
                row("REFUND", "missing", "a", 20)), Approval.none())).contains("ORPHAN_CONSUMPTION_REFUND"));
        var repeated = row("CREDIT", "first-login:a", "a", 10);
        assertTrue(codes(CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 0, 20L)), repeated, repeated), Approval.none()))
                .contains("DUPLICATE_JOURNAL_IDENTITY"));
    }
    @Test void manualCreditsNeedExactEvidenceAndStaleApprovalsFailClosed() {
        var source = snapshot(List.of(new Owner("a", 0, 50L)), row("CREDIT", "admin:old", "a", 50));
        assertFalse(CreditHistoricalCutover.dryRun(source, Approval.none()).ready());
        var missingEvidence = new Approval(Set.of("admin:old"), Set.of(), "");
        assertTrue(codes(CreditHistoricalCutover.dryRun(source, missingEvidence)).contains("OPERATOR_APPROVAL_EVIDENCE_REQUIRED"));
        var approved = CreditHistoricalCutover.dryRun(source, new Approval(Set.of("admin:old"), Set.of(), "signed-fixture-approval"));
        assertTrue(approved.ready());
        assertTrue(codes(CreditHistoricalCutover.dryRun(source, new Approval(Set.of("other"), Set.of(), "signed"))).contains("STALE_CREDIT_APPROVAL"));
    }
    @Test void cancelledLocalOrdersRemainUnknownEvenWhenExplicitlyIsolated() {
        Snapshot base = snapshot(List.of(new Owner("a", 0, 0L)));
        var order = new Order("o", "a", "PAYPAL_LIVE", "CANCELLED", null, "remote-id", "9.90", "USD", 100, T);
        var source = new Snapshot(base.identity(), base.owners(), base.journal(), List.of(order), List.of(), List.of());
        assertTrue(codes(CreditHistoricalCutover.dryRun(source, Approval.none())).contains("REMOTE_ORDER_STATE_UNKNOWN_RETAIN_FOR_REVIEW"));
        var plan = CreditHistoricalCutover.dryRun(source, new Approval(Set.of(), Set.of("o"), "retain-for-manual-review"));
        assertTrue(plan.ready()); assertEquals("CANCELLED", plan.source().orders().get(0).status());
        assertEquals(0, plan.openings().get(0).remaining());
    }
    @Test void paidPaymentAndFinancialFactsCannotBecomeUnattributedGiftRights() {
        var source = snapshot(List.of(new Owner("a", 0, 100L)), row("CREDIT", "payment:o", "a", 100));
        assertTrue(codes(CreditHistoricalCutover.dryRun(source, new Approval(Set.of("payment:o"), Set.of(), "approved")))
                .contains("PAYMENT_ATTRIBUTION_REQUIRES_VERIFIED_MAPPING"));
        var financial = snapshot(List.of(new Owner("a", 0, -10L)), row("PAYMENT_ADJUST", "financial", "a", -10));
        assertTrue(codes(CreditHistoricalCutover.dryRun(financial, Approval.none())).contains("HISTORICAL_FINANCIAL_FACTS_REQUIRE_VERIFIED_MAPPING"));
    }
    @Test void sourceAndApprovalsAreImmutableAndHashesIgnoreInputOrdering() {
        var owners = new ArrayList<>(List.of(new Owner("b", 0, 5L), new Owner("a", 0, 10L)));
        var rows = new ArrayList<>(List.of(row("CREDIT", "first-login:b", "b", 5), row("CREDIT", "first-login:a", "a", 10)));
        var source = new Snapshot(new Identity("MODEL", "none", "offline", "none", 0), owners, rows, List.of(), List.of(), List.of());
        String hash = source.sha256(); Collections.reverse(owners); Collections.reverse(rows);
        assertEquals(hash, new Snapshot(source.identity(), owners, rows, List.of(), List.of(), List.of()).sha256());
        owners.clear(); rows.clear(); assertEquals(2, source.owners().size()); assertEquals(2, source.journal().size());
        assertThrows(UnsupportedOperationException.class, () -> source.journal().clear());
        var plan = CreditHistoricalCutover.dryRun(source, Approval.none());
        assertEquals(plan, CreditHistoricalCutover.dryRun(source, Approval.none()));
        assertThrows(UnsupportedOperationException.class, () -> plan.openings().clear());
    }
    @Test void riskFlagsAndFinancialRiskFactsBlockHistoricalActivationReadiness() {
        assertTrue(codes(CreditHistoricalCutover.dryRun(snapshot(List.of(new Owner("a", 1, 0L))), Approval.none()))
                .contains("HISTORICAL_ACCOUNT_RISK_REQUIRES_REVIEW"));
        var base = snapshot(List.of(new Owner("a", 0, 0L)));
        var risk = new Snapshot(base.identity(), base.owners(), base.journal(), List.of(), List.of("opaque-risk-hash"), List.of());
        assertFalse(CreditHistoricalCutover.dryRun(risk, Approval.none()).ready());
    }
}
