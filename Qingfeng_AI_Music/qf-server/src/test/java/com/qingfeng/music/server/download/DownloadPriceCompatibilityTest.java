package com.qingfeng.music.server.download;

import com.qingfeng.music.credits.CreditsLedger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadPriceCompatibilityTest {
    @Test void oldAndNewPricesPreserveOnlyTheCorrectAccountAndWork() {
        var db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:download_price_compat;DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE credit_ledger(entry_type VARCHAR(20), reference_id VARCHAR(100), owner_id VARCHAR(100), amount BIGINT)");
        var ledger = mock(CreditsLedger.class);
        var service = new DownloadEntitlements(db, ledger);
        String oldRef = DownloadEntitlements.reference("alice", "old-work");
        String newRef = DownloadEntitlements.reference("alice", "new-work");
        db.update("INSERT INTO credit_ledger VALUES ('DEBIT', ?, 'alice', -10)", oldRef);
        db.update("INSERT INTO credit_ledger VALUES ('DEBIT', ?, 'alice', -15)", newRef);
        assertTrue(service.unlocked("alice", "old-work"));
        assertTrue(service.unlocked("alice", "new-work"));
        assertFalse(service.unlocked("bob", "old-work"));
        assertFalse(service.unlocked("alice", "other-work"));
        service.unlock("alice", "old-work", null);
        service.unlock("alice", "new-work", null);
        verifyNoInteractions(ledger);

        String invalidRef = DownloadEntitlements.reference("alice", "invalid");
        db.update("INSERT INTO credit_ledger VALUES ('DEBIT', ?, 'alice', -1)", invalidRef);
        db.update("INSERT INTO credit_ledger VALUES ('REFUND', ?, 'alice', -10)", invalidRef);
        assertFalse(service.unlocked("alice", "invalid"));
        assertEquals(10, DownloadEntitlements.PRICE);
    }
}
