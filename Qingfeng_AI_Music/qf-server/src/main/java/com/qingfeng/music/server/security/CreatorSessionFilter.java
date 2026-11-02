package com.qingfeng.music.server.security;

import com.qingfeng.music.identity.api.IdentityApi;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class CreatorSessionFilter extends OncePerRequestFilter {
    private final IdentityApi identity;
    public CreatorSessionFilter(IdentityApi identity) { this.identity=identity; }
    public static String token(HttpServletRequest request) {
        if (request.getCookies()==null) return null;
        return Arrays.stream(request.getCookies()).filter(c->c.getName().equals("QF_SESSION")).map(Cookie::getValue).findFirst().orElse(null);
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (request.getRequestURI().startsWith("/api/app/")) identity.resolve(token(request)).ifPresent(id ->
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new CreatorPrincipal(id),null,List.of(new SimpleGrantedAuthority("ROLE_CREATOR")))));
        chain.doFilter(request,response);
    }
}
