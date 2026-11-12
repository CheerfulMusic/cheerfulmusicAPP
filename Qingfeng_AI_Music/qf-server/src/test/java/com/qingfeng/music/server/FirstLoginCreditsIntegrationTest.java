package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.LogtoIdentityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:first_login_credits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.credits.enabled=false"
})
@ActiveProfiles("test")
class FirstLoginCreditsIntegrationTest {
    private static final String ISSUER = "https://login.example.test/oidc";
    @Autowired IdentityApi identity;
    @Autowired LogtoIdentityService logto;
    @Autowired CreditsLedger credits;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;

    @Test void anonymousSessionAndRecoveryDoNotGrantCredits() {
        var guest = identity.create();
        identity.recover(identity.issueRecoveryCode(guest.creatorId()));
        assertEquals(0, credits.balance(guest.creatorId()));
        assertEquals(0, grants(guest.creatorId()));
    }

    @Test void firstGoogleLoginGrants50EvenWhenCreditChargingIsDisabled() {
        var guest = identity.create();
        String subject = UUID.randomUUID().toString();
        var signed = google(guest, subject);
        assertGrant(signed.creatorId());
        var repeated = google(signed, subject);
        var otherDevice = identity.create();
        var restored = google(otherDevice, subject);
        assertEquals(repeated.creatorId(), restored.creatorId());
        assertGrant(restored.creatorId());
        assertEquals(0, credits.balance(otherDevice.creatorId()));
    }

    @Test void firstLogtoLoginGrants50AndRepeatOrNewDeviceCannotGrantAgain() {
        var guest = identity.create();
        String subject = UUID.randomUUID().toString();
        var signed = logto(guest, subject);
        assertGrant(signed.creatorId());
        var repeated = logto(signed, subject);
        var otherDevice = identity.create();
        var restored = logto(otherDevice, subject);
        assertEquals(repeated.creatorId(), restored.creatorId());
        assertGrant(restored.creatorId());
        assertEquals(0, credits.balance(otherDevice.creatorId()));
    }

    @Test void addingAnotherLoginProviderDoesNotGrantAgainInEitherDirection() {
        var googleFirst = google(identity.create(), UUID.randomUUID().toString());
        var both = logto(googleFirst, UUID.randomUUID().toString());
        assertGrant(both.creatorId());

        var logtoFirst = logto(identity.create(), UUID.randomUUID().toString());
        var bothReversed = google(logtoFirst, UUID.randomUUID().toString());
        assertGrant(bothReversed.creatorId());
    }

    @Test void existingAccountsDoNotReceiveRetroactiveGrantsOrAnotherProviderBonus() throws Exception {
        var googleGuest = identity.create();
        String googleSubject = UUID.randomUUID().toString();
        db.update("INSERT INTO creator_google_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            hash("https://accounts.google.com\0" + googleSubject), googleGuest.creatorId());
        var existingGoogle = google(googleGuest, googleSubject);
        logto(existingGoogle, UUID.randomUUID().toString());
        assertEquals(0, credits.balance(existingGoogle.creatorId()));

        var logtoGuest = identity.create();
        String logtoSubject = UUID.randomUUID().toString();
        db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            hash(ISSUER + "\0" + logtoSubject), logtoGuest.creatorId());
        var existingLogto = logto(logtoGuest, logtoSubject);
        google(existingLogto, UUID.randomUUID().toString());
        assertEquals(0, credits.balance(existingLogto.creatorId()));

        var authingGuest = identity.create();
        db.update("INSERT INTO creator_authing_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            hash(UUID.randomUUID().toString()), authingGuest.creatorId());
        google(authingGuest, UUID.randomUUID().toString());
        assertEquals(0, credits.balance(authingGuest.creatorId()));
    }

    @Test void loginAndWelcomeGrantRollbackTogetherForBothProviders() {
        for (boolean google : new boolean[] {true, false}) {
            var guest = identity.create();
            assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                if (google) google(guest, UUID.randomUUID().toString());
                else logto(guest, UUID.randomUUID().toString());
                assertGrant(guest.creatorId());
                throw new IllegalStateException("failure before login commit");
            }));
            assertEquals(0, credits.balance(guest.creatorId()));
            assertEquals(0, grants(guest.creatorId()));
            assertTrue(identity.googleIdentity(guest.creatorId()).isEmpty());
            assertTrue(logto.profile(guest.creatorId()).isEmpty());
            assertEquals(guest.creatorId(), identity.resolve(guest.token()).orElseThrow());
        }
    }

    @Test void conflictingOrInvalidLoginsDoNotGrantCredits() {
        var owner = identity.create();
        String subject = UUID.randomUUID().toString();
        google(owner, subject);
        var other = logto(identity.create(), UUID.randomUUID().toString());
        assertThrows(DomainException.class, () -> google(other, subject));
        assertGrant(other.creatorId());
        var guest = identity.create();
        assertThrows(DomainException.class, () -> google(guest, ""));
        assertThrows(DomainException.class, () -> logto(guest, ""));
        assertEquals(0, credits.balance(guest.creatorId()));
    }

    @Test void concurrentProviderCallbacksGrantOnlyOnce() throws Exception {
        var guest = identity.create();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> google = () -> attempt(start, () -> google(guest, UUID.randomUUID().toString()));
            Callable<Boolean> logto = () -> attempt(start, () -> logto(guest, UUID.randomUUID().toString()));
            var a = pool.submit(google);
            var b = pool.submit(logto);
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertGrant(guest.creatorId());
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean attempt(CountDownLatch start, Runnable signIn) throws InterruptedException {
        start.await();
        try { signIn.run(); return true; }
        catch (DomainException conflict) { return false; }
    }

    private IdentityApi.Session google(IdentityApi.Session current, String subject) {
        return identity.signInWithGoogle(current.token(), current.creatorId(), subject, null, null);
    }

    private IdentityApi.Session logto(IdentityApi.Session current, String subject) {
        return logto.signIn(current.token(), current.creatorId(), ISSUER, subject, null, null, null);
    }

    private int grants(String owner) {
        return db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND reference_id=?", Integer.class,
            owner, "first-login:" + owner);
    }

    private void assertGrant(String owner) {
        assertEquals(50, credits.balance(owner));
        assertEquals(1, grants(owner));
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
