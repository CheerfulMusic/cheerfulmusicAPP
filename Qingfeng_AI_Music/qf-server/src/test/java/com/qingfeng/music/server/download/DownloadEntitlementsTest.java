package com.qingfeng.music.server.download;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.media.api.MediaApi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DownloadEntitlementsTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final CreditsLedger ledger = mock(CreditsLedger.class);
    private final DownloadEntitlements service = new DownloadEntitlements(db, ledger);

    @Test void stableReferenceIsScopedToAccountAndWholeWork() {
        assertEquals(DownloadEntitlements.reference("user", "work"), DownloadEntitlements.reference("user", "work"));
        assertNotEquals(DownloadEntitlements.reference("user", "work"), DownloadEntitlements.reference("other", "work"));
        assertNotEquals(DownloadEntitlements.reference("a", "bc"), DownloadEntitlements.reference("ab", "c"));
    }

    @Test void availableAudioIsChargedWithIdempotentReference() {
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        var content = new MediaApi.Content(1, "audio/mpeg", "a.mp3", () -> new ByteArrayInputStream(new byte[]{1}));
        service.unlock("user", "work", content);
        verify(ledger).debit("user", 10, DownloadEntitlements.reference("user", "work"));
    }

    @Test void inaccessibleAudioDoesNotCharge() {
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
        var content = new MediaApi.Content(1, "audio/mpeg", "a.mp3", () -> { throw new IOException(); });
        assertThrows(DomainException.class, () -> service.unlock("user", "work", content));
        verifyNoInteractions(ledger);
    }

    @Test void unlockedWorkDoesNotChargeOrOpenAudioAgain() {
        when(db.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
        service.unlock("user", "work", null);
        verifyNoInteractions(ledger);
    }
}
