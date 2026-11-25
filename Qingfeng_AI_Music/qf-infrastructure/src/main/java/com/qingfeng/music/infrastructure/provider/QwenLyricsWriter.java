package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.concurrent.LyricsRequestBudget;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsStructure;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import tools.jackson.databind.ObjectMapper;

public final class QwenLyricsWriter implements LyricsWriter {
    private static final System.Logger LOG=System.getLogger(QwenLyricsWriter.class.getName());
    private static final String ARK_SOURCE = "DOUBAO";
    private static final String FINALIZE_SONG = """

        交付前自行检查并润色故事事实、段落推进、自然语感、演唱节奏和副歌记忆点。
        保留指定段落顺序和明确要求保留的原句，每个非间奏段落都要有正文。
        只提交最终歌词及原有 JSON 所需的 title、lyrics、plan，不输出草稿或审查说明。
        """;
    private final String key,model;private final URI endpoint;private final HttpClient client;
    private final ArkTextFallback fallback;
    private final Duration qwenTimeout;
    private final ObjectMapper json=new ObjectMapper();
    public QwenLyricsWriter(String key,String model){this(key,model,URI.create(QwenInspirationRewriter.QWEN_ENDPOINT));}
    public QwenLyricsWriter(String key,String model,URI endpoint){this(key,model,endpoint,client());}
    public QwenLyricsWriter(String key,String model,URI endpoint,HttpClient client){
        this(key,model,endpoint,client,null,Duration.ofSeconds(90));
    }
    public QwenLyricsWriter(String key, String model, URI endpoint, ArkTextFallback fallback, Duration qwenTimeout) {
        this(key, model, endpoint, client(), fallback, qwenTimeout);
    }
    private QwenLyricsWriter(String key, String model, URI endpoint, HttpClient client, ArkTextFallback fallback, Duration qwenTimeout) {
        if (qwenTimeout.isZero() || qwenTimeout.isNegative() || qwenTimeout.compareTo(Duration.ofSeconds(90)) > 0)
            throw new IllegalArgumentException("Invalid Qwen lyric response deadline");
        this.key=key==null?"":key.trim();this.model=model==null||model.isBlank()?QwenInspirationRewriter.QWEN_MODEL:model.trim();this.endpoint=endpoint;this.client=client;
        this.fallback = fallback;
        this.qwenTimeout = qwenTimeout;
    }
    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public boolean isConfigured(){return !key.isBlank();}
    public boolean hasTimeoutFallback() { return fallback != null; }
    @Override public String source(){return "QWEN";}
    public Draft write(WorkApi.Work work,String language){
        return write(work,language,"","");
    }
    public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics){
        return write(work,language,instructions,previousLyrics,"");
    }
    public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,String musicDirection){
        return write(work,language,instructions,previousLyrics,new Direction(musicDirection,"",work.vocalGender()));
    }
    @Override public Draft writeValidated(WorkApi.Work work, String language, String instructions, String previousLyrics,
                                          Direction direction, BiFunction<LyricsWriter, Draft, Draft> validate) {
        if (fallback == null)
            return LyricsWriter.super.writeValidated(work, language, instructions, previousLyrics, direction, validate);
        try (var ignored = LyricsRequestBudget.enter(LyricsQualityReview.TOTAL_TIMEOUT)) {
            var draft = LyricsWriter.super.writeValidated(work, language, instructions, previousLyrics, direction, validate);
            if (!LyricsRequestBudget.hasRemaining(Duration.ofNanos(1))) throw LyricsSectionRepair.timedOut();
            return draft;
        }
    }
    public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,Direction direction){
        if (!isConfigured())
            throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE", "歌词生成暂不可用，请稍后再试或手动填写");
        boolean fullNew=previousLyrics.isBlank();
        long deadline=LyricsQualityReview.deadline();
        String system=LyricsWritingPrompt.system(language);
        var material=new LinkedHashMap<String,Object>();
        material.putAll(Map.of("recipient",work.recipient(),"occasion",work.occasion(),"story",work.story(),"message",work.message(),"style",work.style(),"preferredTitle",work.title(),"revisionInstructions",instructions,"previousLyrics",previousLyrics));
        material.put("musicDirection",direction.style());material.put("musicExclusions",direction.exclusions());material.put("vocalGender",direction.vocalGender());
        material.put("lyricsLanguage",language);
        String context=json.writeValueAsString(material);
        var request = chatRequest(system, context, 2400, true);
        long started = System.nanoTime();
        try{
            var response = complete(request, deadline, fullNew ? LyricsQualityReview.GENERATION_TIMEOUT : Duration.ofSeconds(90),
                fullNew ? () -> chatRequest(system + FINALIZE_SONG, context, 4000, true) : null);
            var result=LyricsWritingPrompt.jsonObject(outputText(response.body()));
            if (fallback != null) ProviderHttp.remaining(deadline);
            if (response.usedFallback()) {
                var draft = OpenAiLyricsWriter.parseSong(result, false, ARK_SOURCE);
                ProviderHttp.remaining(deadline);
                return draft;
            }
            var plan=result.path("plan");
            if(!result.path("title").isString()||!result.path("lyrics").isString())throw invalidResponse();
            if(!fullNew)return new Draft(result.path("title").asText(""),result.path("lyrics").asText(""),
                plan.isObject()?json.treeToValue(plan,com.qingfeng.music.settings.CreativePlan.class):null);
            var draft=OpenAiLyricsWriter.parseSong(result,false,null);
            return review(draft,language,material,deadline);
        }catch(TextModelTransport.HttpFailure rejected) {
            LOG.log(System.Logger.Level.WARNING, "Lyrics model failed: model={0}, elapsedMs={1}, reason={2}",
                model, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "HTTP_" + rejected.status);
            throw providerFailure(rejected.status);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DomainException("LYRICS_REQUEST_INTERRUPTED","歌词请求已中断，结果尚未确认，请先查看原请求进度。");}
        catch(HttpTimeoutException timeout){throw LyricsSectionRepair.timedOut();}
        catch(IOException network){throw LyricsSectionRepair.unknownConnection();}
        catch(tools.jackson.core.JacksonException invalid){throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");}
        catch(DomainException stopped){
            if("AGENT_DEADLINE_EXCEEDED".equals(stopped.code()))throw LyricsSectionRepair.timedOut();
            throw stopped;
        }
    }

    private Draft review(Draft draft,String language,Map<String,Object> material,long deadline)
            throws IOException,InterruptedException {
        var request = chatRequest(LyricsWritingPrompt.refinement(language),
            json.writeValueAsString(Map.of("originalMaterial", material, "candidate", LyricsQualityReview.candidate(draft))), 4000, false);
        try {
            var response = complete(request, deadline, LyricsQualityReview.TOTAL_TIMEOUT, null);
            if (response.usedFallback()) draft = new Draft(draft.title(), draft.lyrics(), draft.plan(), ARK_SOURCE);
            var refined = LyricsWritingPrompt.refined(draft, outputText(response.body()));
            if (fallback != null) ProviderHttp.remaining(deadline);
            return refined;
        } catch (TextModelTransport.HttpFailure | DomainException | tools.jackson.core.JacksonException invalid) {
            // No unrefined draft is eligible for delivery.
            throw LyricsQualityReview.unverified();
        }
    }

    @Override public Map<Integer, String> repairSections(SectionRepair request) {
        if (ARK_SOURCE.equals(request.draft().source())) throw LyricsStructure.mismatch();
        var body = chatRequest(LyricsSectionRepair.instructions(request.language()),
            json.writeValueAsString(LyricsSectionRepair.material(request)), 4000, true);
        if (fallback == null)
            return LyricsSectionRepair.post(client, endpoint, key, json.writeValueAsString(body), request, this::outputText);
        return LyricsSectionRepair.repair(request, (deadline, timeout) -> outputText(complete(body, deadline, timeout, null).body()));
    }

    private Map<String, Object> chatRequest(String system, String context, int maxTokens, boolean structured) {
        var body = new LinkedHashMap<String, Object>(Map.of("model", model, "messages", List.of(
            Map.of("role", "system", "content", system), Map.of("role", "user", "content", context)),
            "enable_thinking", false, "max_tokens", maxTokens, "stream", false));
        if (structured) body.put("response_format", Map.of("type", "json_object"));
        return body;
    }

    private record Completion(String body, boolean usedFallback) {}

    private Completion complete(Map<String, Object> request, long deadline, Duration maximum,
                                Supplier<Map<String, Object>> arkRequest) throws IOException, InterruptedException {
        String body = json.writeValueAsString(request);
        if (fallback == null) {
            var response = LyricsHttpTransport.post(client, endpoint, key, body, LyricsQualityReview.remaining(deadline, maximum));
            int status = response.statusCode();
            if (status == 408 || status == 504) throw new HttpTimeoutException("Lyrics response timed out");
            if (status != 200) throw new TextModelTransport.HttpFailure(status);
            return new Completion(response.body(), false);
        }
        long started = System.nanoTime();
        long qwenDeadline = Math.min(deadline, started + Math.min(qwenTimeout.toNanos(), maximum.toNanos()));
        try {
            return new Completion(postQwen(body, qwenDeadline), false);
        } catch (TextModelTransport.SentTimeout timeout) {
            ProviderHttp.remaining(deadline);
            if (!LyricsRequestBudget.claimTimeoutFallback()) throw timeout;
            String alternate = json.writeValueAsString(fallback.requestBody(arkRequest == null ? request : arkRequest.get()));
            try {
                return new Completion(fallback.complete(client, alternate, deadline, model, started), true);
            } catch (IOException unavailable) {
                throw timeout;
            }
        }
    }

    private String postQwen(String body, long deadline) throws IOException, InterruptedException {
        try {
            return TextModelTransport.post(client, endpoint, key, "qwen", body, deadline);
        } catch (TextModelTransport.NotSent firstConnectionFailure) {
            LyricsHttpTransport.pauseBeforeRetry(deadline);
            try {
                return TextModelTransport.post(client, endpoint, key, "qwen", body, deadline);
            } catch (TextModelTransport.NotSent secondConnectionFailure) {
                throw LyricsHttpTransport.connectFailed();
            }
        }
    }

    private static DomainException providerFailure(int status) {
        return switch (status) {
            case 502 -> LyricsSectionRepair.unknownConnection();
            case 401, 403 -> new DomainException("LYRICS_PROVIDER_UNAVAILABLE", "歌词服务认证失败，请检查后端千问密钥");
            case 402 -> new DomainException("LYRICS_PROVIDER_UNAVAILABLE", "千问余额不足，请充值后重试");
            default -> new DomainException("LYRICS_PROVIDER_UNAVAILABLE", "歌词生成服务暂时繁忙，请稍后再试");
        };
    }

    private String outputText(String body) {
        var choice = json.readTree(body).path("choices").path(0);
        var message = choice.path("message");
        if (!"stop".equals(choice.path("finish_reason").asText()) || !message.path("content").isString()
                || !message.path("refusal").asText("").isBlank()) throw invalidResponse();
        return message.path("content").asText();
    }

    private static DomainException invalidResponse() {
        return new DomainException("LYRICS_RESPONSE_INVALID", "歌词生成结果不完整，请稍后重新发起");
    }
}
