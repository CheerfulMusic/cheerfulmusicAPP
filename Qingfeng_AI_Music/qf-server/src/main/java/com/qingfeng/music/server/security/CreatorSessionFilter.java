package com.qingfeng.music.server.security;

import com.qingfeng.music.identity.api.IdentityApi;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CreatorSessionFilter extends OncePerRequestFilter {
    private static final Pattern GIFT_FILE = Pattern.compile("/api/public/gifts/[A-Za-z0-9_-]{43}/(download|audio)");
    private static final Set<String> ATTACHMENT_VALUES = Set.of("true", "on", "yes", "1");

    private final IdentityApi identity;
    public CreatorSessionFilter(IdentityApi identity) { this.identity=identity; }
    public static String token(HttpServletRequest request) {
        if (request.getCookies()==null) return null;
        return Arrays.stream(request.getCookies()).filter(c->c.getName().equals("QF_SESSION")).map(Cookie::getValue).findFirst().orElse(null);
    }

    public static boolean isGiftDownload(HttpServletRequest request) {
        var path = GIFT_FILE.matcher(request.getServletPath());
        if (!path.matches()) return false;
        String method = request.getMethod();
        if ("download".equals(path.group(1))) return "GET".equals(method) || "POST".equals(method);
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        // Keep the attachment check aligned with Spring MVC's boolean request parameter conversion.
        String download = request.getParameter("download");
        return download != null && ATTACHMENT_VALUES.contains(download.trim().toLowerCase(Locale.ROOT));
    }

    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (request.getRequestURI().startsWith("/api/app/") || isGiftDownload(request)) identity.resolve(token(request)).ifPresent(id ->
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CreatorPrincipal(id),null,List.of(new SimpleGrantedAuthority("ROLE_CREATOR")))));
        chain.doFilter(request,response);
    }
}
