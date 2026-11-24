package com.qingfeng.music.infrastructure.provider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.LongConsumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Scoped to admitted execution so transport retries each obtain their own quota. */
public final class AiModelUsage implements AutoCloseable {
    public interface Gate {
        LongConsumer reserve(String provider, URI endpoint, long estimate);
    }
    private static final ThreadLocal<Gate> GATE = new ThreadLocal<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LongConsumer NONE = ignored -> {};
    private final Gate prior;

    private AiModelUsage(Gate gate) {
        prior = GATE.get();
        GATE.set(gate);
    }

    public static AiModelUsage open(Gate gate) {
        return new AiModelUsage(gate);
    }

    static boolean active() {
        return GATE.get() != null;
    }

    static LongConsumer before(URI endpoint, String body) {
        if (GATE.get() == null) return NONE;
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        String provider = host.contains("dashscope") ? "qwen" : host.startsWith("ark.") ? "ark" : "openai";
        return before(provider, endpoint, body);
    }

    static LongConsumer before(String provider, URI endpoint, String body) {
        Gate gate = GATE.get();
        if (gate == null) return NONE;
        JsonNode request = JSON.readTree(body);
        long output = request.path("max_tokens").asLong(request.path("max_output_tokens").asLong(4000));
        long estimatedInput = withoutEncodedImages(request);
        return gate.reserve(provider, endpoint, Math.addExact(estimatedInput, output));
    }

    private static long withoutEncodedImages(JsonNode value) {
        if (value.isString()) {
            String text = value.asText();
            return text.startsWith("data:image/") ? 4096 : text.getBytes(StandardCharsets.UTF_8).length + 4L;
        }
        if (value.isObject()) {
            long total = 2;
            for (var property : value.properties()) total += property.getKey().length() + 4 + withoutEncodedImages(property.getValue());
            return total;
        }
        if (value.isArray()) {
            long total = 2;
            for (var item : value) total += 1 + withoutEncodedImages(item);
            return total;
        }
        return value.asText().length();
    }

    static void reported(LongConsumer reservation, String body) {
        if (reservation == NONE) return;
        long used;
        try {
            used = JSON.readTree(body).path("usage").path("total_tokens").asLong(-1);
        } catch (RuntimeException malformed) {
            return;
        }
        if (used >= 0) reservation.accept(used);
    }

    @Override public void close() {
        if (prior == null) GATE.remove(); else GATE.set(prior);
    }
}
