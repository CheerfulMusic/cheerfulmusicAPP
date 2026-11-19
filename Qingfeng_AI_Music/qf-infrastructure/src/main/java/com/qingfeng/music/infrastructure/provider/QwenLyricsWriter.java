package com.qingfeng.music.infrastructure.provider;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

public final class QwenLyricsWriter implements LyricsWriter {
    private static final System.Logger LOG=System.getLogger(QwenLyricsWriter.class.getName());
    private final String key,model;private final URI endpoint;private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper();
    public QwenLyricsWriter(String key,String model){this(key,model,URI.create(QwenInspirationRewriter.QWEN_ENDPOINT));}
    public QwenLyricsWriter(String key,String model,URI endpoint){this(key,model,endpoint,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());}
    public QwenLyricsWriter(String key,String model,URI endpoint,HttpClient client){this.key=key==null?"":key.trim();this.model=model==null||model.isBlank()?QwenInspirationRewriter.QWEN_MODEL:model.trim();this.endpoint=endpoint;this.client=client;}
    public boolean isConfigured(){return !key.isBlank();}
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
    public Draft write(WorkApi.Work work,String language,String instructions,String previousLyrics,Direction direction){
        boolean fullNew=previousLyrics.isBlank();
        long deadline=LyricsQualityReview.deadline();
        String system=LyricsWritingPrompt.system(language);
        var material=new LinkedHashMap<String,Object>();
        material.putAll(Map.of("recipient",work.recipient(),"occasion",work.occasion(),"story",work.story(),"message",work.message(),"style",work.style(),"preferredTitle",work.title(),"revisionInstructions",instructions,"previousLyrics",previousLyrics));
        material.put("musicDirection",direction.style());material.put("musicExclusions",direction.exclusions());material.put("vocalGender",direction.vocalGender());
        material.put("lyricsLanguage",language);
        String context=json.writeValueAsString(material);
        String body=json.writeValueAsString(Map.of("model",model,"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",context)),"response_format",Map.of("type","json_object"),"enable_thinking",false,"max_tokens",2400,"stream",false));
        try{
            var response=LyricsHttpTransport.post(client,endpoint,key,body,fullNew
                ?LyricsQualityReview.remaining(deadline,LyricsQualityReview.GENERATION_TIMEOUT):Duration.ofSeconds(90));
            if(response.statusCode()!=200)LOG.log(System.Logger.Level.WARNING,"Lyrics provider HTTP failure: work={0}, status={1}",work.id(),response.statusCode());
            if(response.statusCode()==408||response.statusCode()==504)throw new HttpTimeoutException("Lyrics generation timed out");
            if(response.statusCode()==502)throw new java.io.IOException("Lyrics gateway outcome unknown");
            if(response.statusCode()==401||response.statusCode()==403)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词服务认证失败，请检查后端千问密钥");
            if(response.statusCode()==402)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","千问余额不足，请充值后重试");
            if(response.statusCode()!=200)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词生成服务暂时繁忙，请稍后再试");
            var result=LyricsWritingPrompt.jsonObject(outputText(response.body()));
            var plan=result.path("plan");
            if(!result.path("title").isString()||!result.path("lyrics").isString())throw invalidResponse();
            if(!fullNew)return new Draft(result.path("title").asText(""),result.path("lyrics").asText(""),
                plan.isObject()?json.treeToValue(plan,com.qingfeng.music.settings.CreativePlan.class):null);
            var draft=OpenAiLyricsWriter.parseSong(result,false,null);
            return review(draft,language,material,work.id(),deadline);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DomainException("LYRICS_REQUEST_INTERRUPTED","歌词请求已中断，结果尚未确认，请先查看原请求进度。");}
        catch(java.net.http.HttpTimeoutException timeout){throw new DomainException("LYRICS_PROVIDER_TIMEOUT","等待歌词结果超时，请先查看原请求进度。");}
        catch(java.io.IOException network){throw new DomainException("LYRICS_PROVIDER_NETWORK_ERROR","歌词服务连接中断，结果尚未确认，请先查看原请求进度。");}
        catch(tools.jackson.core.JacksonException invalid){throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");}
        catch(DomainException stopped){
            if("AGENT_DEADLINE_EXCEEDED".equals(stopped.code()))throw new DomainException("LYRICS_PROVIDER_TIMEOUT","等待歌词结果超时，请先查看原请求进度。");
            throw stopped;
        }
    }

    private Draft review(Draft draft,String language,Map<String,Object> material,String workId,long deadline)
            throws java.io.IOException,InterruptedException {
        String body=json.writeValueAsString(Map.of("model",model,"messages",List.of(
            Map.of("role","system","content",LyricsWritingPrompt.refinement(language)),
            Map.of("role","user","content",json.writeValueAsString(Map.of("originalMaterial",material,"candidate",LyricsQualityReview.candidate(draft))))),
            "enable_thinking",false,"max_tokens",4000,"stream",false));
        try {
            var response=LyricsHttpTransport.post(client,endpoint,key,body,LyricsQualityReview.remaining(deadline));
            if(response.statusCode()==408||response.statusCode()==504)throw new HttpTimeoutException("Lyrics refinement timed out");
            if(response.statusCode()!=200)throw LyricsQualityReview.unverified();
            return LyricsWritingPrompt.refined(draft,outputText(response.body()));
        } catch (DomainException | tools.jackson.core.JacksonException invalid) {
            // A failed refinement must never trigger a second provider generation or return the initial draft.
            throw LyricsQualityReview.unverified();
        }
    }

    @Override public Map<Integer, String> repairSections(SectionRepair request) {
        String body = json.writeValueAsString(Map.of("model", model, "messages", List.of(
            Map.of("role", "system", "content", LyricsSectionRepair.instructions(request.language())),
            Map.of("role", "user", "content", json.writeValueAsString(LyricsSectionRepair.material(request)))),
            "response_format", Map.of("type", "json_object"), "enable_thinking", false, "max_tokens", 4000, "stream", false));
        return LyricsSectionRepair.post(client, endpoint, key, body, request, this::outputText);
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
