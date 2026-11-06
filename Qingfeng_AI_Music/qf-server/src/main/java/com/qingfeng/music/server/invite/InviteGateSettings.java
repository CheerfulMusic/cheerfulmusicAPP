package com.qingfeng.music.server.invite;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** 内测邀请码门禁配置。启用时强制要求私有邀请码与签名密钥，避免误把全站开放成内测态。 */
public record InviteGateSettings(
        boolean enabled,
        List<String> codes,
        String signingKey,
        Duration sessionTtl,
        int attemptsPerMinute,
        boolean secureCookie) {

    public InviteGateSettings {
        codes = codes == null ? List.of() : codes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (sessionTtl == null) sessionTtl = Duration.ofDays(7);
        if (sessionTtl.compareTo(Duration.ofMinutes(1)) < 0
                || sessionTtl.compareTo(Duration.ofDays(30)) > 0
                || attemptsPerMinute < 1 || attemptsPerMinute > 100) {
            throw new IllegalArgumentException("Invalid beta access lifetime or attempt limit");
        }
        if (enabled && (codes.isEmpty() || codes.stream().anyMatch(code -> code.length() < 12 || code.length() > 128) || signingKey == null || signingKey.length() < 32)) {
            throw new IllegalArgumentException(
                    "Enabled beta access requires private invitation codes and a signing key of at least 32 characters");
        }
    }
}
