package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** KIE统一异步接口。提交只调用一次；断连、5xx和无法解析的响应交由worker标记待核对。 */
public final class KieSunoMusicProvider implements MusicProvider {
    private final String apiKey, model;
    private final URI base;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private java.util.function.Consumer<String> audioApproval = ignored -> { };

    public KieSunoMusicProvider approveAudioWith(java.util.function.Consumer<String> approval) {this.audioApproval=java.util.Objects.requireNonNull(approval);return this;}

    public KieSunoMusicProvider(String apiKey, String model) {
        this(apiKey, model, URI.create("https://api.kie.ai"), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    /** 可注入本机HTTP服务验证真实请求格式，不在测试中调用付费接口。 */
    public KieSunoMusicProvider(String apiKey, String model, URI base, HttpClient client) {
        this.apiKey=apiKey.trim(); this.model=model; this.base=base; this.client=client;
        if (!java.util.Set.of("V6","V6_MINI","V6_WILD").contains(model)) throw new IllegalArgumentException("不支持的KIE Suno模型配置");
    }
    public String name() { return "kie-suno"; }
    public boolean isConfigured() { return !apiKey.isBlank(); }
    public void validate(Request request) {
        if (request.title()==null || request.title().isBlank() || request.title().length()>80
            || request.confirmedLyrics()==null || request.confirmedLyrics().isBlank() || request.confirmedLyrics().length()>5000
            || request.style()==null || request.style().isBlank() || request.style().length()>1000)
            throw new DomainException("INVALID_MUSIC_INPUT","生成歌曲需要标题不超过80字、歌词不超过5000字、风格不超过1000字，请修改后重试");
    }
    public Submission submit(Request request, String idempotencyKey) {
        validate(request);
        // KIE未承诺幂等提交。站内请求键由数据库去重，绝不以网络重试模拟供应商幂等。
        var input=Map.of("custom_mode",true,"instrumental",false,"model",model,
            "title",request.title(),"lyrics",request.confirmedLyrics(),"style",request.style());
        var response=send("/api/v1/jobs/createTask",json.writeValueAsString(Map.of("model","ai-music-api/generate","input",input)),true);
        String task=response.path("data").path("taskId").asText("");
        if(task.isBlank()||task.length()>255)throw new IllegalStateException("KIE提交响应缺少任务编号，禁止自动重试");
        return new Submission(task);
    }
    public Result query(String taskId) {
        var response=send("/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(taskId,StandardCharsets.UTF_8),null,false);
        var data=response.path("data");
        if(!taskId.equals(data.path("taskId").asText()))throw new IllegalStateException("KIE任务编号不匹配");
        String state=data.path("state").asText();
        if(state.equals("fail"))return new Result(taskId,GenerationStatus.FAILED,null);
        if(!state.equals("success")) {
            if(!java.util.Set.of("waiting","queuing","generating").contains(state))throw new IllegalStateException("KIE任务状态无法识别");
            return new Result(taskId,GenerationStatus.RUNNING,null);
        }
        JsonNode result=data.path("resultJson");
        if(result.isTextual())result=json.readTree(result.asText());
        String audio=firstAudio(result);
        if(audio.isBlank())throw new IllegalStateException("KIE完成任务缺少音频，稍后重新查询");
        audioApproval.accept(audio);
        return new Result(taskId,GenerationStatus.SAVING,audio);
    }
    private String firstAudio(JsonNode result) {
        for(String field: new String[]{"sunoData","data","tracks"}) {
            var tracks=result.path(field);
            if(tracks.isArray()) for(var track:tracks) {
                String url=track.path("audioUrl").asText(track.path("audio_url").asText(""));
                if(!url.isBlank())return url;
            }
        }
        var urls=result.path("resultUrls");
        if(urls.isArray())for(var url:urls)if(url.isTextual()&&!url.asText().isBlank())return url.asText();
        return "";
    }
    private JsonNode send(String path, String body, boolean submitting) {
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(45))
                .header("Authorization","Bearer "+apiKey).header("Accept","application/json");
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            int status=response.statusCode();
            if(submitting && java.util.Set.of(400,401,402,403,404,422,429).contains(status))throw new Rejected();
            if(status!=200)throw new IllegalStateException("KIE HTTP请求未完成");
            var payload=json.readTree(response.body()); int code=payload.path("code").asInt(-1);
            if(code!=200) {
                if(submitting && java.util.Set.of(400,401,402,403,404,422,429).contains(code))throw new Rejected();
                throw new IllegalStateException("KIE业务响应未完成");
            }
            return payload;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("KIE请求中断，禁止重复提交"); }
        catch(java.io.IOException e) { throw new IllegalStateException("KIE连接或响应异常，禁止重复提交"); }
    }
}
