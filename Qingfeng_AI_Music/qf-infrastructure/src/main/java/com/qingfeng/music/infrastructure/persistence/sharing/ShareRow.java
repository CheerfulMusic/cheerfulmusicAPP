package com.qingfeng.music.infrastructure.persistence.sharing;
import java.time.Instant;
public class ShareRow {
    public String id;
    public String workId;
    public String tokenHash;
    public String assetId;
    public String title;
    public String recipient;
    public String message;
    public String lyrics;
    public String audioOrigin;
    public boolean active;
    public Instant createdAt;
    public Instant expiresAt;
}
