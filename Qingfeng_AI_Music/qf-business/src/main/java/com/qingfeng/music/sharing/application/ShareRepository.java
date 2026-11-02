package com.qingfeng.music.sharing.application;
import java.time.Instant;
import java.util.Optional;
public interface ShareRepository {
    void insert(Snapshot share);
    Optional<Snapshot> active(String hash,Instant now);
    void revokeAll(String work);
    record Snapshot(String id,String workId,String tokenHash,String assetId,String title,String recipient,String message,String lyrics,String audioOrigin,boolean active,Instant createdAt,Instant expiresAt) {}
}
