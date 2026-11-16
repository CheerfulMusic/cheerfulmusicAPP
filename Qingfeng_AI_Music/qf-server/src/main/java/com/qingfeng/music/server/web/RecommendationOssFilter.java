package com.qingfeng.music.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed after the existing security and invite filters; all other requests remain unchanged. */
public final class RecommendationOssFilter extends OncePerRequestFilter {
    private final RecommendationOssRedirect redirect;
    public RecommendationOssFilter(RecommendationOssRedirect redirect) { this.redirect = redirect; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var location = redirect.destination(request.getServletPath(), request.getMethod(), request.getParameter("reauth") != null);
        if (location.isEmpty()) { chain.doFilter(request, response); return; }
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", location.get().toASCIIString());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
