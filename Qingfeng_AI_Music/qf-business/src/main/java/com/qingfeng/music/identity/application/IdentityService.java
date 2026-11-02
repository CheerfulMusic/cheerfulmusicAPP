package com.qingfeng.music.identity.application;

import com.qingfeng.music.identity.api.IdentityApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class IdentityService implements IdentityApi {
    private final IdentityRepository repository;
    private final SecureRandom random = new SecureRandom();
    public IdentityService(IdentityRepository repository) { this.repository = repository; }
    @Transactional
    public Session create() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        repository.create(id, hash(token), now, now.plus(30, ChronoUnit.DAYS));
        return new Session(token, id);
    }
    public Optional<String> resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return repository.findCreator(hash(token), Instant.now());
    }
    private static String hash(String token) {

        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public String issueRecoveryCode(String creator) {
        String code=randomToken();repository.setRecovery(creator,hash(code));return code;
    }
    public boolean hasRecoveryCode(String creator){return repository.hasRecovery(creator);}
    @Transactional
    public Session recover(String code) {
        if(code==null||!code.matches("[A-Za-z0-9_-]{43}"))throw invalidRecovery();
        String creator=repository.lockRecovery(hash(code)).orElseThrow(this::invalidRecovery);
        repository.setRecovery(creator,null);
        String token=randomToken();repository.addSession(creator,hash(token),Instant.now().plus(30,ChronoUnit.DAYS));
        return new Session(token,creator);
    }
    private String randomToken(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private com.qingfeng.music.common.exception.DomainException invalidRecovery(){return new com.qingfeng.music.common.exception.DomainException("INVALID_RECOVERY_CODE","找回码无效或已使用，请检查后重试");}
}
