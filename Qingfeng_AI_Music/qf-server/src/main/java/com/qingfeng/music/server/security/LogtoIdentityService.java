package com.qingfeng.music.server.security;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bridges a Logto OIDC subject to the existing creator and QF_SESSION. */
@Service
@org.springframework.context.annotation.Profile({"mysql", "test"})
public class LogtoIdentityService {
    private final JdbcTemplate db;
    private final IdentityApi identity;
    private final SecureRandom random = new SecureRandom();

    public LogtoIdentityService(JdbcTemplate db, IdentityApi identity) {
        this.db = db;
        this.identity = identity;
    }

    public record Profile(String email, String phoneMasked, String name) {}

    public Optional<Profile> profile(String creator) {
        return db.query("SELECT email,phone_masked,display_name FROM creator_oidc_identity WHERE creator_id=?",
            (rs, n) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3)), creator)
            .stream().findFirst();
    }

    @Transactional
    public IdentityApi.Session signIn(String currentToken, String expectedCreator, String issuer,
                                      String subject, String verifiedEmail, String verifiedPhone, String name) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank() || subject.length() > 255)
            throw failure("LOGTO_LOGIN_FAILED");
        // Bind only to the browser creator that initiated this verified OIDC flow.
        var locked = db.query("SELECT id FROM creator WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), expectedCreator);
        if (locked.isEmpty() || identity.resolve(currentToken).filter(expectedCreator::equals).isEmpty())
            throw failure("LOGTO_SESSION_CHANGED");

        String subjectHash = hash(issuer + "\0" + subject);
        var owner = db.query("SELECT creator_id FROM creator_oidc_identity WHERE subject_hash=?",
            (rs, n) -> rs.getString(1), subjectHash).stream().findFirst();
        String target = owner.orElse(expectedCreator);
        if (owner.isPresent() && !target.equals(expectedCreator)) {
            if (hasContent(expectedCreator) || hasAnyIdentity(expectedCreator)) throw failure("LOGTO_ACCOUNT_CONFLICT");
        } else if (owner.isEmpty()) {
            if (hasOidcIdentity(expectedCreator)) throw failure("LOGTO_ACCOUNT_CONFLICT");
            Instant now = Instant.now();
            db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,email,phone_masked,display_name,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                subjectHash, expectedCreator, trim(verifiedEmail, 320), maskedPhone(verifiedPhone), trim(name, 200),
                Timestamp.from(now), Timestamp.from(now));
        }
        db.update("UPDATE creator_oidc_identity SET email=?,phone_masked=?,display_name=?,updated_at=? WHERE subject_hash=?",
            trim(verifiedEmail, 320), maskedPhone(verifiedPhone), trim(name, 200), Timestamp.from(Instant.now()), subjectHash);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO creator_session(token_hash,creator_id,expires_at) VALUES(?,?,?)",
            hash(token), target, Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
        db.update("DELETE FROM creator_session WHERE token_hash=?", hash(currentToken));
        return new IdentityApi.Session(token, target);
    }

    private boolean hasOidcIdentity(String creator) {
        return !db.query("SELECT subject_hash FROM creator_oidc_identity WHERE creator_id=?",
            (rs, n) -> rs.getString(1), creator).isEmpty();
    }

    private boolean hasAnyIdentity(String creator) {
        return hasOidcIdentity(creator)
            || !db.query("SELECT subject_hash FROM creator_google_identity WHERE creator_id=?", (rs, n) -> rs.getString(1), creator).isEmpty()
            || !db.query("SELECT subject_hash FROM creator_authing_identity WHERE creator_id=?", (rs, n) -> rs.getString(1), creator).isEmpty();
    }

    private boolean hasContent(String creator) {
        Long count = db.queryForObject("SELECT "
            + "(SELECT COUNT(*) FROM music_work WHERE owner_id=?) + "
            + "(SELECT COUNT(*) FROM model_usage_reservation WHERE owner_id=?) + "
            + "(SELECT COUNT(*) FROM credit_ledger WHERE owner_id=?) + "
            + "(SELECT COUNT(*) FROM payment_order WHERE creator_id=?)", Long.class,
            creator, creator, creator, creator);
        return count != null && count > 0;
    }

    private static String maskedPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() < 4 ? "****" : "****" + digits.substring(digits.length() - 4);
    }

    private static String trim(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(value.length(), max));
    }

    private static DomainException failure(String code) {
        return new DomainException(code, "登录未完成，当前作品和身份已保留");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
