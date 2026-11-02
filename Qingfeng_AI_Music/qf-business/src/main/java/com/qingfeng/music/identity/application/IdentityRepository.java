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
}
