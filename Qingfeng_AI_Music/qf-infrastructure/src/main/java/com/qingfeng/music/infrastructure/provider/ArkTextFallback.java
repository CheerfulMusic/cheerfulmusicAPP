package com.qingfeng.music.infrastructure.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Ark's Chat Completions dialect for a single attempt after a dispatched Qwen call times out. */
public final class ArkTextFallback {
    private static final System.Logger LOG = System.getLogger(ArkTextFallback.class.getName());
    private final String key;
    private final String model;
    private final URI endpoint;

    public ArkTextFallback(String key, String model, URI endpoint) {
        this.key = key;
        this.model = model;
        this.endpoint = endpoint;
    }

    String model() { return model; }

    Map<String, Object> requestBody(Map<String, Object> qwenBody) {
        var body = new LinkedHashMap<>(qwenBody);
        body.put("model", model);
        body.remove("enable_thinking");
        body.put("thinking", Map.of("type", "disabled"));
        return body;
    }

    String complete(HttpClient client, String body, long deadline, String primaryModel, long primaryStarted)
            throws IOException, InterruptedException {
        ProviderHttp.remaining(deadline);
        LOG.log(System.Logger.Level.WARNING, "Text model fallback: model={0}, fallbackModel={1}, elapsedMs={2}, reason=QWEN_TIMEOUT",
            primaryModel, model, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - primaryStarted));
        return TextModelTransport.post(client, endpoint, key, "ark", body, deadline);
    }
}
