package com.qingfeng.music.server.invite;

import com.qingfeng.music.server.security.ApiRateLimitFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** 校验邀请码并签发带 HMAC 签名的临时通行证 Cookie；明文邀请码不写入浏览器。 */
public final class InviteGateService {
    public static final String COOKIE = "QF_BETA_ACCESS";
    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";

    private final InviteGateSettings settings;
    private final Clock clock;
    private final List<String> tags;
    private final SecureRandom random = new SecureRandom();
    private final ApiRateLimitFilter attempts;

    public InviteGateService(InviteGateSettings settings) { this(settings, Clock.systemUTC()); }

    public InviteGateService(InviteGateSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.attempts = new ApiRateLimitFilter(clock);
        this.tags = settings.enabled() ? settings.codes().stream().map(this::tag).toList() : List.of();
    }

    public InviteGateSettings settings() { return settings; }

    public boolean allowAttempt(String remoteAddress) {
        return attempts.allow("invite:" + remoteAddress, settings.attemptsPerMinute());
    }

    /** 返回通行证到期的 epoch 秒；签名无效、已过期或门禁关闭时返回 0。 */
    public long expiresAt(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || !settings.enabled()) return 0;
        long now = clock.instant().getEpochSecond();
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE.equals(cookie.getName()))
                .mapToLong(cookie -> validUntil(cookie.getValue()))
                .filter(expiry -> expiry > now)
                .findFirst()
                .orElse(0L);
    }

    /** 校验成功返回待写入的 Cookie 值，失败返回 null。比较过程不提前退出。 */
    public String admit(String code) {
        if (!settings.enabled() || code == null) return null;
        byte[] candidate = tag(code.trim()).getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String tag : tags) {
            matched |= MessageDigest.isEqual(tag.getBytes(StandardCharsets.UTF_8), candidate);
        }
        if (!matched) return null;
        long expiry = clock.instant().plus(settings.sessionTtl()).getEpochSecond();
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        String payload = VERSION + "." + expiry + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }

    private long validUntil(String value) {
        if (value == null) return 0;
        String[] parts = value.split("\\.", -1);
        if (parts.length < 3 || !VERSION.equals(parts[0])) return 0;
        String signature = parts[parts.length - 1];
        String payload = String.join(".", Arrays.copyOf(parts, parts.length - 1));
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException invalid) {
            return 0;
        }
        if (!MessageDigest.isEqual(mac(payload), actual)) return 0;
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private String tag(String code) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac("code:" + code));
    }

    private byte[] mac(String input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(settings.signingKey().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Invite gate signing is unavailable", unavailable);
        }
    }
}
