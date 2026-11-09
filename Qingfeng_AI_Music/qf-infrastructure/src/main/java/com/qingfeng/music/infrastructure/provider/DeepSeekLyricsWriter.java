package com.qingfeng.music.infrastructure.provider;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

public final class DeepSeekLyricsWriter implements LyricsWriter {
    private static final System.Logger LOG=System.getLogger(DeepSeekLyricsWriter.class.getName());
    private final String key,model;private final URI endpoint;private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper();
    public DeepSeekLyricsWriter(String key,String model){this(key,model,URI.create("https://api.deepseek.com/chat/completions"),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());}
    public DeepSeekLyricsWriter(String key,String model,URI endpoint,HttpClient client){this.key=key.trim();this.model=model;this.endpoint=endpoint;this.client=client;}
    public boolean isConfigured(){return !key.isBlank();}
    @Override public String source(){return "DEEPSEEK";}
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
        String system=LyricsWritingPrompt.system(language);
        var material=new LinkedHashMap<String,Object>();
        material.putAll(Map.of("recipient",work.recipient(),"occasion",work.occasion(),"story",work.story(),"message",work.message(),"style",work.style(),"preferredTitle",work.title(),"revisionInstructions",instructions,"previousLyrics",previousLyrics));
        material.put("musicDirection",direction.style());material.put("musicExclusions",direction.exclusions());material.put("vocalGender",direction.vocalGender());
        String context=json.writeValueAsString(material);
        String body=json.writeValueAsString(Map.of("model",model,"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",context)),"response_format",Map.of("type","json_object"),"thinking",Map.of("type","disabled"),"max_tokens",2400,"stream",false));
        try{
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90)).header("Authorization","Bearer "+key).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)LOG.log(System.Logger.Level.WARNING,"Lyrics provider HTTP failure: work={0}, status={1}",work.id(),response.statusCode());
            if(response.statusCode()==401||response.statusCode()==403)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词服务认证失败，请检查后端DeepSeek密钥");
            if(response.statusCode()==402)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","DeepSeek余额不足，请充值后重试");
            if(response.statusCode()!=200)throw new DomainException("LYRICS_PROVIDER_UNAVAILABLE","歌词生成服务暂时繁忙，请稍后再试");
            var choice=json.readTree(response.body()).path("choices").path(0);
            if(!choice.path("finish_reason").asText().equals("stop"))throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");
            var result=json.readTree(choice.path("message").path("content").asText());
            var plan=result.path("plan");
            return new Draft(result.path("title").asText(""),result.path("lyrics").asText(""),
                plan.isObject()?json.treeToValue(plan,com.qingfeng.music.settings.CreativePlan.class):null);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new DomainException("LYRICS_REQUEST_INTERRUPTED","歌词编写已中断，可以重新编写；已有内容仍会保留。");}
        catch(java.net.http.HttpTimeoutException timeout){throw new DomainException("LYRICS_PROVIDER_TIMEOUT","歌词编写超时，可以重新编写；已有内容仍会保留。");}
        catch(java.io.IOException network){throw new DomainException("LYRICS_PROVIDER_NETWORK_ERROR","歌词服务连接中断，可以稍后重新编写。");}
        catch(tools.jackson.core.JacksonException invalid){throw new DomainException("LYRICS_RESPONSE_INVALID","歌词生成结果不完整，请稍后重新发起");}
    }
}
