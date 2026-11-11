package com.qingfeng.music.server.web.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从请求中解析真实客户端 IP。生产为单级反代（nginx 已透传 X-Forwarded-For / X-Real-IP），
 * 三者按优先级回退：X-Forwarded-For 首段（多跳取原始客户端）→ X-Real-IP → TCP 远端地址。
 */
public final class ClientIp {
    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) return null;
        String forwarded = firstOf(request.getHeader("X-Forwarded-For"));
        if (forwarded != null) return forwarded;
        String realIp = firstOf(request.getHeader("X-Real-IP"));
        if (realIp != null) return realIp;
        return request.getRemoteAddr();
    }

    private static String firstOf(String header) {
        if (header == null || header.isBlank()) return null;
        int comma = header.indexOf(',');
        String first = (comma >= 0 ? header.substring(0, comma) : header).trim();
        return first.isEmpty() ? null : first;
    }
}
