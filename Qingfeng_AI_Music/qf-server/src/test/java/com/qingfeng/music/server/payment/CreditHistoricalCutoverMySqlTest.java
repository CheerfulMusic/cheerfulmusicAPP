package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.LocalFinancialCohort;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcFencedCreditsLedger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** A fresh single-schema MySQL8.0.46 cohort; no production data, payment calls or activation by the tool. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditHistoricalCutoverMySqlTest {
    private static final String A="10000000-0000-0000-0000-000000000001",B="10000000-0000-0000-0000-000000000002";
    private static final Timestamp OLD=Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static CreditHistoricalCutover tool;
    private static Plan plan;
    private static DriverManagerDataSource source;
    private static long count(String sql,Object...args){return Objects.requireNonNull(db.queryForObject(sql,Long.class,args));}
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Explicit isolated historical environment required: "+key);return value;}
    @BeforeAll static void sourceInventoryAndRealCanonicalMigration() {
        String name=required("P0_HISTORICAL_CUTOVER_MYSQL_DATABASE"),url=required("P0_HISTORICAL_CUTOVER_MYSQL_URL");
        assertTrue(name.matches("p0_historical_cutover_20261113r[0-9]+"));
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+name+"(?:\\?.*)?"));
        source=new DriverManagerDataSource(url,required("P0_HISTORICAL_CUTOVER_MYSQL_USER"),required("P0_HISTORICAL_CUTOVER_MYSQL_PASSWORD"));
        var connectionProperties=new Properties();
        connectionProperties.setProperty("connectionTimeZone","UTC");
        connectionProperties.setProperty("forceConnectionTimeZoneToSession","true");
        source.setConnectionProperties(connectionProperties);
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new JdbcTransactionManager(source));
        tool=LocalFinancialCohort.historicalCutover(db,tx,name,required("P0_HISTORICAL_CUTOVER_MYSQL_UUID"));
        assertEquals("+00:00",db.queryForObject("SELECT @@session.time_zone",String.class),"Actual session UTC is required, not only a URL label");
        assertEquals(0,count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Never clear or reuse a prior cohort");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("44").load().migrate();
        db.update("INSERT INTO creator(id,created_at) VALUES(?,?),(?,?)",A,OLD,B,OLD);
        db.update("INSERT INTO credit_ledger VALUES('CREDIT',?,?,100,?),('DEBIT','old-returned',?,-30,?),('REFUND','old-returned',?,30,?),('DEBIT','old-unreturned',?,-20,?),('CREDIT','admin:approved',?,45,?)",
                "first-login:"+A,A,OLD,A,OLD,A,OLD,A,OLD,B,OLD);
        db.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,80,?),(?,45,?)",A,OLD,B,OLD);
        Snapshot core43=tool.inventory();
        assertEquals(44,core43.coreHistory().size());
        plan=CreditHistoricalCutover.dryRun(core43,new Approval(Set.of("admin:approved"),Set.of(),"synthetic-history-approval-r1"));
        assertTrue(plan.ready(),plan.issues().toString());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("45").load().migrate();
        assertEquals(45,count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL"));
        assertEquals(-2125061473,count("SELECT checksum FROM flyway_schema_history WHERE version='45'"));
        assertEquals(core43.sha256(),tool.inventory().sha256(),"V45 DDL must not rewrite source money facts");
    }
    @Test @Order(1) void dryRunIsReadOnlyAndApprovalShaIsMandatory() {
        String before=tool.inventory().sha256();
        assertThrows(IllegalStateException.class,()->tool.applyIsolated(plan,"0".repeat(64)));
        assertEquals(before,tool.inventory().sha256());assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
    }
    @Test @Order(2) void driftIsDetectedWithoutPartiallyImportingRights() {
        tx.executeWithoutResult(status->{
            db.update("UPDATE credit_account SET balance=81 WHERE owner_id=?",A);
            assertThrows(IllegalStateException.class,()->tool.applyIsolated(plan,plan.sha256()));
            status.setRollbackOnly();
        });
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));assertEquals(80,count("SELECT balance FROM credit_account WHERE owner_id=?",A));
    }
    @Test @Order(3) void outerFailureRollsBackEveryImportedRowAndEvidence() {
        tx.executeWithoutResult(status->{
            Applied result=tool.applyIsolated(plan,plan.sha256());assertFalse(result.replay());
            assertEquals(2,count("SELECT COUNT(*) FROM p0_credit_batch"));status.setRollbackOnly();
        });
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));assertEquals(0,count("SELECT COUNT(*) FROM p0_legacy_debit_mapping"));
        assertNull(db.queryForObject("SELECT evidence FROM p0_credit_cutover_state WHERE id=1",String.class));
        assertEquals(plan.source().sha256(),tool.inventory().sha256());
    }
    @Test @Order(4) void exactOpeningAndDebitMappingsImportIdempotentlyWithoutActivation() {
        Applied first=tool.applyIsolated(plan,plan.sha256());Applied duplicate=tool.applyIsolated(plan,plan.sha256());
        assertFalse(first.replay());assertTrue(duplicate.replay());assertEquals("MySQL",first.engine());
        assertEquals(125,first.journalTotal());assertEquals(2,first.owners());assertEquals(2,first.debitMappings());
        assertEquals(125,count("SELECT SUM(original-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(125,count("SELECT SUM(balance) FROM credit_account"));assertEquals(125,count("SELECT SUM(amount) FROM credit_ledger"));
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_allocation"),"Historical FIFO cannot be invented");
        assertEquals(1,count("SELECT restored FROM p0_legacy_debit_mapping WHERE reference='old-returned'"));
        assertEquals(0,count("SELECT restored FROM p0_legacy_debit_mapping WHERE reference='old-unreturned'"));
        assertEquals(2,count("SELECT COUNT(*) FROM p0_legacy_debit_mapping WHERE attribution='UNKNOWN'"));
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
        var writer=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        assertThrows(IllegalStateException.class,()->writer.debit(A,1,"must-not-consume-before-activation"));
        assertEquals(plan.source().sha256(),tool.inventory().sha256());
    }
    @Test @Order(5) void corruptedReplayProjectionFailsClosedAndDoesNotRepair() {
        tx.executeWithoutResult(status->{
            db.update("UPDATE p0_legacy_debit_mapping SET amount=21 WHERE reference='old-unreturned'");
            assertThrows(IllegalStateException.class,()->tool.applyIsolated(plan,plan.sha256()));
            assertEquals(21,count("SELECT amount FROM p0_legacy_debit_mapping WHERE reference='old-unreturned'"));status.setRollbackOnly();
        });
        assertEquals(20,count("SELECT amount FROM p0_legacy_debit_mapping WHERE reference='old-unreturned'"));
    }
    @Test @Order(6) void matchingOldCreditReplayWorksButDoesNotGrantAgainAfterExplicitTestActivation() {
        // TEST fixture action only. The cutover tool has no activation method.
        db.update("UPDATE p0_credit_cutover_state SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP(6) WHERE id=1 AND status='PREPARING'");
        var writer=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        writer.credit(A,100,"first-login:"+A);writer.credit(B,45,"admin:approved");
        assertEquals(80,writer.balance(A));assertEquals(45,writer.balance(B));
        assertEquals(2,count("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='CREDIT'"));
        assertEquals(Boolean.TRUE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,A,100,"first-login:"+A)));
        assertEquals(Boolean.FALSE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,A,99,"first-login:"+A)));
        assertEquals(Boolean.FALSE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,B,100,"first-login:"+A)));
        assertEquals(Boolean.FALSE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,A,100,"payment:unknown")));
        assertThrows(IllegalStateException.class,()->tool.applyIsolated(plan,plan.sha256()),"ACTIVE is not a valid import state");
    }
    @Test @Order(7) void returningOldConsumptionRestoresOnceAndKeepsOriginalAttributionUnknown() {
        var writer=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        writer.refund(A,"old-unreturned");writer.refund(A,"old-unreturned");writer.refund(A,"old-returned");
        assertEquals(100,writer.balance(A));assertEquals(1,count("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id='old-unreturned'"));
        assertEquals(1,count("SELECT COUNT(*) FROM p0_credit_batch WHERE owner_id=? AND source_type='LEGACY_RESTORE'",A));
        assertEquals(145,count("SELECT SUM(CAST(original AS DECIMAL(65,0))-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(145,count("SELECT SUM(amount) FROM credit_ledger"));
        assertEquals(1,count("SELECT COUNT(*) FROM credit_ledger l JOIN p0_credit_batch b ON b.owner_id=l.owner_id AND b.source_type='LEGACY_OPENING' WHERE l.owner_id=? AND l.entry_type='REFUND' AND l.reference_id='old-unreturned' AND l.created_at>b.created_at",A));
        assertEquals(Boolean.TRUE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,A,100,"first-login:"+A)),"A real subsequent refund cannot invalidate the exact historical prefix");
    }
    @Test @Order(8) void equalBoundaryAndBackdatedMissingBatchCannotPassAsHistoricalCredit() {
        var opening=db.queryForMap("SELECT reference,created_at FROM p0_credit_batch WHERE owner_id=? AND source_type='LEGACY_OPENING'",A);
        tx.executeWithoutResult(status->{
            Timestamp boundary=(Timestamp)opening.get("created_at");
            db.update("SET @qf_credit_writer_epoch=(SELECT epoch FROM p0_credit_writer_policy WHERE id=1)");
            try{
                db.update("INSERT INTO credit_ledger VALUES('CREDIT','corrupt-boundary',?,1,?)",A,boundary);
                assertFalse(CreditHistoricalCutover.isHistoricalCreditReplay(db,A,1,"corrupt-boundary"));
                db.update("INSERT INTO credit_ledger VALUES('CREDIT','corrupt-backdated',?,1,?)",A,OLD);
                assertFalse(CreditHistoricalCutover.isHistoricalCreditReplay(db,A,1,"corrupt-backdated"));
                assertFalse(CreditHistoricalCutover.isHistoricalCreditReplay(db,A,100,"first-login:"+A),"Complete prefix hash detects even an unrelated backdated row");
            }finally{db.update("SET @qf_credit_writer_epoch=NULL");status.setRollbackOnly();}
        });
        assertEquals(Boolean.TRUE,tx.execute(status->CreditHistoricalCutover.isHistoricalCreditReplay(db,A,100,"first-login:"+A)));
        assertEquals(0,count("SELECT COUNT(*) FROM credit_ledger WHERE reference_id LIKE 'corrupt-%'"));
    }
}
