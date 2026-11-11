package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.IntentClassifier;
import com.qingfeng.music.agent.api.IntentResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 意图兜底：沿用 DeepSeek 现有密钥与 response_format:json_object 模式，
 * 同步调用、12 秒超时。任何异常/非法输出一律返回 null，由 IntentRouter 降级为规则澄清。
 * W1 默认 qingfeng.agent.intent.llm-enabled=false，不会产生任何真实模型请求。
 */
public final class DeepSeekIntentClassifier implements IntentClassifier {

    private static final String SYSTEM = """
            你是音乐创作助手的意图分类器。只输出 JSON 对象，不要输出解释文字。
            可用 tool：generate_song / generate_lyrics / modify_section / extend_song / set_style / get_current_song。
            输出字段：{"tool":"...","args":{"text":"用户原话"},"confidence":0.0~1.0,"replyText":"简短中文回复"}。
            无法确定时输出 {"tool":null,"args":{},"confidence":0,"replyText":"请补充说明"}。
            """;

    private final String key;
    private final String model;
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public DeepSeekIntentClassifier(String key, String model) {
        this(key, model, URI.create("https://api.deepseek.com/chat/completions"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public DeepSeekIntentClassifier(String key, String model, URI endpoint, HttpClient http) {
        this.key = key == null ? "" : key;
        this.model = model == null || model.isBlank() ? "deepseek-flash" : model;
        this.endpoint = endpoint;
        this.http = http;
    }

    @Override
    public boolean isConfigured() { return !key.isBlank(); }

    @Override
    public IntentResult classify(String text, String locale) {
        if (!isConfigured()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", java.util.List.of(
                    Map.of("role", "system", "content", SYSTEM),
                    Map.of("role", "user", "content", "locale=" + (locale == null ? "zh-CN" : locale) + " text=" + text)));
            body.put("response_format", Map.of("type", "json_object"));
            body.put("stream", false);
            body.put("max_tokens", 500);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return null;
            JsonNode root = json.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return null;
            JsonNode parsed = json.readTree(content);
            String tool = parsed.path("tool").isNull() ? null : parsed.path("tool").asText(null);
            if (AgentTool.fromId(tool) == null) return null;
            double confidence = parsed.path("confidence").asDouble(0);
            if (confidence < 0 || confidence > 1) return null;
            String reply = parsed.path("replyText").asText("好的，我来处理。");
            Map<String, Object> args = Map.of("text", text, "locale", locale == null ? "zh-CN" : locale);
            return new IntentResult(tool, args, confidence, false, reply, IntentResult.Source.LLM);
        } catch (Exception ignored) {
            return null;
        }
    }
}
