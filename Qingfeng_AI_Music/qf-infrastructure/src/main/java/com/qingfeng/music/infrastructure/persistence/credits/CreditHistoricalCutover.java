package com.qingfeng.music.infrastructure.persistence.credits;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit offline cutover tool, never an application bean or startup initializer.
 * It imports remaining, unattributed NON-payment legacy rights, not fabricated FIFO.
 * Production application/migration/activation is deliberately outside this tool.
 */
public final class CreditHistoricalCutover {
    private static final String PREFIX = "legacy-opening:";
    private static final String OPTIONAL_V46 = "V46__agent_chat_allowance.sql";
    private static final String OPTIONAL_V46_SHA256 = "8ce991cea50af971ca6d748c1a37b304a8ee11d7969f06dd455dc16629638f86";
    private static final int OPTIONAL_V46_CHECKSUM = -26150424;
    // Later resources are content-pinned without widening the historical financial cutover input.
    private static final String OPTIONAL_V47 = "V47__music_video_projects.sql";
    private static final String OPTIONAL_V47_SHA256 = "b4a25044d77906e891d56632f037a68a1ba5dad2073a129188b11b8bcba8a08a";
    private static final int OPTIONAL_V47_CHECKSUM = -48314744;
    private static final String OPTIONAL_V48 = "V48__phone_binding.sql";
    private static final String OPTIONAL_V48_SHA256 = "09aeadab869727486bac96ac9200ad47310132763ad49020c0abe7844b359ca3";
    private static final int OPTIONAL_V48_CHECKSUM = -481399581;
    private static final String OPTIONAL_V49 = "V49__lyrics_preparation.sql";
    private static final String OPTIONAL_V49_SHA256 = "c0cde3e30b4e2bac3b3690c70dfd1125abced7045f1163ec9ee33a5c214616b4";
    private static final int OPTIONAL_V49_CHECKSUM = -1886072734;
    private static final Set<String> EMPTY_FINANCIAL_TABLES = Set.of(
        "p0_credit_allocation", "p0_credit_payment_binding", "p0_credit_refund", "p0_credit_dispute",
        "p0_credit_return", "p0_credit_financial_audit", "p0_credit_recovery", "p0_credit_recovery_audit",
        "p0_credit_recovery_approval", "p0_credit_refund_terminal", "p0_credit_refund_return",
        "p0_stripe_dispute_proof", "p0_credit_native_identity");
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final String isolatedServerUuid;
    private final String isolatedDataPath;

    public CreditHistoricalCutover(JdbcTemplate db, TransactionTemplate transactions) {
        this(db, transactions, UUID.fromString("05c27d3c-dda3-11f1-aba0-84a938421519"), "r:/data/");
    }

    CreditHistoricalCutover(JdbcTemplate db, TransactionTemplate transactions, UUID isolatedServerUuid, String isolatedDataPath) {
        this.db = Objects.requireNonNull(db);
        this.transactions = Objects.requireNonNull(transactions);
        this.isolatedServerUuid = Objects.requireNonNull(isolatedServerUuid).toString();
        this.isolatedDataPath = Objects.requireNonNull(isolatedDataPath);
    }

    public record Identity(String engine, String version, String database, String serverUuid, int port) {}
    public record Owner(String id, int riskFrozen, Long storedBalance) {}
    public record Journal(String type, String reference, String owner, long amount, Instant createdAt) {}
    public record Order(String id, String owner, String provider, String status, String capture,
                        String link, String amount, String currency, long credits, Instant createdAt) {}
    public record History(int version, String script, int checksum) {}
    public record Snapshot(Identity identity, List<Owner> owners, List<Journal> journal,
                           List<Order> orders, List<String> riskFacts, List<History> coreHistory) {
        public Snapshot {
            Objects.requireNonNull(identity);
            owners = owners.stream().sorted(Comparator.comparing(Owner::id)).toList();
            journal = journal.stream().sorted(journalOrder()).toList();
            orders = orders.stream().sorted(Comparator.comparing(Order::id)).toList();
            riskFacts = riskFacts.stream().sorted().toList();
            coreHistory = coreHistory.stream().sorted(Comparator.comparingInt(History::version)).toList();
        }
        public String sha256() { return digestSnapshot(this); }
    }
    /** Isolation is an acknowledgement to retain the unknown order for review, NEVER proof of nonpayment. */
    public record Approval(Set<String> approvedCreditReferences, Set<String> isolatedOrderIds, String evidence) {
        public Approval {
            approvedCreditReferences = Set.copyOf(approvedCreditReferences);
            isolatedOrderIds = Set.copyOf(isolatedOrderIds);
            Objects.requireNonNull(evidence);
        }
        public static Approval none() { return new Approval(Set.of(), Set.of(), ""); }
    }
    public record Issue(String code, String owner, String reference) {}
    public record Opening(String owner, String reference, long remaining, String journalSha256) {}
    public record Debit(String reference, String owner, long amount, boolean restored) {}
    public record Plan(Snapshot source, Approval approval, List<Opening> openings,
                       List<Debit> debits, List<Issue> issues, String sha256) {
        public Plan {
            openings = List.copyOf(openings); debits = List.copyOf(debits); issues = List.copyOf(issues);
        }
        public boolean ready() { return issues.isEmpty(); }
    }
    public record Applied(String sourceSha256, String planSha256, int owners, int debitMappings,
                          long journalTotal, boolean replay, String state, String engine) {}

    /** Explicit balance preservation, never an attestation about the funding of old orders. */
    public static Plan carryForwardPlan(Snapshot source, String evidence) {
        require(evidence != null && !evidence.isBlank() && evidence.length() <= 128,
                "CARRY_FORWARD_APPROVAL_REQUIRED");
        var credits = new HashSet<String>();
        source.journal().stream().filter(row -> "CREDIT".equals(row.type())).forEach(row -> credits.add(row.reference()));
        var orders = new HashSet<String>();
        source.orders().forEach(order -> orders.add(order.id()));
        Plan strict = dryRun(source, new Approval(credits, orders, evidence));
        // These facts remain untouched and unknown. No old order is bound to a payment
        // batch and no historic consumption allocation or cash provenance is invented.
        Set<String> provenanceOnly = Set.of("PAYMENT_ATTRIBUTION_REQUIRES_VERIFIED_MAPPING",
                "PAID_ORDER_ATTRIBUTION_REQUIRES_VERIFIED_MAPPING",
                "HISTORICAL_FINANCIAL_FACTS_REQUIRE_VERIFIED_MAPPING",
                "HISTORICAL_RISK_FACTS_REQUIRE_VERIFIED_MAPPING");
        var issues = strict.issues().stream().filter(issue -> !provenanceOnly.contains(issue.code())).toList();
        var scopedIssues = new ArrayList<>(issues);
        for (Order order : source.orders()) if (order.createdAt() == null)
            scopedIssues.add(new Issue("HISTORICAL_ORDER_TIME_MISSING", order.owner(), order.id()));
        String sha = digest(encode(out -> {
            field(out, "preserve-existing-balances-fenced-payments-v2");
            field(out, source.sha256()); field(out, evidence);
        }));
        return new Plan(source, strict.approval(), strict.openings(), strict.debits(), scopedIssues, sha);
    }

    /** Hashes are verified against external evidence files by the standalone command. */
    public record OperationalApproval(Identity destination, String sourceSha256, String planSha256,
            String stoppedWritersSha256, String backupRestoreSha256, String compatibleRollbackSha256,
            String legacyPaymentBoundarySha256) {
        public OperationalApproval {
            Objects.requireNonNull(destination);
            for (String value : Arrays.asList(sourceSha256, planSha256, stoppedWritersSha256,
                    backupRestoreSha256, compatibleRollbackSha256, legacyPaymentBoundarySha256))
                require(value != null && value.matches("[a-f0-9]{64}"), "CARRY_FORWARD_PROOF_REQUIRED");
        }
    }

    public Applied applyCarryForward(Plan plan, OperationalApproval approval) {
        verifyCarryForwardApproval(plan, approval);
        return apply(plan, plan.sha256(), () -> verifyControlledDestination(approval.destination()));
    }

    /** Activation is separate from import and requires all four release proofs again. */
    public Applied activateCarryForward(Plan plan, OperationalApproval approval) {
        verifyCarryForwardApproval(plan, approval);
        var write = new TransactionTemplate(transactions.getTransactionManager());
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return Objects.requireNonNull(write.execute(status -> {
            verifyControlledDestination(approval.destination());
            db.queryForList("SELECT id FROM creator ORDER BY id FOR UPDATE");
            var policy = db.queryForMap("SELECT epoch,protocol FROM p0_credit_writer_policy WHERE id=1 FOR UPDATE");
            require(number(policy, "protocol") == 3 && !policy.get("epoch").toString().isBlank(), "WRITER_PROTOCOL_MISMATCH");
            var state = db.queryForMap("SELECT status,evidence,activated_at FROM p0_credit_cutover_state WHERE id=1 FOR UPDATE");
            String evidence = "history:" + plan.source().sha256();
            require(evidence.equals(state.get("evidence")), "HISTORY_ACTIVATION_EVIDENCE_MISMATCH");
            require(plan.source().sha256().equals(readSnapshot(true).sha256()), "HISTORY_SOURCE_DRIFT");
            for (String table : EMPTY_FINANCIAL_TABLES)
                require(count("SELECT COUNT(*) FROM " + table) == 0, "EXISTING_FINANCIAL_FACTS_BLOCK_ACTIVATION");
            verifyProjection(plan, evidence);
            Timestamp activation = db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            require(activation != null && plan.source().orders().stream().allMatch(order -> order.createdAt() != null
                    && order.createdAt().isBefore(activation.toInstant())), "HISTORY_ORDER_TIME_BOUNDARY_NOT_STRICT");
            boolean replay = "ACTIVE".equals(state.get("status"));
            if (replay) require(state.get("activated_at") != null, "HISTORY_ACTIVE_WITHOUT_TIMESTAMP");
            else require(db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND status='PREPARING' AND evidence=? AND activated_at IS NULL", evidence) == 1,
                    "HISTORY_ACTIVATION_STATE_CONFLICT");
            long total = 0; for (Opening opening : plan.openings()) total = Math.addExact(total, opening.remaining());
            return new Applied(plan.source().sha256(), plan.sha256(), plan.openings().size(), plan.debits().size(),
                    total, replay, "ACTIVE", plan.source().identity().engine());
        }));
    }

    /** Pre-start verification. Once new writes begin the old source hash must no longer be replayed. */
    public Applied verifyCarryForward(Plan plan, OperationalApproval approval) {
        verifyCarryForwardApproval(plan, approval);
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(read.execute(status -> {
            verifyControlledDestination(approval.destination());
            require(plan.source().sha256().equals(readSnapshot(false).sha256()), "HISTORY_SOURCE_DRIFT");
            var policy = db.queryForMap("SELECT epoch,protocol FROM p0_credit_writer_policy WHERE id=1");
            require(number(policy, "protocol") == 3 && !policy.get("epoch").toString().isBlank(), "WRITER_PROTOCOL_MISMATCH");
            var state = db.queryForMap("SELECT status,evidence,activated_at FROM p0_credit_cutover_state WHERE id=1");
            String evidence = "history:" + plan.source().sha256();
            require("ACTIVE".equals(state.get("status")) && state.get("activated_at") != null
                    && evidence.equals(state.get("evidence")), "HISTORY_CUTOVER_NOT_ACTIVE");
            verifyProjection(plan, evidence);
            long total = 0; for (Opening opening : plan.openings()) total = Math.addExact(total, opening.remaining());
            return new Applied(plan.source().sha256(), plan.sha256(), plan.openings().size(), plan.debits().size(), total,
                    true, "ACTIVE", plan.source().identity().engine());
        }));
    }

    private void verifyCarryForwardApproval(Plan plan, OperationalApproval approval) {
        require(plan.ready() && plan.equals(carryForwardPlan(plan.source(), plan.approval().evidence())), "INVALID_OR_BLOCKED_CARRY_FORWARD_PLAN");
        require(plan.source().identity().equals(approval.destination())
                && plan.source().sha256().equals(approval.sourceSha256())
                && plan.sha256().equals(approval.planSha256()), "CARRY_FORWARD_APPROVAL_MISMATCH");
    }

    private void verifyControlledDestination(Identity expected) {
        Identity actual = identity();
        require(actual.equals(expected) && "MySQL".equals(actual.engine()) && "8.0.46".equals(actual.version()),
                "CARRY_FORWARD_DATABASE_IDENTITY_CHANGED");
        if ("f71f7437-d6a8-11f1-baa2-00163e1ce234".equals(actual.serverUuid()))
            require(actual.port() == 3306 && "qingfeng_music".equals(actual.database()), "CARRY_FORWARD_PRODUCTION_IDENTITY_MISMATCH");
        else verifyIsolatedDestination(expected);
        // An account without PROCESS only sees its own sessions. This is an
        // additional same-account guard, NOT proof that all other accounts are
        // absent. The command also requires a fresh privileged global observer
        // receipt before every apply/activate/verify operation.
        require(count("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND (DB=DATABASE() OR USER=SUBSTRING_INDEX(CURRENT_USER(),'@',1))") == 0,
                "CARRY_FORWARD_OTHER_DATABASE_CONNECTIONS_PRESENT");
    }

    /** SELECT-only consistent snapshot. Credentials, personal details and event payloads are never read. */
    public Snapshot inventory() {
        var read = new TransactionTemplate(transactions.getTransactionManager());
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(read.execute(status -> readSnapshot(false)));
    }

    public static Plan dryRun(Snapshot source, Approval approval) {
        Objects.requireNonNull(source); Objects.requireNonNull(approval);
        var issues = new ArrayList<Issue>();
        var owners = new TreeMap<String, Owner>();
        for (Owner owner : source.owners()) {
            if (owner.id() == null || owner.id().isBlank() || owners.put(owner.id(), owner) != null)
                issues.add(new Issue("DUPLICATE_OR_INVALID_OWNER", owner.id(), ""));
            if (owner.riskFrozen() != 0) issues.add(new Issue("HISTORICAL_ACCOUNT_RISK_REQUIRES_REVIEW", owner.id(), ""));
        }
        var rows = new TreeMap<String, Journal>();
        var byOwner = new TreeMap<String, List<Journal>>();
        for (Journal row : source.journal()) {
            String key = row.type() + "\u0000" + row.reference();
            if (rows.put(key, row) != null) issues.add(new Issue("DUPLICATE_JOURNAL_IDENTITY", row.owner(), row.reference()));
            byOwner.computeIfAbsent(row.owner(), ignored -> new ArrayList<>()).add(row);
            if (!owners.containsKey(row.owner())) issues.add(new Issue("JOURNAL_OWNER_MISSING", row.owner(), row.reference()));
            if (row.reference() == null || row.reference().isBlank() || row.reference().length() > 160 || row.createdAt() == null)
                issues.add(new Issue("INVALID_JOURNAL_REFERENCE_OR_TIME", row.owner(), row.reference()));
            switch (row.type()) {
                case "CREDIT" -> {
                    if (row.amount() <= 0) issues.add(new Issue("INVALID_CREDIT_SIGN", row.owner(), row.reference()));
                    if (row.reference().startsWith("payment:"))
                        issues.add(new Issue("PAYMENT_ATTRIBUTION_REQUIRES_VERIFIED_MAPPING", row.owner(), row.reference()));
                    else if (!row.reference().startsWith("first-login:") && !row.reference().startsWith("referral:")
                            && !approval.approvedCreditReferences().contains(row.reference()))
                        issues.add(new Issue("CREDIT_SOURCE_APPROVAL_REQUIRED", row.owner(), row.reference()));
                }
                case "DEBIT" -> { if (row.amount() >= 0 || row.amount() == Long.MIN_VALUE)
                    issues.add(new Issue("INVALID_DEBIT_SIGN_OR_RANGE", row.owner(), row.reference())); }
                case "REFUND" -> { if (row.amount() <= 0)
                    issues.add(new Issue("INVALID_CONSUMPTION_REFUND_SIGN", row.owner(), row.reference())); }
                default -> issues.add(new Issue("HISTORICAL_FINANCIAL_FACTS_REQUIRE_VERIFIED_MAPPING", row.owner(), row.reference()));
            }
        }
        if ((!approval.approvedCreditReferences().isEmpty() || !approval.isolatedOrderIds().isEmpty())
                && (approval.evidence().isBlank() || approval.evidence().length() > 128))
            issues.add(new Issue("OPERATOR_APPROVAL_EVIDENCE_REQUIRED", "", ""));
        for (String ref : approval.approvedCreditReferences())
            if (!rows.containsKey("CREDIT\u0000" + ref)) issues.add(new Issue("STALE_CREDIT_APPROVAL", "", ref));
        var openings = new ArrayList<Opening>();
        for (Owner owner : source.owners()) {
            var entries = byOwner.getOrDefault(owner.id(), List.of());
            try {
                long total = 0;
                for (Journal row : entries) total = Math.addExact(total, row.amount());
                if (owner.storedBalance() != null && owner.storedBalance() != total)
                    issues.add(new Issue("STORED_BALANCE_JOURNAL_MISMATCH", owner.id(), ""));
                if (total < 0) issues.add(new Issue("NEGATIVE_OPENING_REQUIRES_EXPLICIT_DEBT_STRUCTURE", owner.id(), ""));
                String hash = journalDigest(owner.id(), entries);
                openings.add(new Opening(owner.id(), PREFIX + hash, total, hash));
            } catch (ArithmeticException overflow) {
                issues.add(new Issue("HISTORICAL_BALANCE_OVERFLOW", owner.id(), ""));
            }
        }
        var debits = new ArrayList<Debit>();
        for (Journal row : source.journal()) {
            if ("DEBIT".equals(row.type()) && row.amount() < 0 && row.amount() != Long.MIN_VALUE) {
                Journal returned = rows.get("REFUND\u0000" + row.reference());
                if (returned != null && (!row.owner().equals(returned.owner()) || returned.amount() != -row.amount()))
                    issues.add(new Issue("CONSUMPTION_REFUND_IDENTITY_OR_AMOUNT_CONFLICT", row.owner(), row.reference()));
                debits.add(new Debit(row.reference(), row.owner(), -row.amount(), returned != null));
            } else if ("REFUND".equals(row.type()) && !rows.containsKey("DEBIT\u0000" + row.reference()))
                issues.add(new Issue("ORPHAN_CONSUMPTION_REFUND", row.owner(), row.reference()));
        }
        var orderIds = new HashSet<String>();
        var captures = new HashSet<String>();
        var links = new HashSet<String>();
        for (Order order : source.orders()) {
            if (!orderIds.add(order.id()) || !owners.containsKey(order.owner()))
                issues.add(new Issue("ORDER_IDENTITY_OR_OWNER_CONFLICT", order.owner(), order.id()));
            if (order.capture() != null && !captures.add(order.provider() + "\u0000" + order.capture()))
                issues.add(new Issue("DUPLICATE_PROVIDER_CAPTURE", order.owner(), order.id()));
            if (order.link() != null && !links.add(order.provider() + "\u0000" + order.link()))
                issues.add(new Issue("DUPLICATE_PROVIDER_ORDER", order.owner(), order.id()));
            if ("PAID".equals(order.status()))
                issues.add(new Issue("PAID_ORDER_ATTRIBUTION_REQUIRES_VERIFIED_MAPPING", order.owner(), order.id()));
            else if (!approval.isolatedOrderIds().contains(order.id()))
                issues.add(new Issue("REMOTE_ORDER_STATE_UNKNOWN_RETAIN_FOR_REVIEW", order.owner(), order.id()));
        }
        for (String id : approval.isolatedOrderIds()) if (!orderIds.contains(id))
            issues.add(new Issue("STALE_ORDER_ISOLATION_APPROVAL", "", id));
        if (!source.riskFacts().isEmpty()) issues.add(new Issue("HISTORICAL_RISK_FACTS_REQUIRE_VERIFIED_MAPPING", "", ""));
        issues.sort(Comparator.comparing(Issue::code).thenComparing(i -> Objects.toString(i.owner(), ""))
                .thenComparing(i -> Objects.toString(i.reference(), "")));
        String fingerprint = digest(encode(out -> {
            field(out, "credit-history-plan-v1"); field(out, source.sha256()); field(out, approval.evidence());
            for (String value : new TreeSet<>(approval.approvedCreditReferences())) { field(out, "C"); field(out, value); }
            for (String value : new TreeSet<>(approval.isolatedOrderIds())) { field(out, "O"); field(out, value); }
        }));
        return new Plan(source, approval, openings, debits, issues, fingerprint);
    }

    /**
     * Only synthetic single-schema local MySQL (or explicitly labelled H2 contract tests).
     * Never activates. The exact immutable plan must be approved separately from this call.
     * All owners are locked first, in primary-key order; no journal entry is rewritten.
     */
    public Applied applyIsolated(Plan plan, String approvedPlanSha256) {
        require(plan.ready() && plan.equals(dryRun(plan.source(), plan.approval())), "INVALID_OR_BLOCKED_HISTORY_PLAN");
        require(plan.sha256().equals(approvedPlanSha256), "HISTORY_PLAN_APPROVAL_MISMATCH");
        return apply(plan, approvedPlanSha256, () -> verifyIsolatedDestination(plan.source().identity()));
    }

    private Applied apply(Plan plan, String approvedPlanSha256, Runnable destinationGuard) {
        require(plan.sha256().equals(approvedPlanSha256), "HISTORY_PLAN_APPROVAL_MISMATCH");
        var write = new TransactionTemplate(transactions.getTransactionManager());
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        return Objects.requireNonNull(write.execute(status -> {
            destinationGuard.run();
            db.queryForList("SELECT id FROM creator ORDER BY id FOR UPDATE");
            var policy = db.queryForMap("SELECT epoch,protocol FROM p0_credit_writer_policy WHERE id=1 FOR UPDATE");
            require(((Number) policy.get("protocol")).intValue() == 3 && !policy.get("epoch").toString().isBlank(), "WRITER_PROTOCOL_MISMATCH");
            var cutover = db.queryForMap("SELECT status,evidence FROM p0_credit_cutover_state WHERE id=1 FOR UPDATE");
            require("PREPARING".equals(cutover.get("status")), "HISTORY_APPLY_REQUIRES_PREPARING");
            Snapshot current = readSnapshot(true);
            require(current.sha256().equals(plan.source().sha256()), "HISTORY_SOURCE_DRIFT");
            String evidence = "history:" + current.sha256();
            boolean replay = evidence.equals(cutover.get("evidence"));
            require(cutover.get("evidence") == null || replay, "HISTORY_EVIDENCE_CONFLICT");
            for (String table : EMPTY_FINANCIAL_TABLES)
                require(count("SELECT COUNT(*) FROM " + table) == 0, "EXISTING_FINANCIAL_FACTS_BLOCK_IMPORT");
            Timestamp boundary = db.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
            require(boundary != null && current.journal().stream().allMatch(row -> row.createdAt().isBefore(boundary.toInstant())), "HISTORY_TIME_BOUNDARY_NOT_STRICT");
            require(current.orders().stream().allMatch(order -> order.createdAt() != null
                    && order.createdAt().isBefore(boundary.toInstant())), "HISTORY_ORDER_TIME_BOUNDARY_NOT_STRICT");
            if (!replay) {
                require(count("SELECT COUNT(*) FROM p0_credit_batch") == 0 && count("SELECT COUNT(*) FROM p0_legacy_debit_mapping") == 0,
                        "PARTIAL_HISTORY_IMPORT_REQUIRES_REVIEW");
                for (Opening opening : plan.openings())
                    db.update("INSERT INTO p0_credit_batch(reference,owner_id,source_type,source_id,original,consumed,adjusted,held,status,created_at) VALUES(?,?,'LEGACY_OPENING',?,?,0,0,0,'ACTIVE',?)",
                            opening.reference(), opening.owner(), evidence, opening.remaining(), boundary);
                for (Debit debit : plan.debits())
                    db.update("INSERT INTO p0_legacy_debit_mapping(reference,owner_id,amount,restored,attribution) VALUES(?,?,?,?,'UNKNOWN')",
                            debit.reference(), debit.owner(), debit.amount(), debit.restored() ? 1 : 0);
                require(db.update("UPDATE p0_credit_cutover_state SET evidence=? WHERE id=1 AND status='PREPARING' AND evidence IS NULL", evidence) == 1,
                        "HISTORY_EVIDENCE_RACE");
            }
            verifyProjection(plan, evidence);
            require(readSnapshot(true).sha256().equals(current.sha256()), "HISTORY_SOURCE_MUTATED_DURING_IMPORT");
            long total = 0; for (Opening opening : plan.openings()) total = Math.addExact(total, opening.remaining());
            return new Applied(current.sha256(), plan.sha256(), plan.openings().size(), plan.debits().size(), total,
                    replay, "PREPARING", current.identity().engine());
        }));
    }

    /** Runtime replay permission: a hash of the COMPLETE historical prefix, not a clock alone. */
    public static boolean isHistoricalCreditReplay(JdbcTemplate db, String owner, long amount, String reference) {
        if (owner == null || reference == null || reference.startsWith("payment:") || amount <= 0) return false;
        try {
            var openings = db.queryForList("SELECT reference,source_id,created_at FROM p0_credit_batch WHERE owner_id=? AND source_type='LEGACY_OPENING' FOR UPDATE", owner);
            if (openings.size() != 1) return false;
            var opening = openings.get(0);
            // The caller already holds the owner and shared writer-policy fence.
            // Immutable ACTIVE evidence needs no global exclusive lock after batch locks.
            String evidence = db.queryForObject("SELECT evidence FROM p0_credit_cutover_state WHERE id=1 AND status='ACTIVE'", String.class);
            if (evidence == null || !evidence.matches("history:[a-f0-9]{64}") || !evidence.equals(opening.get("source_id"))) return false;
            Timestamp boundary = (Timestamp) opening.get("created_at");
            var prefix = db.query("SELECT entry_type,reference_id,owner_id,amount,created_at FROM credit_ledger WHERE owner_id=? AND created_at<? ORDER BY entry_type,reference_id FOR UPDATE",
                    (rs, n) -> new Journal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toInstant()), owner, boundary);
            boolean exact = prefix.stream().anyMatch(row -> row.type().equals("CREDIT") && row.reference().equals(reference) && row.amount() == amount);
            return exact && (PREFIX + journalDigest(owner, prefix)).equals(opening.get("reference"));
        } catch (RuntimeException incomplete) { return false; }
    }

    private void verifyProjection(Plan plan, String evidence) {
        require(count("SELECT COUNT(*) FROM p0_credit_batch") == plan.openings().size(), "HISTORY_BATCH_COUNT_MISMATCH");
        require(count("SELECT COUNT(*) FROM p0_legacy_debit_mapping") == plan.debits().size(), "HISTORY_MAPPING_COUNT_MISMATCH");
        for (Opening opening : plan.openings()) {
            var batch = db.queryForMap("SELECT * FROM p0_credit_batch WHERE reference=? AND owner_id=?", opening.reference(), opening.owner());
            require("LEGACY_OPENING".equals(batch.get("source_type")) && evidence.equals(batch.get("source_id"))
                    && "ACTIVE".equals(batch.get("status")) && number(batch, "original") == opening.remaining()
                    && number(batch, "consumed") == 0 && number(batch, "adjusted") == 0 && number(batch, "held") == 0,
                    "HISTORY_BATCH_CONTENT_MISMATCH");
            Timestamp created = (Timestamp) batch.get("created_at");
            require(plan.source().journal().stream().allMatch(row -> row.createdAt().isBefore(created.toInstant())), "HISTORY_STORED_BOUNDARY_MISMATCH");
        }
        for (Debit debit : plan.debits()) {
            var mapping = db.queryForMap("SELECT * FROM p0_legacy_debit_mapping WHERE reference=?", debit.reference());
            require(debit.owner().equals(mapping.get("owner_id")) && debit.amount() == number(mapping, "amount")
                    && (debit.restored() ? 1 : 0) == number(mapping, "restored") && "UNKNOWN".equals(mapping.get("attribution")),
                    "HISTORY_MAPPING_CONTENT_MISMATCH");
        }
    }

    private Snapshot readSnapshot(boolean lock) {
        Identity identity = identity();
        List<History> history = db.query("SELECT version,script,checksum,type,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                (rs, n) -> {
                    require("SQL".equals(rs.getString(4)) && rs.getBoolean(5), "FAILED_OR_NON_SQL_MIGRATION_HISTORY");
                    return new History(Integer.parseInt(rs.getString(1)), rs.getString(2), rs.getInt(3));
                });
        verifyHistory(history);
        String suffix = lock ? " FOR UPDATE" : "";
        List<Owner> owners = db.query("SELECT c.id,c.payment_risk_frozen,a.balance FROM creator c LEFT JOIN credit_account a ON a.owner_id=c.id ORDER BY c.id" + suffix,
                (rs, n) -> new Owner(rs.getString(1), rs.getInt(2), rs.getObject(3) == null ? null : rs.getLong(3)));
        List<Journal> journal = db.query("SELECT entry_type,reference_id,owner_id,amount,created_at FROM credit_ledger ORDER BY entry_type,reference_id" + suffix,
                (rs, n) -> new Journal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getTimestamp(5).toInstant()));
        List<Order> orders = db.query("SELECT id,creator_id,provider,status,provider_capture_id,provider_link_id,amount,currency,credits,created_at FROM payment_order ORDER BY id" + suffix,
                (rs, n) -> new Order(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getBigDecimal(7).toPlainString(), rs.getString(8), rs.getLong(9), rs.getTimestamp(10).toInstant()));
        List<String> risk = db.query("SELECT id,provider,resource_id,event_type,order_id,owner_id,status,last_event_id FROM payment_risk_case ORDER BY id" + suffix,
                (rs, n) -> digest(encode(out -> { for (int i = 1; i <= 8; i++) field(out, rs.getString(i)); })));
        return new Snapshot(identity, owners, journal, orders, risk, history.stream().filter(row -> row.version() <= 44).toList());
    }

    private Identity identity() {
        String engine = db.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());
        if ("MySQL".equals(engine)) {
            // serverTimezone/connectionTimeZone alone only describe the driver conversion.
            // A local-time MySQL session would make DB NOW and Instant-bound journal rows
            // use different boundaries. Refuse rather than infer or shift historical times.
            String sessionZone = db.queryForObject("SELECT @@session.time_zone", String.class);
            require(Set.of("+00:00", "UTC").contains(sessionZone), "HISTORY_SESSION_TIMEZONE_NOT_UTC");
            return db.queryForObject("SELECT VERSION(),DATABASE(),@@server_uuid,@@port",
                    (rs, n) -> new Identity(engine, rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
        }
        require("H2".equals(engine), "UNSUPPORTED_HISTORY_DATABASE_ENGINE");
        return new Identity("H2", "CONTRACT_TEST_ONLY", "H2_IN_MEMORY_CONTRACT_ONLY", "NOT_MYSQL", 0);
    }

    private void verifyIsolatedDestination(Identity expected) {
        Identity actual = identity();
        require(actual.equals(expected), "HISTORY_DATABASE_IDENTITY_CHANGED");
        if ("H2".equals(actual.engine())) return; // Explicitly labelled in Applied.engine, never MySQL acceptance.
        require("8.0.46".equals(actual.version()) && actual.port() == 23316
                && isolatedServerUuid.equals(actual.serverUuid())
                && actual.database().matches("p0_historical_cutover_20261113r[0-9]+"), "HISTORY_APPLY_NOT_ISOLATED");
        var location = db.queryForMap("SELECT @@datadir data_path,@@bind_address bind_address,CURRENT_ROLE() active_role");
        require(isolatedDataPath.equals(location.get("data_path").toString().replace('\\', '/').toLowerCase(Locale.ROOT))
                && "127.0.0.1".equals(location.get("bind_address")) && "NONE".equals(location.get("active_role")), "HISTORY_ISOLATION_NOT_PROVED");
        for (String grant : db.queryForList("SHOW GRANTS", String.class))
            require(grant.startsWith("GRANT USAGE ON *.*") || grant.contains(" ON `" + actual.database() + "`.* TO "), "HISTORY_ACCOUNT_HAS_EXTERNAL_PRIVILEGES");
        for (String schema : db.queryForList("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA", String.class))
            require(Set.of(actual.database(), "information_schema", "performance_schema").contains(schema), "HISTORY_EXTERNAL_SCHEMA_VISIBLE");
    }

    private static void verifyHistory(List<History> actual) {
        try {
            verifyHistory(actual, new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql"));
        } catch (java.io.IOException invalid) { throw new IllegalStateException("HISTORY_RESOURCE_UNREADABLE", invalid); }
    }

    private static void verifyHistory(List<History> actual, org.springframework.core.io.Resource[] resources) {
        try {
            var expected = new TreeMap<Integer, History>();
            for (var resource : resources) {
                String name = Objects.requireNonNull(resource.getFilename());
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                require(version >= 1 && version <= 59, "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                CRC32 crc = new CRC32();
                String text;
                try (var input = resource.getInputStream()) { text = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                text.lines().forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
                if (version == 46) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require(OPTIONAL_V46.equals(name) && (int) crc.getValue() == OPTIONAL_V46_CHECKSUM
                            && OPTIONAL_V46_SHA256.equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 47) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require(OPTIONAL_V47.equals(name) && (int) crc.getValue() == OPTIONAL_V47_CHECKSUM
                            && OPTIONAL_V47_SHA256.equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 48) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require(OPTIONAL_V48.equals(name) && (int) crc.getValue() == OPTIONAL_V48_CHECKSUM
                            && OPTIONAL_V48_SHA256.equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 49) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require(OPTIONAL_V49.equals(name) && (int) crc.getValue() == OPTIONAL_V49_CHECKSUM
                            && OPTIONAL_V49_SHA256.equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 50) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V50__discover_daily_plays_and_vocal_approval.sql".equals(name) && (int) crc.getValue() == -2123351923
                            && "bcedc1392b8623d7d31516f01c101f17aa170ae105ac6866f7a7ef71b77f510a".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 54) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V54__song_share_statistics.sql".equals(name) && (int) crc.getValue() == 570458891
                            && "9d837e8c53171c274f2367d1ff99ead74eb706659b0a612b6f5aa2aec0b9f633".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 53) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V53__agent_admission_version.sql".equals(name) && (int) crc.getValue() == -1976084986
                            && "83af90babf7a7a299177d08d0b3e1eb79bcabf7cc0454cbd4d6025ae074e5591".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 51) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V51__payment_reconciliation_cursor.sql".equals(name) && (int) crc.getValue() == 1186757330
                            && "7030c4fba4bc5009ca921fb59f931ebef952f6f93d67efd77889c298cd2ee045".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 52) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V52__gift_daily_budget.sql".equals(name) && (int) crc.getValue() == -938954138
                            && "898c2e8a1b47009f68b21554dd8d5e46614eb29ed77475d108e5ea568bf1ad07".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 55) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V55__generation_audio_reference.sql".equals(name) && (int) crc.getValue() == -2046873812
                            && "c629d909975214990cd9d02561d364e3cd5274e1ede43c57ed1ef9971450c4d0".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 56) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V56__discover_song_community.sql".equals(name) && (int) crc.getValue() == -827620177
                            && "3f3a64d35e5b57b508f51823ef90f5732e304cbfe080d026845bd41bdc6e056a".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 57) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V57__extended_lyric_languages.sql".equals(name) && (int) crc.getValue() == -823088408
                            && "1ca4736350a6f7d4af512e97858da6cfbe5859f8614bcd7b32626cb3e054c553".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 58) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V58__wechat_self_card.sql".equals(name) && (int) crc.getValue() == -1001320552
                            && "4a31e5debd6cd01e17d7827343916f63fea414d985c2416e75b1126af0268e7d".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                if (version == 59) {
                    String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                    require("V59__ai_capacity_queue.sql".equals(name) && (int) crc.getValue() == 595702576
                            && "9b26469a2be6556b1065a4f89204870caf9655bf6c1cbcfa6ccb8b2806dc99a1".equals(digest(normalized.getBytes(StandardCharsets.UTF_8))),
                            "HISTORY_UNAPPROVED_RESOURCE_EXTENSION");
                }
                require(expected.put(version, new History(version, name, (int) crc.getValue())) == null, "DUPLICATE_CLASSPATH_MIGRATION");
            }
            // Known later resources must form an exact prefix, not widen the historical source contract.
            int lastVersion = expected.isEmpty() ? 45 : Math.max(45, expected.lastKey());
            for (int version = 1; version <= lastVersion; version++)
                require(expected.containsKey(version), "HISTORY_RESOURCE_CHAIN_INCOMPLETE");
            require(actual.size() == 44 || actual.size() == 45, "HISTORY_CHAIN_LENGTH_MISMATCH");
            for (int i = 0; i < actual.size(); i++)
                require(actual.get(i).version() == i + 1 && actual.get(i).equals(expected.get(i + 1)), "HISTORY_SCRIPT_OR_CHECKSUM_MISMATCH");
        } catch (java.io.IOException invalid) { throw new IllegalStateException("HISTORY_RESOURCE_UNREADABLE", invalid); }
    }

    private static Comparator<Journal> journalOrder() { return Comparator.comparing(Journal::type).thenComparing(Journal::reference).thenComparing(Journal::owner); }
    private static String journalDigest(String owner, List<Journal> journal) {
        return digest(encode(out -> {
            field(out, "legacy-journal-v1"); field(out, owner);
            for (Journal row : journal.stream().sorted(journalOrder()).toList()) journalFields(out, row);
        }));
    }
    private static String digestSnapshot(Snapshot snapshot) {
        return digest(encode(out -> {
            field(out, "credit-history-source-v1"); field(out, snapshot.identity().toString());
            for (History history : snapshot.coreHistory()) field(out, history.toString());
            for (Owner owner : snapshot.owners()) { field(out, "OWNER"); field(out, owner.toString()); }
            for (Journal row : snapshot.journal()) { field(out, "JOURNAL"); journalFields(out, row); }
            for (Order order : snapshot.orders()) { field(out, "ORDER"); field(out, order.toString()); }
            for (String fact : snapshot.riskFacts()) { field(out, "RISK"); field(out, fact); }
        }));
    }
    private static void journalFields(DataOutputStream out, Journal row) throws Exception {
        field(out, row.type()); field(out, row.reference()); field(out, row.owner());
        out.writeLong(row.amount()); field(out, Objects.toString(row.createdAt(), null));
    }
    private interface Encoder { void write(DataOutputStream out) throws Exception; }
    private static byte[] encode(Encoder encoder) {
        try { var bytes = new ByteArrayOutputStream(); try (var out = new DataOutputStream(bytes)) { encoder.write(out); } return bytes.toByteArray(); }
        catch (Exception invalid) { throw new IllegalStateException("HISTORY_CANONICAL_ENCODING_FAILED", invalid); }
    }
    private static void field(DataOutputStream out, String value) throws Exception {
        if (value == null) out.writeInt(-1);
        else { byte[] data = value.getBytes(StandardCharsets.UTF_8); out.writeInt(data.length); out.write(data); }
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private long count(String sql) { return Objects.requireNonNull(db.queryForObject(sql, Long.class)); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
