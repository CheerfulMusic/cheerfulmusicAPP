package com.qingfeng.music.identity.api;

import java.util.Optional;

public interface IdentityApi {
    /** 明文令牌仅交给HttpOnly Cookie，不写入日志或数据库。 */
    Session create();
    Optional<String> resolve(String token);
    void logout(String token);
    String issueRecoveryCode(String creatorId);
    boolean hasRecoveryCode(String creatorId);
    Session recover(String code);
    Optional<GoogleIdentity> googleIdentity(String creatorId);
    Session signInWithGoogle(String currentToken, String expectedCreator, String subject, String email, String name);
    /** Internal login hook: move only the authenticated browser guest collection to a verified account. */
    boolean claimGuestWorks(String currentToken, String sourceCreator, String targetCreator);
    record GoogleIdentity(String subjectHash, String email, String name) {}
    record Session(String token, String creatorId) {}
}
