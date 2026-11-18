package com.qingfeng.music.server.config;

import com.qingfeng.music.credits.CreditsLedger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("mysql & sg-reader")
public class RegionalReadConfiguration {
    private static final Set<String> READ_PATHS = Set.of("/", "/discover", "/api/public/discover", "/actuator/health");
    private static final Pattern STATIC_PATH = Pattern.compile("/(assets|brand|legal)/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> regionalReadRoutes() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                if (!allows(request.getMethod(), request.getRequestURI())) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static boolean allows(String method, String path) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) return false;
        if (READ_PATHS.contains(path)) return true;
        return STATIC_PATH.matcher(path).matches() && !path.contains("/../") && !path.contains("/./")
                && !path.endsWith("/..") && !path.endsWith("/.");
    }

    static CreditsLedger disabledLedger(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!connection.isReadOnly() || !"MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Regional application requires a read-only MySQL connection");
            }
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT @@GLOBAL.read_only, @@GLOBAL.super_read_only")) {
                if (!row.next() || !row.getBoolean(1) || !row.getBoolean(2)) {
                    throw new IllegalStateException("Regional application requires a super_read_only replica");
                }
            }
            return null;
        });
        return new CreditsLedger() {
            @Override public void credit(String owner, long amount, String reference) { throw unavailable(); }
            @Override public void debit(String owner, long amount, String reference) { throw unavailable(); }
            @Override public void refund(String owner, String reference) { throw unavailable(); }
            @Override public long balance(String owner) { throw unavailable(); }

            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("Credits are handled by the primary application");
            }
        };
    }
}
