package com.qingfeng.music.server.download;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.media.api.MediaApi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A single purchase grants all versions of the same work to one account. */
@Service
@Profile({"mysql", "test"})
public class DownloadEntitlements {
    public static final long PRICE = 10;
    private final JdbcTemplate jdbc;
    private final CreditsLedger ledger;

    public DownloadEntitlements(JdbcTemplate jdbc, CreditsLedger ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public boolean unlocked(String owner, String work) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=? AND owner_id=? AND amount=?", Integer.class, reference(owner, work), owner, -PRICE);
        return count != null && count > 0;
    }

    public String verifiedOwner(com.qingfeng.music.server.security.CreatorPrincipal principal) {
        if (principal == null) throw new DomainException("DOWNLOAD_LOGIN_REQUIRED", "请先登录后下载。");
        String owner = principal.id();
        Integer count = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_authing_identity WHERE creator_id=?)", Integer.class, owner, owner, owner);
        if (count == null || count == 0) throw new DomainException("DOWNLOAD_LOGIN_REQUIRED", "请先登录后下载。");
        return owner;
    }

    /** Caller must first validate the owner's or active share's access to this exact work and content. */
    public void unlock(String owner, String work, MediaApi.Content content) {
        if (unlocked(owner, work)) return;
        if (content == null || content.size() <= 0) throw unavailable();
        try (var stream = content.source().open()) {
            if (stream.read() == -1) throw unavailable();
        } catch (IOException e) {
            throw unavailable();
        }
        // The ledger serializes an owner and uniquely constrains type/reference.
        ledger.debit(owner, PRICE, reference(owner, work));
    }

    static String reference(String owner, String work) {
        if (owner == null || owner.isBlank() || work == null || work.isBlank()) throw new IllegalArgumentException("account and work required");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] a = owner.getBytes(StandardCharsets.UTF_8), b = work.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(a.length).array());
            digest.update(a);
            return "download-v1:" + HexFormat.of().formatHex(digest.digest(b));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static DomainException unavailable() { return new DomainException("AUDIO_UNAVAILABLE", "音频暂时不可用，请稍后重试。"); }
}
