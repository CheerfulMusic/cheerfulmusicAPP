package com.qingfeng.music.server.web;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import java.net.URI;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

/** Only the built-in public samples may leave the application via this route. */
public final class RecommendationOssRedirect {
    public static final String PREFIX = "qingfeng/recommendation-samples/v1/";
    private static final Set<String> SAMPLES = Set.of(
        "sample-love-v2.mp3", "sample-family-v2.mp3", "sample-ocean-travel-v2.mp3",
        "sample-red-guofeng-v1.mp3", "sample-rain-disco-v1.mp3", "sample-tomorrow-rock-v1.mp3",
        "sample-scenery-folk-v1.mp3", "sample-hometown-electronic-v1.mp3", "sample-river-instrumental-v1.mp3");
    private final OSS client;
    private final String bucket;
    private final boolean enabled;
    private final Clock clock;

    public RecommendationOssRedirect(OSS client, String bucket, boolean enabled, Clock clock) {
        this.client = client; this.bucket = bucket; this.enabled = enabled; this.clock = clock;
    }

    public static boolean isSamplePath(String path) {
        return path != null && path.startsWith("/media/") && SAMPLES.contains(path.substring("/media/".length()));
    }

    public Optional<URI> destination(String path, String method, boolean reauth) {
        if (!enabled || client == null || bucket == null || bucket.isBlank() || reauth
                || !("GET".equals(method) || "HEAD".equals(method)) || !isSamplePath(path)) return Optional.empty();
        String name = path.substring("/media/".length());
        String key = PREFIX + name;
        try {
            if (!client.doesObjectExist(bucket, key)) return Optional.empty();
            var expires = Date.from(clock.instant().plusSeconds(3600));
            var signed = client.generatePresignedUrl(bucket, key, expires, "HEAD".equals(method) ? HttpMethod.HEAD : HttpMethod.GET);
            URI result = signed.toURI();
            return "https".equalsIgnoreCase(result.getScheme()) ? Optional.of(result) : Optional.empty();
        } catch (Exception ignored) {
            // Missing objects, network failures and signing failures keep the original static route usable.
            return Optional.empty();
        }
    }
}
