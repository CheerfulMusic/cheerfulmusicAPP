package com.qingfeng.music.server.payment;

import com.qingfeng.music.infrastructure.persistence.credits.LocalFinancialCohort;
import com.qingfeng.music.infrastructure.persistence.credits.*;
import com.qingfeng.music.infrastructure.persistence.credits.CreditHistoricalCutover.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real, dedicated MySQL8/InnoDB cohort. All owners/orders are synthetic; no provider calls. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditCarryForwardCutoverMySqlTest {
    private static final String A="10000000-0000-0000-0000-000000000001", B="10000000-0000-0000-0000-000000000002";
    private static final String ORDER="10000000-0000-0000-0000-000000000010";
    private static final Timestamp OLD=Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
    private static DriverManagerDataSource source;
    private static JdbcTemplate db;
    private static TransactionTemplate tx;
    private static CreditHistoricalCutover tool;
    private static Plan plan;
    private static OperationalApproval approval(Plan value) {
        return new OperationalApproval(value.source().identity(),value.source().sha256(),value.sha256(),"a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64));
    }
    private static String required(String key) { String value=System.getenv("P0_CARRY_FORWARD_MYSQL_"+key);assertNotNull(value,"Dedicated isolated carry-forward resource required");return value; }
    private static long count(String sql,Object...args) { return Objects.requireNonNull(db.queryForObject(sql,Long.class,args)); }
    @BeforeAll static void prepareEmptyIsolatedCohort() {
        String name=required("DATABASE"),url=required("URL");
        assertTrue(name.matches("p0_historical_cutover_20261113r[0-9]+"));
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:23316/"+name+"(?:\\?.*)?"));
        source=new DriverManagerDataSource(url,required("USER"),required("PASSWORD"));
        var properties=new Properties();properties.setProperty("connectionTimeZone","UTC");properties.setProperty("forceConnectionTimeZoneToSession","true");source.setConnectionProperties(properties);
        db=new JdbcTemplate(source);tx=new TransactionTemplate(new JdbcTransactionManager(source));
        tool=LocalFinancialCohort.historicalCutover(db,tx,name,required("UUID"));
        assertEquals(0,count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()"),"Never clear/reuse a previous cohort");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("44").load().migrate();
        db.update("INSERT INTO creator(id,created_at) VALUES(?,?),(?,?)",A,OLD,B,OLD);
        db.update("INSERT INTO payment_order(id,creator_id,package_id,amount,currency,credits,provider,status,provider_capture_id,provider_link_id,created_at) VALUES(?,?,'historical-snapshot',9.90,'USD',100,'PAYPAL_LIVE','PAID','old-capture','old-link',?)",ORDER,A,OLD);
        db.update("INSERT INTO credit_ledger VALUES('CREDIT',?,?,100,?),('DEBIT','returned',?,-20,?),('REFUND','returned',?,20,?),('DEBIT','unreturned',?,-30,?),('CREDIT','admin:unattributed',?,45,?)",
                "payment:"+ORDER,A,OLD,A,OLD,A,OLD,A,OLD,B,OLD);
        // The missing B cache row is a supported old lazy cache, not zero rights.
        db.update("INSERT INTO credit_account(owner_id,balance,updated_at) VALUES(?,70,?)",A,OLD);
        var snapshot=tool.inventory();
        plan=CreditHistoricalCutover.carryForwardPlan(snapshot,"synthetic-preserve-existing-balances-v1");
        assertTrue(plan.ready(),plan.issues().toString());
        assertFalse(CreditHistoricalCutover.dryRun(snapshot,plan.approval()).ready());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).target("45").load().migrate();
        assertEquals(snapshot.sha256(),tool.inventory().sha256());
    }
    @Test @org.junit.jupiter.api.Order(1) void readOnlyPlanKeepsPaidOrderAndMissingCacheUnchanged() {
        assertDoesNotThrow(()->CreditCarryForwardCommand.verifyJdbcClock(db));
        assertEquals(115,plan.openings().stream().mapToLong(Opening::remaining).sum());
        assertEquals(2,plan.debits().size());assertEquals("PAID",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,ORDER));
        assertEquals(0,count("SELECT COUNT(*) FROM credit_account WHERE owner_id=?",B));
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));
        assertEquals(plan.source().sha256(),tool.inventory().sha256());
    }
    @Test @org.junit.jupiter.api.Order(2) void wrongApprovalAndSourceDriftFailWithoutPartialImport() {
        var wrong=new OperationalApproval(plan.source().identity(),plan.source().sha256(),"0".repeat(64),"a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64));
        assertThrows(IllegalStateException.class,()->tool.applyCarryForward(plan,wrong));
        tx.executeWithoutResult(status->{
            db.update("UPDATE credit_account SET balance=71 WHERE owner_id=?",A);
            assertThrows(IllegalStateException.class,()->tool.applyCarryForward(plan,approval(plan)));status.setRollbackOnly();
        });
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));assertEquals(70,count("SELECT balance FROM credit_account WHERE owner_id=?",A));
    }
    @Test @org.junit.jupiter.api.Order(3) void futureHistoricalOrderCannotCrossNewOrderBoundary() {
        tx.executeWithoutResult(status->{
            db.update("UPDATE payment_order SET created_at=? WHERE id=?",Timestamp.from(Instant.parse("2030-01-01T00:00:00Z")),ORDER);
            Plan future=CreditHistoricalCutover.carryForwardPlan(tool.inventory(),"synthetic-preserve-existing-balances-v1");
            var failure=assertThrows(IllegalStateException.class,()->tool.applyCarryForward(future,approval(future)));
            assertEquals("HISTORY_ORDER_TIME_BOUNDARY_NOT_STRICT",failure.getMessage());status.setRollbackOnly();
        });
        assertEquals(plan.source().sha256(),tool.inventory().sha256());assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));
    }
    @Test @org.junit.jupiter.api.Order(4) void anotherIdleWriterConnectionBlocksApply() throws Exception {
        try(var other=source.getConnection()) {
            assertFalse(other.isClosed());
            var failure=assertThrows(IllegalStateException.class,()->tool.applyCarryForward(plan,approval(plan)));
            assertEquals("CARRY_FORWARD_OTHER_DATABASE_CONNECTIONS_PRESENT",failure.getMessage());
        }
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));
    }
    @Test @org.junit.jupiter.api.Order(5) void importIsAtomicIdempotentAndDoesNotActivateOrRewriteHistory() {
        tx.executeWithoutResult(status->{tool.applyCarryForward(plan,approval(plan));status.setRollbackOnly();});
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_batch"));assertEquals(0,count("SELECT COUNT(*) FROM p0_legacy_debit_mapping"));
        assertFalse(tool.applyCarryForward(plan,approval(plan)).replay());assertTrue(tool.applyCarryForward(plan,approval(plan)).replay());
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
        assertEquals(115,count("SELECT SUM(original-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(115,count("SELECT SUM(amount) FROM credit_ledger"));
        assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_allocation"));assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_payment_binding"));
        assertEquals(plan.source().sha256(),tool.inventory().sha256());
    }
    @Test @org.junit.jupiter.api.Order(6) void activationSeparatelyVerifiesOtherConnectionsAndOriginalSource() throws Exception {
        try(var other=source.getConnection()) {
            assertFalse(other.isClosed());assertThrows(IllegalStateException.class,()->tool.activateCarryForward(plan,approval(plan)));
        }
        assertEquals("PREPARING",db.queryForObject("SELECT status FROM p0_credit_cutover_state WHERE id=1",String.class));
        assertEquals("ACTIVE",tool.activateCarryForward(plan,approval(plan)).state());
        assertTrue(tool.activateCarryForward(plan,approval(plan)).replay());
        Timestamp activated=db.queryForObject("SELECT activated_at FROM p0_credit_cutover_state WHERE id=1",Timestamp.class);
        assertNotNull(activated);assertTrue(OLD.toInstant().isBefore(activated.toInstant()));
        assertEquals("ACTIVE",tool.verifyCarryForward(plan,approval(plan)).state());
        assertEquals(plan.source().sha256(),tool.inventory().sha256());
    }
    @Test @org.junit.jupiter.api.Order(7) void oldWriterCannotGrantCreditsAndPaidOrderCannotBeRegranted() {
        var old=new JdbcCreditsLedger(db,tx);assertThrows(RuntimeException.class,()->old.credit(A,1,"old-writer-bypass"));
        assertEquals(0,count("SELECT COUNT(*) FROM credit_ledger WHERE reference_id='old-writer-bypass'"));
        var writer=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        assertThrows(IllegalStateException.class,()->writer.credit(A,100,"payment:"+ORDER));
        assertEquals(70,writer.balance(A));assertEquals(0,count("SELECT COUNT(*) FROM p0_credit_payment_binding"));
    }
    @Test @org.junit.jupiter.api.Order(8) void normalConsumptionAndInFlightLegacyReturnKeepAllBalancesConserved() {
        var writer=new JdbcFencedCreditsLedger(new JdbcCreditsLedger(db,tx),db,tx);
        writer.debit(A,25,"post-cutover-consume");writer.debit(A,25,"post-cutover-consume");
        writer.refund(A,"unreturned");writer.refund(A,"unreturned");writer.refund(A,"returned");
        writer.credit(B,45,"admin:unattributed");
        assertEquals(75,writer.balance(A));assertEquals(45,writer.balance(B));
        assertEquals(120,count("SELECT SUM(balance) FROM credit_account"));
        assertEquals(120,count("SELECT SUM(amount) FROM credit_ledger"));
        assertEquals(120,count("SELECT SUM(CAST(original AS DECIMAL(65,0))-consumed-adjusted) FROM p0_credit_batch"));
        assertEquals(1,count("SELECT COUNT(*) FROM p0_credit_allocation WHERE debit_reference='post-cutover-consume'"));
        assertEquals(1,count("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND' AND reference_id='unreturned'"));
        assertEquals("PAID",db.queryForObject("SELECT status FROM payment_order WHERE id=?",String.class,ORDER));
        assertEquals(new java.math.BigDecimal("9.90"),db.queryForObject("SELECT amount FROM payment_order WHERE id=?",java.math.BigDecimal.class,ORDER));
        assertThrows(IllegalStateException.class,()->tool.applyCarryForward(plan,approval(plan)),"An activated source with real new writes is never reimported");
        assertThrows(IllegalStateException.class,()->tool.activateCarryForward(plan,approval(plan)));
    }
    @Test @org.junit.jupiter.api.Order(9) void actualUtcSessionWithWrongJdbcDecoderIsRejectedBeforeInventory() {
        var badSource=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:23316/"+required("DATABASE")+"?useSSL=false&allowPublicKeyRetrieval=true",required("USER"),required("PASSWORD"));
        var driver=new Properties();
        driver.setProperty("connectionTimeZone","Asia/Shanghai");
        driver.setProperty("forceConnectionTimeZoneToSession","false");
        driver.setProperty("sessionVariables","time_zone='+00:00'");
        badSource.setConnectionProperties(driver);
        var badDb=new JdbcTemplate(badSource);
        assertEquals("+00:00",badDb.queryForObject("SELECT @@session.time_zone",String.class),"The connection really is UTC; only checking that label would miss this error");
        long journalBefore=count("SELECT SUM(amount) FROM credit_ledger");
        var failure=assertThrows(IllegalStateException.class,()->CreditCarryForwardCommand.verifyJdbcClock(badDb));
        assertEquals("CARRY_FORWARD_JDBC_TIMEZONE_DECODE_MISMATCH",failure.getMessage());
        assertEquals(journalBefore,count("SELECT SUM(amount) FROM credit_ledger"));
        assertDoesNotThrow(()->CreditCarryForwardCommand.verifyJdbcClock(db));
    }
}
