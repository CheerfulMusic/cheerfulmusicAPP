package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.settings.CreativePlan;
import com.qingfeng.music.work.api.WorkApi;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** GPT 写词使用官方 Responses API；失败不重发付费请求，也不覆盖已有歌词。 */
public final class OpenAiLyricsWriter implements LyricsWriter {
    public static final String DEFAULT_MODEL = "gpt-6.1-terra";
    private static final System.Logger LOG = System.getLogger(OpenAiLyricsWriter.class.getName());
    private final String key, model;
    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiLyricsWriter(String key, String model) {
        this(key, model, URI.create("https://api.openai.com/v1/responses"), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    // 可注入传输用于离线协议验证，应用配置不接受任意第三方端点。
    public OpenAiLyricsWriter(String key, String model, URI endpoint, HttpClient client) {
        this.key = key == null ? "" : key.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.endpoint = Objects.requireNonNull(endpoint);
        this.client = Objects.requireNonNull(client);
    }

    @Override public boolean isConfigured() { return !key.isBlank(); }
    @Override public String source() { return "OPENAI"; }
    @Override public Draft write(WorkApi.Work work, String language) {
        return write(work, language, "", "", "");
    }
    @Override public Draft write(WorkApi.Work work, String language, String instructions, String previousLyrics) {
        return write(work, language, instructions, previousLyrics, "");
    }
    @Override public Draft write(WorkApi.Work work, String language, String instructions,
                                 String previousLyrics, String musicDirection) {
        if (!isConfigured()) throw unavailable("歌词生成暂不可用，请稍后再试或手动填写");
        var material = new LinkedHashMap<String, Object>();
        material.putAll(Map.of("recipient", work.recipient(), "occasion", work.occasion(),
            "story", work.story(), "message", work.message(), "style", work.style(),
            "preferredTitle", work.title(), "revisionInstructions", instructions, "previousLyrics", previousLyrics));
        material.put("musicDirection", musicDirection);
        material.put("vocalGender", work.vocalGender());
        String body = json.writeValueAsString(Map.of(
            "model", model, "instructions", LyricsWritingPrompt.system(language),
            "input", List.of(Map.of("role", "user", "content", json.writeValueAsString(material))),
            "text", Map.of("format", Map.of("type", "json_schema", "name", "song_lyrics", "strict", true, "schema", schema())),
            "reasoning", Map.of("effort", "low"), "max_output_tokens", 6000, "store", false, "stream", false));
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 不记录响应正文、请求素材、认证头或传输异常，防止密钥和用户故事进入日志。
                LOG.log(System.Logger.Level.WARNING, "GPT lyrics HTTP failure: work={0}, status={1}", work.id(), response.statusCode());
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw unavailable("歌词服务暂不可用，请联系管理员检查 GPT 接口授权。");
                if (response.statusCode() == 429)
                    throw unavailable("歌词服务当前额度不足或请求较多，请稍后再试；已有内容仍会保留。");
                throw unavailable("歌词生成服务暂时不可用，请稍后再试；已有内容仍会保留。");
            }
            return parse(response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DomainException("LYRICS_REQUEST_INTERRUPTED", "歌词编写已中断，可以重新编写；已有内容仍会保留。");
        } catch (HttpTimeoutException timeout) {
            throw new DomainException("LYRICS_PROVIDER_TIMEOUT", "歌词编写超时，可以重新编写；已有内容仍会保留。");
        } catch (IOException network) {
            throw new DomainException("LYRICS_PROVIDER_NETWORK_ERROR", "歌词服务连接中断，可以稍后重新编写。");
        } catch (tools.jackson.core.JacksonException invalid) {
            throw incomplete();
        }
    }

    private Draft parse(String body) {
        var response = json.readTree(body);
        if (!"completed".equals(response.path("status").asText()) ||
            (!response.path("error").isMissingNode() && !response.path("error").isNull()))
            throw incomplete();
        StringBuilder text = new StringBuilder();
        for (var item : response.path("output")) {
            // reasoning 不是歌词；只读取已完成的 assistant message 中的 output_text。
            if (!"message".equals(item.path("type").asText())) continue;
            if (!"assistant".equals(item.path("role").asText()) || !"completed".equals(item.path("status").asText()))
                throw incomplete();
            for (var part : item.path("content")) {
                if ("refusal".equals(part.path("type").asText()))
                    throw new DomainException("LYRICS_RESPONSE_INVALID", "这次内容暂时无法编写，请调整创作描述后再试；已有内容仍会保留。");
                if ("output_text".equals(part.path("type").asText())) text.append(part.path("text").asText());
            }
        }
        if (text.isEmpty()) throw incomplete();
        var result = json.readTree(text.toString());
        String title = requiredText(result, "title", 80, true);
        String lyrics = requiredText(result, "lyrics", 5000, true);
        var plan = result.path("plan");
        if (!Set.of("AUTO", "MALE", "FEMALE").contains(plan.path("vocalGender").asText())) throw incomplete();
        var creative = new CreativePlan(requiredText(plan, "requirements", 600, false),
            requiredText(plan, "suggestedArrangement", 240, false), requiredText(plan, "negativeTags", 250, false),
            requiredText(plan, "summaryZh", 200, false), requiredText(plan, "summaryEn", 300, false),
            requiredText(plan, "vocalGender", 6, true));
        return new Draft(title, lyrics, creative);
    }

    private static String requiredText(JsonNode node, String name, int max, boolean nonblank) {
        var field = node.path(name);
        if (!field.isString()) throw incomplete();
        String value = field.asText().trim();
        if (value.length() > max || nonblank && value.isBlank()) throw incomplete();
        return value;
    }

    private static Map<String, Object> schema() {
        var plan = new LinkedHashMap<String, Object>();
        plan.put("requirements", string(600));
        plan.put("suggestedArrangement", string(240));
        plan.put("negativeTags", string(250));
        plan.put("summaryZh", string(200));
        plan.put("summaryEn", string(300));
        plan.put("vocalGender", Map.of("type", "string", "enum", List.of("AUTO", "MALE", "FEMALE")));
        return object(Map.of("title", string(80), "lyrics", string(5000), "plan", object(plan)));
    }
    private static Map<String, Object> string(int max) { return Map.of("type", "string", "maxLength", max); }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static DomainException incomplete() {
        return new DomainException("LYRICS_RESPONSE_INVALID", "歌词生成结果不完整，请稍后重新发起；已有内容仍会保留。");
    }
    private static DomainException unavailable(String message) { return new DomainException("LYRICS_PROVIDER_UNAVAILABLE", message); }
}
