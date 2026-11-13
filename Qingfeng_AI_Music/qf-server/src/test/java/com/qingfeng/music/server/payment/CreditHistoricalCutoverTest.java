package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure, synthetic planning assertions: these do not assert MySQL or production acceptance. */
class CreditHistoricalCutoverTest {
    private static final Instant T = Instant.parse("2020-01-01T00:00:00Z");
    private static Journal row(String type, String ref, String owner, long amount) { return new Journal(type, ref, owner, amount, T); }
    private static Snapshot snapshot(List<Owner> owners, Journal... rows) {
        return new Snapshot(new Identity("MODEL", "none", "offline", "none", 0), owners, List.of(rows), List.of(), List.of(), List.of());
    }
    private static Set<String> codes(Plan plan) { var result = new HashSet<String>(); plan.issues().forEach(i -> result.add(i.code())); return result; }
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
