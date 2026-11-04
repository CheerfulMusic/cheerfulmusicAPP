package com.qingfeng.music.sharing.api;
import com.qingfeng.music.media.api.MediaApi.Content;
import java.time.Instant;
public interface SharingApi {
    Created create(String owner,String work,String revision,String asset);
    void revokeAll(String owner,String work);
    Gift get(String token);
    Content audio(String token);
    Content cover(String token);
    record Created(String id,String token,Instant expiresAt) {}
    record Gift(String title,String recipient,String message,String lyrics,String audioOrigin,Instant createdAt,Instant expiresAt,boolean hasCover) {}
}
