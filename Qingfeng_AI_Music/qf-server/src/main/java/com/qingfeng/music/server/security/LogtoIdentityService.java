package com.qingfeng.music.server.security;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.credits.FirstLoginCredits;
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
    private final FirstLoginCredits firstLoginCredits;
    @org.springframework.beans.factory.annotation.Autowired
    private com.qingfeng.music.credits.CreditsLedger credits;
    private final SecureRandom random = new SecureRandom();

    public LogtoIdentityService(JdbcTemplate db, IdentityApi identity, FirstLoginCredits firstLoginCredits) {
        this.db = db;
        this.identity = identity;
        this.firstLoginCredits = firstLoginCredits;
    }

    public record Profile(String email, String phoneMasked, String name) {}

    public Optional<Profile> profile(String creator) {
        return db.query("SELECT email,COALESCE((SELECT phone_masked FROM creator_phone_binding WHERE creator_id=i.creator_id),phone_masked),display_name FROM creator_oidc_identity i WHERE creator_id=? UNION SELECT g.email,b.phone_masked,g.display_name FROM creator_phone_binding b JOIN creator_google_identity g ON g.creator_id=b.creator_id WHERE b.creator_id=? AND NOT EXISTS (SELECT 1 FROM creator_oidc_identity WHERE creator_id=b.creator_id)",
            (rs, n) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3)), creator, creator)
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
        if(owner.isEmpty()) owner=db.query("SELECT creator_id FROM creator_phone_binding WHERE subject_hash=?",(rs,n)->rs.getString(1),subjectHash).stream().findFirst();
        String target = owner.orElse(expectedCreator);
        if (owner.isPresent() && !target.equals(expectedCreator)) {
            if (!identity.claimGuestWorks(currentToken,expectedCreator,target)) throw failure("LOGTO_ACCOUNT_CONFLICT");
        } else if (owner.isEmpty()) {
            if (hasOidcIdentity(expectedCreator)) throw failure("LOGTO_ACCOUNT_CONFLICT");
            boolean firstLogin = !hasAnyIdentity(expectedCreator);
            Instant now = Instant.now();
            db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,email,phone_masked,display_name,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                subjectHash, expectedCreator, trim(verifiedEmail, 320), maskedPhone(verifiedPhone), trim(name, 200),
                Timestamp.from(now), Timestamp.from(now));
            if (firstLogin) firstLoginCredits.grant(expectedCreator);
        }
        db.update("UPDATE creator_oidc_identity SET email=?,phone_masked=?,display_name=?,updated_at=? WHERE subject_hash=?",
            trim(verifiedEmail, 320), maskedPhone(verifiedPhone), trim(name, 200), Timestamp.from(Instant.now()), subjectHash);

        // Both SMS login and binding reserve the same subject key, closing concurrent claim races.
        if (verifiedPhone != null && !verifiedPhone.isBlank()) {
            var phoneOwner=db.query("SELECT creator_id FROM creator_phone_binding WHERE subject_hash=?",(rs,n)->rs.getString(1),subjectHash);
            if(phoneOwner.isEmpty()) {
                var bound=db.query("SELECT subject_hash FROM creator_phone_binding WHERE creator_id=?",(rs,n)->rs.getString(1),target);
                if(bound.isEmpty()) {
                    try {db.update("INSERT INTO creator_phone_binding(subject_hash,creator_id,phone_masked) VALUES(?,?,?)",subjectHash,target,maskedPhone(verifiedPhone));}
                    catch(org.springframework.dao.DuplicateKeyException conflict){throw failure("LOGTO_ACCOUNT_CONFLICT");}
                }
            } else if(!phoneOwner.get(0).equals(target)) throw failure("LOGTO_ACCOUNT_CONFLICT");
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO creator_session(token_hash,creator_id,expires_at) VALUES(?,?,?)",
            hash(token), target, Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
        db.update("DELETE FROM creator_session WHERE token_hash=?", hash(currentToken));
        return new IdentityApi.Session(token, target);
    }

    public boolean canBindPhone(String creator) { return hasAnyIdentity(creator); }

    @Transactional
    public void bindPhone(String token,String creator,String issuer,String subject,String phone) {
        db.query("SELECT id FROM creator WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),creator);
        if(identity.resolve(token).filter(creator::equals).isEmpty()||!canBindPhone(creator))throw failure("LOGTO_SESSION_CHANGED");
        String key=hash(issuer+"\0"+subject);
        var owners=db.query("SELECT creator_id FROM creator_oidc_identity WHERE subject_hash=? UNION SELECT creator_id FROM creator_phone_binding WHERE subject_hash=?",(rs,n)->rs.getString(1),key,key);
        if(owners.stream().anyMatch(owner->!owner.equals(creator)))throw failure("LOGTO_ACCOUNT_CONFLICT");
        var existing=db.query("SELECT subject_hash FROM creator_phone_binding WHERE creator_id=?",(rs,n)->rs.getString(1),creator);
        if(!existing.isEmpty()){if(!existing.get(0).equals(key))throw failure("LOGTO_ACCOUNT_CONFLICT");return;}
        if(profile(creator).map(Profile::phoneMasked).isPresent())throw failure("LOGTO_ACCOUNT_CONFLICT");
        try{db.update("INSERT INTO creator_phone_binding(subject_hash,creator_id,phone_masked) VALUES(?,?,?)",key,creator,maskedPhone(phone));}
        catch(org.springframework.dao.DuplicateKeyException conflict){throw failure("LOGTO_ACCOUNT_CONFLICT");}
        credits.credit(creator,50,"phone-binding:"+creator);
    }

    /** Call only after the provider has verified this email and completed password reset. */
    @Transactional
    public void revokeSessionsForVerifiedEmail(String email) {
        var owners=db.query("SELECT creator_id FROM creator_oidc_identity WHERE LOWER(email)=LOWER(?)",(rs,n)->rs.getString(1),email);
        for(String owner:owners.stream().distinct().sorted().toList()) {
            db.query("SELECT id FROM creator WHERE id=? FOR UPDATE",(rs,n)->rs.getString(1),owner);
            db.update("DELETE FROM creator_session WHERE creator_id=?",owner);
        }
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
