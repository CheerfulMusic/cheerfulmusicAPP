package com.qingfeng.music.server.mv;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** DashScope asynchronous video API. Submissions are never automatically retried. */
public final class WanVideoProvider implements VideoProvider {
    private static final java.util.concurrent.ScheduledExecutorService DOWNLOAD_TIMEOUT = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "wan-download-timeout"); thread.setDaemon(true); return thread;
    });
    private final URI base;
    private final String key, model;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public WanVideoProvider(String url, String key, String model) {
        this(url, key, model, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    WanVideoProvider(String url, String key, String model, HttpClient client) {
        base = URI.create(url.endsWith("/") ? url : url + "/");
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("Video endpoint must be an HTTPS API base URL");
        this.key = key; this.model = model; http = client;
    }
    public boolean available() { return key != null && !key.isBlank() && model != null && !model.isBlank(); }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + key);
    }
    private String task(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new Failure("VIDEO_INVALID_TASK_ID", true);
        return "tasks/" + id;
    }
    public String submit(String prompt, int seconds, String ratio, String requestId) {
        if (!available()) throw new Failure("VIDEO_NOT_CONFIGURED", false);
        if (seconds < 2 || seconds > 30 || !Set.of("21:9", "16:9", "4:3", "1:1", "3:4", "9:16").contains(ratio))
            throw new Failure("VIDEO_INVALID_PARAMETERS", false);
        var body = Map.of("model", model, "input", Map.of("prompt", prompt), "parameters",
                Map.of("resolution", "720P", "ratio", ratio, "duration", seconds, "audio", false, "prompt_extend", true, "watermark", false));
        try {
            var response = http.send(request("services/aigc/video-generation/video-synthesis")
                    .header("X-DashScope-Async", "enable").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new Failure("VIDEO_SUBMIT_HTTP_" + response.statusCode(), response.statusCode() >= 500 || response.statusCode() == 408);
            String id = json.readTree(response.body()).path("output").path("task_id").asText("");
            task(id); return id;
        } catch (Failure e) { throw e; }
        catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new Failure("VIDEO_SUBMIT_UNCERTAIN", true); }
    }
    private JsonNode output(String id) {
        try {
            var response = http.send(request(task(id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new Failure("VIDEO_POLL_HTTP_" + response.statusCode(), false);
            return json.readTree(response.body()).path("output");
        } catch (Failure e) { throw e; }
        catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new Failure("VIDEO_POLL_UNAVAILABLE", false); }
    }
    public String status(String id) { return output(id).path("task_status").asText("unknown").toLowerCase(Locale.ROOT); }
    static URI downloadUri(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            // Provider-generated signed OSS URLs only; never send the API key to object storage.
            if (!"https".equals(uri.getScheme()) || host == null || !host.toLowerCase(Locale.ROOT).endsWith(".aliyuncs.com")
                    || !host.toLowerCase(Locale.ROOT).contains(".oss-") || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) { throw new Failure("VIDEO_INVALID_DOWNLOAD_URL", false); }
    }
    public void download(String id, Path destination) {
        var result = output(id);
        if (!"SUCCEEDED".equals(result.path("task_status").asText(""))) throw new Failure("VIDEO_NOT_READY", false);
        URI uri = downloadUri(result.path("video_url").asText(""));
        try {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            var deadline = DOWNLOAD_TIMEOUT.schedule(() -> { try { response.body().close(); } catch (java.io.IOException ignored) {} }, 120, java.util.concurrent.TimeUnit.SECONDS);
            try (var input = response.body()) {
                if (response.statusCode() != 200) throw new Failure("VIDEO_DOWNLOAD_HTTP_" + response.statusCode(), false);
                long total = 0; byte[] buffer = new byte[65536];
                try (var target = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    for (int n; (n = input.read(buffer)) != -1;) {
                        total += n; if (total > 300L * 1024 * 1024) throw new Failure("VIDEO_TOO_LARGE", false);
                        target.write(buffer, 0, n);
                    }
                }
                if (total < 100) throw new Failure("VIDEO_EMPTY_CONTENT", false);
            } finally { deadline.cancel(false); }
        } catch (Failure e) { throw e; }
        catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new Failure("VIDEO_DOWNLOAD_UNAVAILABLE", false); }
    }
}
