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
import java.util.HashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** KIE统一异步接口。提交只调用一次；断连、5xx和无法解析的响应交由worker标记待核对。 */
public final class KieSunoMusicProvider implements MusicProvider {
    private final String apiKey, model;
    private final URI base;
    private final HttpClient client;
    private final Tuning tuning;
    private com.qingfeng.music.settings.MusicPromptLimits limits=com.qingfeng.music.settings.MusicPromptLimits.suno();
    public KieSunoMusicProvider withLimits(com.qingfeng.music.settings.MusicPromptLimits value){
        if(value.title()>80||value.lyrics()>5000||value.style()>1000||value.negativeTags()>500)throw new IllegalArgumentException("预算不能超过当前KIE模型能力");
        limits=value;return this;
    }
    public com.qingfeng.music.settings.MusicPromptLimits limits(){return limits;}
    private double sectionAudioWeight=0.85;
    public KieSunoMusicProvider sectionAudioWeight(double value){if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("局部修改参考权重超出范围");sectionAudioWeight=value;return this;}
    private final ObjectMapper json = new ObjectMapper();
    private java.util.function.Consumer<String> audioApproval = ignored -> { };

    public KieSunoMusicProvider approveAudioWith(java.util.function.Consumer<String> approval) {this.audioApproval=java.util.Objects.requireNonNull(approval);return this;}

    /** 平衡风格遵循与自然度；范围与KIE自定义模式文档一致。 */
    public record Tuning(double styleWeight,double weirdness,int variety) {
        public Tuning {
            if(!Double.isFinite(styleWeight)||styleWeight<0||styleWeight>1
                ||!Double.isFinite(weirdness)||weirdness<0||weirdness>1||variety<0||variety>4)
                throw new IllegalArgumentException("KIE风格参数超出范围");
            styleWeight=Math.round(styleWeight*100)/100.0;
            weirdness=Math.round(weirdness*100)/100.0;
        }
        public static Tuning defaults(){return new Tuning(0.85,0.25,0);}
    }
    public KieSunoMusicProvider(String apiKey, String model) {this(apiKey,model,Tuning.defaults());}
    public KieSunoMusicProvider(String apiKey, String model,Tuning tuning) {
        this(apiKey, model, URI.create("https://api.kie.ai"), HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),tuning);
    }
    /** 可注入本机HTTP服务验证真实请求格式，不在测试中调用付费接口。 */
    public KieSunoMusicProvider(String apiKey, String model, URI base, HttpClient client) {
        this(apiKey,model,base,client,Tuning.defaults());
    }
    public KieSunoMusicProvider(String apiKey, String model, URI base, HttpClient client,Tuning tuning) {
        this.apiKey=apiKey.trim(); this.model=model; this.base=base; this.client=client;this.tuning=java.util.Objects.requireNonNull(tuning);
        if (!java.util.Set.of("V6","V6_MINI","V6_WILD").contains(model)) throw new IllegalArgumentException("不支持的KIE Suno模型配置");
    }
    public String name() { return "kie-suno"; }
    public boolean isConfigured() { return !apiKey.isBlank(); }
    public boolean supportsSectionEditing(){return true;}
    public Submission replace(Request request,Replacement edit,String key){
        validate(request);
        if(edit==null||edit.taskId()==null||!edit.taskId().matches("[A-Za-z0-9_-]{1,255}")||edit.audioId()==null||!edit.audioId().matches("[A-Za-z0-9_-]{1,255}")||!Double.isFinite(edit.start())||!Double.isFinite(edit.end())||edit.start()<0||edit.end()-edit.start()<10||edit.lyrics()==null||edit.lyrics().isBlank()||edit.lyrics().length()>5000)throw new Rejected();
        var input=new HashMap<String,Object>();
        input.put("task_id",edit.taskId());input.put("audio_id",edit.audioId());input.put("title",request.title());
        input.put("prompt","Re-sing only this section with the revised lyrics. Follow the original melody, tempo, vocal character and transitions as closely as possible.");
        input.put("tags",style(request));
        input.put("infill_start_s",edit.start());input.put("infill_end_s",edit.end());
        input.put("full_lyrics",request.confirmedLyrics());input.put("lyrics",edit.lyrics());
        input.put("audio_weight",sectionAudioWeight);input.put("style_weight",tuning.styleWeight());input.put("weirdness_constraint",tuning.weirdness());input.put("variety",tuning.variety());
        if(!request.vocalGender().equals("AUTO"))input.put("vocal_gender",request.vocalGender().equals("MALE")?"m":"f");
        if(!request.negativeTags().isBlank())input.put("negative_tags",request.negativeTags());
        var response=send("/api/v1/jobs/createTask",json.writeValueAsString(Map.of("model","ai-music-api/replace-section","input",input)),true);
        String task=response.path("data").path("taskId").asText("");
        if(task.isBlank()||task.length()>255)throw new IllegalStateException("局部修改提交响应缺少任务编号，禁止自动重试");
        return new Submission(task);
    }
    public void validate(Request request) {
        if (request.title()==null || request.title().isBlank() || request.title().length()>limits.title()
            || request.confirmedLyrics()==null || request.confirmedLyrics().isBlank() || request.confirmedLyrics().length()>limits.lyrics()
            || request.negativeTags().length()>limits.negativeTags()
            || request.style()==null || request.style().isBlank() || request.style().length()>limits.style()
            || style(request).length()>limits.style())
            throw new DomainException("INVALID_MUSIC_INPUT","生成歌曲需要标题不超过80字、歌词不超过5000字、风格不超过1000字，请修改后重试");
    }
    public Submission submit(Request request, String idempotencyKey) {
        validate(request);
        // KIE未承诺幂等提交。站内请求键由数据库去重，绝不以网络重试模拟供应商幂等。
        var input=new HashMap<String,Object>(Map.of("custom_mode",true,"instrumental",false,"model",model,
            "title",request.title(),"lyrics",request.confirmedLyrics(),"style",style(request)));
        input.put("style_weight",tuning.styleWeight());
        input.put("weirdness_constraint",tuning.weirdness());
        input.put("variety",tuning.variety());
        if(!request.vocalGender().equals("AUTO"))input.put("vocal_gender",request.vocalGender().equals("MALE")?"m":"f");
        if(!request.negativeTags().isBlank())input.put("negative_tags",request.negativeTags());
        var response=send("/api/v1/jobs/createTask",json.writeValueAsString(Map.of("model","ai-music-api/generate","input",input)),true);
        String task=response.path("data").path("taskId").asText("");
        if(task.isBlank()||task.length()>255)throw new IllegalStateException("KIE提交响应缺少任务编号，禁止自动重试");
        return new Submission(task);
    }
    private String style(Request request){return request.preparedStyle()?request.style():MusicStylePrompt.expand(request.style(),request.vocalGender());}
    public Request prepare(Request request){return new Request(request.title(),request.confirmedLyrics(),style(request),request.vocalGender(),request.negativeTags(),true);}
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
        var approvedUrls=new java.util.HashSet<String>();
        var candidates=new java.util.ArrayList<AudioInfo>();var seen=new java.util.HashSet<String>();
        for(var tracks:KieAudioResults.groups(result))for(var track:tracks){
            String url=track.path("audioUrl").asText(track.path("audio_url").asText(""));
            if(url.isBlank())continue;
            var source=info(result,url);
            // 不悄悄丢弃无法识别的候选，也不把流式预览链接当作完整文件。
            if(source==null)throw new IllegalStateException("候选音频缺少标识或时长，请稍后重新查询");
            if(seen.add(source.audioId())){if(approvedUrls.add(url))audioApproval.accept(url);candidates.add(source);}
        }
        if(candidates.isEmpty())audioApproval.accept(audio);
        if(candidates.size()>8)throw new IllegalStateException("候选音频数量异常");
        return new Result(taskId,GenerationStatus.SAVING,audio,info(result,audio),candidates);
    }
    public AudioInfo sourceInfo(String taskId,String archivedUrl){
        var response=send("/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(taskId,StandardCharsets.UTF_8),null,false);
        var data=response.path("data");if(!taskId.equals(data.path("taskId").asText())||!data.path("state").asText().equals("success"))return null;
        var result=data.path("resultJson");if(result.isTextual())result=json.readTree(result.asText());
        return info(result,archivedUrl);
    }
    private AudioInfo info(JsonNode result,String expectedUrl){
        for(var tracks:KieAudioResults.groups(result)){
            for(var track:tracks){
                String url=track.path("audioUrl").asText(track.path("audio_url").asText(""));
                if(!KieAudioResults.sameAudio(url,expectedUrl))continue;
                String id=track.path("id").asText(track.path("audioId").asText(track.path("audio_id").asText("")));
                double duration=track.path("duration").asDouble(0);
                if(id.matches("[A-Za-z0-9_-]{1,255}")&&Double.isFinite(duration)&&duration>0)return new AudioInfo(id,duration,url);
            }
        }
        return null;
    }
    private String firstAudio(JsonNode result) {
        for(var tracks:KieAudioResults.groups(result)) {
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
            if(submitting && java.util.Set.of(400,401,402,403,404,422,429).contains(status))throw rejected(status);
            if(status!=200)throw new IllegalStateException("KIE HTTP请求未完成");
            var payload=json.readTree(response.body()); int code=payload.path("code").asInt(-1);
            if(code!=200) {
                if(submitting && java.util.Set.of(400,401,402,403,404,422,429).contains(code))throw rejected(code);
                throw new IllegalStateException("KIE业务响应未完成");
            }
            return payload;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("KIE请求中断，禁止重复提交"); }
        catch(java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) { if(submitting)throw new NotSubmitted("PROVIDER_CONNECT_FAILED");throw new IllegalStateException("音乐服务连接失败"); }
        catch(java.io.IOException e) { throw new IllegalStateException("KIE连接或响应异常，禁止重复提交"); }
    }
    private Rejected rejected(int code){return new Rejected(switch(code){case 401,403->"PROVIDER_AUTH_FAILED";case 402->"PROVIDER_BALANCE_LOW";case 429->"PROVIDER_RATE_LIMITED";case 404->"PROVIDER_ENDPOINT_UNAVAILABLE";default->"PROVIDER_INPUT_REJECTED";});}
}
