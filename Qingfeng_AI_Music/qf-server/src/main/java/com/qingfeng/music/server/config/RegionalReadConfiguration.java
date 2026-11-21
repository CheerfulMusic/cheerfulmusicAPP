package com.qingfeng.music.server.config;

import com.qingfeng.music.credits.CreditsLedger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Set<String> READ_PATHS = Set.of("/", "/actuator/health");
    private static final String READY = "/internal/regional/ready";
    private static final Pattern GIFT = Pattern.compile("/gift/[A-Za-z0-9_-]{43}");
    private static final Duration PROOF_MAX_AGE = Duration.ofSeconds(8);
    private static final Pattern STATIC_PATH = Pattern.compile("/(assets|brand|legal)/[a-zA-Z0-9_.-]+(?:/[a-zA-Z0-9_.-]+)*");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> regionalReadRoutes(
            @Value("${qingfeng.regional.replica-proof-path:}") String proofPath,
            @Value("${qingfeng.regional.gift-enabled:false}") boolean giftEnabled) {
        Path proof = proofPath.isBlank() ? null : Path.of(proofPath);
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String path = request.getRequestURI();
                boolean read = isRead(request.getMethod());
                boolean gift = read && giftEnabled && GIFT.matcher(path).matches()
                        && "active".equals(request.getHeader("X-QF-Primary-Share-Authorized"));
                boolean ready = read && READY.equals(path);
                if (!allows(request.getMethod(), path) && !gift && !ready) {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return;
                }
                if ((gift || ready) && !freshProof(proof, Instant.now())) {
                    response.setHeader("Cache-Control", "no-store");
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                if (ready) {
                    response.setHeader("Cache-Control", "no-store");
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    return;
                }
                chain.doFilter(request, response);
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    static boolean allows(String method, String path) {
        if (!isRead(method)) return false;
        if (READ_PATHS.contains(path)) return true;
        return STATIC_PATH.matcher(path).matches() && !path.contains("/../") && !path.contains("/./")
                && !path.endsWith("/..") && !path.endsWith("/.");
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    static boolean freshProof(Path proof, Instant now) {
        if (proof == null) return false;
        try {
            var attributes = Files.readAttributes(proof, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Duration age = Duration.between(attributes.lastModifiedTime().toInstant(), now);
            return attributes.isRegularFile() && !age.isNegative() && age.compareTo(PROOF_MAX_AGE) <= 0;
        } catch (IOException e) {
            return false;
        }
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
