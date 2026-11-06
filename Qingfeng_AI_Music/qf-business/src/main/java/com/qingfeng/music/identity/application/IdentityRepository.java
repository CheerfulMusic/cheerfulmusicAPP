package com.qingfeng.music.identity.application;

import java.time.Instant;
import java.util.Optional;

public interface IdentityRepository {
    void create(String creatorId, String tokenHash, Instant createdAt, Instant expiresAt);
    Optional<String> findCreator(String tokenHash, Instant now);
    void setRecovery(String creator,String hash);
    boolean hasRecovery(String creator);
    Optional<String> lockRecovery(String hash);
    void addSession(String creator,String hash,Instant expires);
    boolean lockCreator(String creator);
    Optional<String> googleCreator(String subjectHash);
    Optional<com.qingfeng.music.identity.api.IdentityApi.GoogleIdentity> googleIdentity(String creator);
    void bindGoogle(String creator,String subjectHash,String email,String name,Instant now);
    void updateGoogle(String subjectHash,String email,String name,Instant now);
    boolean hasContent(String creator);
    boolean hasFinancialHistory(String creator);
    void moveGuestWorks(String source, String target);
    boolean hasOtherIdentity(String creator);
    void deleteSession(String tokenHash);
}
