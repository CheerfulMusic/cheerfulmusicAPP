package com.qingfeng.music.server.web;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import java.net.URI;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Only the built-in public samples may leave the application via this route. */
public final class RecommendationOssRedirect {
    public static final String PREFIX = "qingfeng/recommendation-samples/v1/";
    public static final String PREVIEW_PREFIX = "qingfeng/recommendation-samples/preview-128-v1/";
    private static final Set<String> SAMPLES = Set.of(
        "sample-love-v2.mp3", "sample-family-v2.mp3", "sample-ocean-travel-v2.mp3",
        "sample-red-guofeng-v1.mp3", "sample-rain-disco-v1.mp3", "sample-tomorrow-rock-v1.mp3",
        "sample-scenery-folk-v1.mp3", "sample-hometown-electronic-v1.mp3", "sample-river-instrumental-v1.mp3",
        "sample-love-v2-preview-128.mp3", "sample-family-v2-preview-128.mp3", "sample-ocean-travel-v2-preview-128.mp3", "sample-red-guofeng-v1-preview-128.mp3", "sample-rain-disco-v1-preview-128.mp3", "sample-tomorrow-rock-v1-preview-128.mp3", "sample-scenery-folk-v1-preview-128.mp3", "sample-hometown-electronic-v1-preview-128.mp3", "sample-river-instrumental-v1-preview-128.mp3");
    // Bounded and daemonized: a slow OSS endpoint must not exhaust request threads.
    private static final ThreadPoolExecutor CHECKS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), task -> { var thread = new Thread(task, "recommendation-oss-check"); thread.setDaemon(true); return thread; });
    private record Availability(boolean available, long untilNanos) {}
    private final ConcurrentHashMap<String, Availability> availability = new ConcurrentHashMap<>();
    private final OSS client;
    private final String bucket;
    private final boolean enabled;
    private final Clock clock;

    public RecommendationOssRedirect(OSS client, String bucket, boolean enabled, Clock clock) {
        this.client = client; this.bucket = bucket; this.enabled = enabled; this.clock = clock;
    }

    private boolean exists(String key) {
        long now = System.nanoTime();
        var cached = availability.get(key);
        if (cached != null && now < cached.untilNanos()) return cached.available();
        boolean result = false;
        java.util.concurrent.Future<Boolean> check = null;
        try {
            check = CHECKS.submit(() -> client.doesObjectExist(bucket, key));
            result = check.get(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException ignored) {
            // The original file remains available if OSS is slow or unreachable.
        } finally {
            if (check != null && !check.isDone()) check.cancel(true);
        }
        availability.put(key, new Availability(result, System.nanoTime() + TimeUnit.SECONDS.toNanos(result ? 60 : 10)));
        return result;
    }

    public static boolean isSamplePath(String path) {
        return path != null && path.startsWith("/media/") && SAMPLES.contains(path.substring("/media/".length()));
    }

    public Optional<URI> destination(String path, String method, boolean reauth) {
        if (!enabled || client == null || bucket == null || bucket.isBlank() || reauth
                || !("GET".equals(method) || "HEAD".equals(method)) || !isSamplePath(path)) return Optional.empty();
        String name = path.substring("/media/".length());
        String key = (name.endsWith("-preview-128.mp3") ? PREVIEW_PREFIX : PREFIX) + name;
        try {
            if (!exists(key)) return Optional.empty();
            var expires = Date.from(clock.instant().plusSeconds(3600));
            var signed = client.generatePresignedUrl(bucket, key, expires, "HEAD".equals(method) ? HttpMethod.HEAD : HttpMethod.GET);
            URI result = signed.toURI();
            String host = result.getHost();
            if (!"https".equalsIgnoreCase(result.getScheme()) || host == null) return Optional.empty();
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (!host.matches("[a-z0-9-]+\\.oss-[a-z0-9-]+\\.aliyuncs\\.com") || host.contains("-internal.")) return Optional.empty();
            return Optional.of(result);
        } catch (Exception ignored) {
            // Missing objects, network failures and signing failures keep the original static route usable.
            return Optional.empty();
        }
    }
}
