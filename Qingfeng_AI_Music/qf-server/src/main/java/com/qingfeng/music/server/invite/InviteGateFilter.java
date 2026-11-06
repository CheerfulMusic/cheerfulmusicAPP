package com.qingfeng.music.server.invite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/** 内测期间只拦截 /api 与 /media；页面、静态资源、门禁端点、支付回调与已放行会话不受影响。 */
public final class InviteGateFilter extends OncePerRequestFilter {
    private static final Set<String> ENTRANCES = Set.of(
            "/api/public/invite/status", "/api/public/invite/enter");
    private static final Set<String> WEBHOOKS = Set.of(
            "/api/public/payments/airwallex/webhook", "/api/public/paypal/webhook");
    private static final String GOOGLE_CALLBACK = "/api/public/auth/google/callback";

    private final InviteGateService gate;

    public InviteGateFilter(InviteGateService gate) { this.gate = gate; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean apiLike = path.startsWith("/api/") || path.startsWith("/media/");
        boolean webhook = "POST".equals(request.getMethod()) && WEBHOOKS.contains(path);
        if (!gate.settings().enabled() || !apiLike || ENTRANCES.contains(path) || webhook
                || gate.expiresAt(request) > 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if ("GET".equals(request.getMethod()) && GOOGLE_CALLBACK.equals(path)) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", "/account");
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"INVITE_REQUIRED\",\"message\":\"请先输入内测邀请码\"}");
    }
}
