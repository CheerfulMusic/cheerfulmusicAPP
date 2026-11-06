package com.qingfeng.music.server.invite;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 内测门禁的两个公开端点：查询状态（含可写操作所需的 CSRF 令牌）与提交邀请码。 */
@RestController
@RequestMapping("/api/public/invite")
public class InviteGateController {
    public record Status(boolean enabled, boolean admitted, String csrfToken, String expiresAt) {}
    public record Invitation(String code) {}

    private final InviteGateService gate;

    public InviteGateController(InviteGateService gate) { this.gate = gate; }

    @GetMapping("/status")
    public ResponseEntity<Status> status(HttpServletRequest request, CsrfToken csrf) {
        long expiry = gate.expiresAt(request);
        boolean enabled = gate.settings().enabled();
        boolean admitted = !enabled || expiry > 0;
        String token = csrf == null ? "" : csrf.getToken();
        String expiresAt = expiry > 0 ? Instant.ofEpochSecond(expiry).toString() : "";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Status(enabled, admitted, token, expiresAt));
    }

    @PostMapping("/enter")
    public ResponseEntity<?> enter(HttpServletRequest request,
                                   @RequestBody(required = false) Invitation invitation) {
        if (!gate.settings().enabled()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("admitted", true));
        }
        if (!gate.allowAttempt(request.getRemoteAddr())) {
            return ResponseEntity.status(429).cacheControl(CacheControl.noStore())
                    .header("Retry-After", "60")
                    .body(Map.of("code", "INVITE_RATE_LIMITED", "message", "尝试次数较多，请一分钟后再试。"));
        }
        String cookie = gate.admit(invitation == null ? null : invitation.code());
        if (cookie == null) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                    .body(Map.of("code", "INVITE_INVALID", "message", "邀请码不正确，请检查后重试。"));
        }
        var access = ResponseCookie.from(InviteGateService.COOKIE, cookie)
                .httpOnly(true)
                .secure(gate.settings().secureCookie() || request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(gate.settings().sessionTtl())
                .build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, access.toString())
                .body(Map.of("admitted", true));
    }
}
