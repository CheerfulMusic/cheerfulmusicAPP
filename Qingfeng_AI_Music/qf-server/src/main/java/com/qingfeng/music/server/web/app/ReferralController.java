package com.qingfeng.music.server.web.app;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.credits.ReferralRewards;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.security.CreatorPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"}) @RequestMapping("/api/app/referrals")
public class ReferralController {
    private final JdbcReferralRewards referrals;
    public ReferralController(JdbcReferralRewards referrals) { this.referrals = referrals; }
    public record Invite(String code) {}
    @GetMapping public Map<String,String> code(@AuthenticationPrincipal CreatorPrincipal creator) {
        return Map.of("code", referrals.code(creator.id()));
    }
    @PostMapping("/accept") public Map<String,Boolean> accept(@AuthenticationPrincipal CreatorPrincipal creator, @RequestBody Invite invite) {
        referrals.accept(creator.id(), invite.code());
        return Map.of("accepted", true);
    }
}

@Service @Profile({"mysql","test"})
class JdbcReferralRewards implements ReferralRewards {
    private final JdbcTemplate db;
    private final CreditsLedger credits;
    JdbcReferralRewards(JdbcTemplate db, CreditsLedger credits) { this.db = db; this.credits = credits; }
    String code(String creator) {
        if (!verified(creator)) throw new DomainException("REFERRAL_LOGIN_REQUIRED", "请先登录账号，再分享邀请链接");
        return creator;
    }
    @Transactional void accept(String invitee, String code) {
        if (code == null || !code.matches("[0-9a-fA-F-]{36}")) throw invalid();
        String inviter;
        try { inviter = UUID.fromString(code).toString(); } catch (IllegalArgumentException e) { throw invalid(); }
        db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, invitee);
        if (invitee.equals(inviter) || verified(invitee) || !verified(inviter)) throw invalid();
        var existing = db.query("SELECT inviter_id FROM referral_pending WHERE invitee_id=?", (rs,n)->rs.getString(1), invitee);
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(inviter)) throw new DomainException("REFERRAL_ALREADY_SET", "此设备已有邀请关系");
            return;
        }
        db.update("INSERT INTO referral_pending(invitee_id,inviter_id,created_at) VALUES(?,?,CURRENT_TIMESTAMP)", invitee, inviter);
    }
    public void rewardForFirstLogin(String invitee) {
        var inviters = db.query("SELECT inviter_id FROM referral_pending WHERE invitee_id=?", (rs,n)->rs.getString(1), invitee);
        if (!inviters.isEmpty()) credits.credit(inviters.get(0), 20, "invite:" + invitee);
    }
    private boolean verified(String creator) {
        Integer count = db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)", Integer.class, creator, creator);
        return count != null && count > 0;
    }
    private DomainException invalid() { return new DomainException("INVALID_REFERRAL", "邀请链接无效或当前账号不符合领取条件"); }
}
