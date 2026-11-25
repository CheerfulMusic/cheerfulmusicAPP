package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.ArkTextFallback;
import java.net.URI;
import java.util.Set;
import org.springframework.core.env.Environment;

final class ArkTextFallbackSettings {
    private static final String PREFIX = "qingfeng.providers.ark.";
    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";
    private static final String DEFAULT_MODEL = "doubao-seed-2-1-pro-260915";

    private ArkTextFallbackSettings() {}

    static ArkTextFallback configured(Environment env) {
        if (!env.getProperty(PREFIX + "qwen-timeout-fallback-enabled", Boolean.class, false)) return null;
        String key = env.getProperty(PREFIX + "api-key", env.getProperty("ARK_API_KEY", "")).trim();
        if (key.isEmpty()) return null;
        String model = env.getProperty(PREFIX + "text-model", DEFAULT_MODEL).trim();
        if (model.isEmpty()) throw new IllegalArgumentException("Ark text model or endpoint ID is required");
        String baseUrl = env.getProperty(PREFIX + "text-base-url", DEFAULT_BASE_URL).trim().replaceAll("/+$", "");
        return new ArkTextFallback(key, model, endpoint(baseUrl));
    }

    private static URI endpoint(String baseUrl) {
        try {
            var uri = URI.create(baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions");
            String host = uri.getHost();
            if (host != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
                        && Set.of("127.0.0.1", "localhost", "[::1]").contains(host))) return uri;
        } catch (IllegalArgumentException ignored) {
            // Configuration errors must not echo the supplied URL.
        }
        throw new IllegalArgumentException("Ark text URL must be HTTPS or loopback HTTP, without credentials, query or fragment");
    }
}
