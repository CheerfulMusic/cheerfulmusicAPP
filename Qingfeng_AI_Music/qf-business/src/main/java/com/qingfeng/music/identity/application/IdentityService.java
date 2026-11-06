package com.qingfeng.music.identity.application;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.credits.FirstLoginCredits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class IdentityService implements IdentityApi {
    private final IdentityRepository repository;
    private final FirstLoginCredits firstLoginCredits;
    private final SecureRandom random = new SecureRandom();
    public IdentityService(IdentityRepository repository, FirstLoginCredits firstLoginCredits) {
        this.repository = repository;
        this.firstLoginCredits = firstLoginCredits;
    }
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
    @Transactional
    public void logout(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return;
        resolve(token).ifPresent(repository::lockCreator);
        repository.deleteSession(hash(token));
    }
    private static String hash(String token) {

        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Transactional
    public String issueRecoveryCode(String creator) {
        if(!repository.lockCreator(creator)||!repository.hasActiveSessions(creator))throw new com.qingfeng.music.common.exception.DomainException("CREATOR_SESSION_CHANGED","登录身份已改变，请刷新后重试");
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
    public Optional<GoogleIdentity> googleIdentity(String creatorId){return repository.googleIdentity(creatorId);}
    @Transactional
    public Session signInWithGoogle(String currentToken,String expectedCreator,String subject,String email,String name){
        if(subject==null||subject.isBlank()||subject.length()>255||!subject.chars().allMatch(c->c>=33&&c<=126))throw googleFailure("GOOGLE_LOGIN_FAILED");
        if(expectedCreator==null||!repository.lockCreator(expectedCreator)||resolve(currentToken).filter(expectedCreator::equals).isEmpty())throw googleFailure("GOOGLE_SESSION_CHANGED");
        // Hashing the issuer/sub key preserves case sensitivity under MySQL's default collation.
        String subjectHash=hash("https://accounts.google.com\u0000"+subject);
        var owner=repository.googleCreator(subjectHash);
        String target=expectedCreator;
        if(owner.isPresent()){
            target=owner.get();
            if(!target.equals(expectedCreator)&&!claimGuestWorks(currentToken,expectedCreator,target))throw googleFailure("GOOGLE_ACCOUNT_CONFLICT");
        }else{
            if(repository.googleIdentity(expectedCreator).isPresent())throw googleFailure("GOOGLE_ACCOUNT_CONFLICT");
            boolean firstLogin = !repository.hasOtherIdentity(expectedCreator);
            repository.bindGoogle(expectedCreator,subjectHash,profile(email,320),profile(name,200),Instant.now());
            if(firstLogin)firstLoginCredits.grant(expectedCreator);
        }
        repository.updateGoogle(subjectHash,profile(email,320),profile(name,200),Instant.now());
        String token=randomToken();repository.addSession(target,hash(token),Instant.now().plus(30,ChronoUnit.DAYS));
        repository.deleteSession(hash(currentToken));
        return new Session(token,target);
    }
    @Transactional
    public boolean claimGuestWorks(String currentToken,String source,String target) {
        if(source==null||target==null||!repository.lockCreator(source)
            ||resolve(currentToken).filter(source::equals).isEmpty())return false;
        if(source.equals(target))return true;
        if(repository.googleIdentity(source).isPresent()||repository.hasOtherIdentity(source)||repository.hasFinancialHistory(source))return false;
        if(!repository.lockCreator(target))return false;
        repository.moveGuestWorks(source,target);
        return true;
    }

    private String profile(String value,int limit){return value==null?null:value.substring(0,Math.min(value.length(),limit));}
    private com.qingfeng.music.common.exception.DomainException googleFailure(String code){return new com.qingfeng.music.common.exception.DomainException(code,"Google登录未完成，当前作品和身份已保留");}
    private com.qingfeng.music.common.exception.DomainException invalidRecovery(){return new com.qingfeng.music.common.exception.DomainException("INVALID_RECOVERY_CODE","找回码无效或已使用，请检查后重试");}
}
