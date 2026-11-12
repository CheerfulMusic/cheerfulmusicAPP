package com.qingfeng.music.server.invite;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/** 内测期间只拦截 /api 与 /media；页面、静态资源、门禁端点、支付回调与已放行会话不受影响。 */
public final class InviteGateFilter extends OncePerRequestFilter {
    private static final Set<String> ENTRANCES = Set.of(
            "/api/public/invite/status", "/api/public/invite/enter");
    private static final Set<String> WEBHOOKS = Set.of(
            "/api/public/payments/airwallex/webhook", "/api/public/paypal/webhook", "/api/public/auth/sms/deliver");
    private static final Set<String> ANALYTICS = Set.of(
            "/api/public/events", "/api/app/analytics/access", "/api/app/analytics/summary");
    private static final String GOOGLE_CALLBACK = "/api/public/auth/google/callback";
    private static final String PUBLIC_ID = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
    private static final Pattern ARTIST_READ = Pattern.compile("/api/public/artists/" + PUBLIC_ID
            + "(?:/(?:avatar|cover)/" + PUBLIC_ID + "|/songs/" + PUBLIC_ID + "/[1-9][0-9]{0,18}/(?:audio|cover))?");

    private final InviteGateService gate;

    public InviteGateFilter(InviteGateService gate) { this.gate = gate; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        boolean apiLike = path.startsWith("/api/") || path.startsWith("/media/");
        boolean webhook = "POST".equals(request.getMethod()) && WEBHOOKS.contains(path);
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        boolean publicGift = read && path.matches("/api/public/gifts/[A-Za-z0-9_-]{43}(?:/audio|/cover)?");
        boolean publicArtist = read && ARTIST_READ.matcher(path).matches();
        boolean analytics = ANALYTICS.contains(path);
        // 热歌推荐是首页公开展示，播放计次也不含个人信息；内测门禁不应让它变成 0。
        boolean hotSong = ("GET".equals(request.getMethod()) && "/api/public/hot-songs".equals(path))
                || ("POST".equals(request.getMethod()) && path.matches("/api/public/hot-songs/[A-Za-z0-9_-]{1,64}/play"));
        if (!gate.settings().enabled() || !apiLike || publicGift || publicArtist || ENTRANCES.contains(path) || analytics || hotSong || webhook
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
