package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.lyrics.playback.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.*;

/** 精确匹配归档的音频版本。兼容接口与统一接口由配置选择，不自动回退或重复 POST。 */
public final class KieLyricsTimingProvider implements LyricsTimingProvider {
    private final String key,mode;private final URI base;private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper();
    public KieLyricsTimingProvider(String key,String mode){this(key,mode,URI.create("https://api.kie.ai"),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());}
    public KieLyricsTimingProvider(String key,String mode,URI base,HttpClient client){
        this.key=key.trim();this.mode=mode;this.base=base;this.client=client;
        if(!Set.of("compatible","unified").contains(mode))throw new IllegalArgumentException("歌词时间轴接口模式无效");
    }
    public boolean isConfigured(){return !key.isBlank();}
    public List<PlaybackLyrics.Word> fetch(String task,String archived){
        var data=send("/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(task,StandardCharsets.UTF_8),null).path("data");
        if(!task.equals(data.path("taskId").asText())||!data.path("state").asText().equals("success"))throw new Rejected();
        var result=data.path("resultJson");if(result.isTextual())result=json.readTree(result.asText());
        String audioId=trackId(result,archived);
        if(audioId.isBlank())throw new Rejected();
        boolean compatible=mode.equals("compatible");
        var payload=compatible?Map.of("taskId",task,"audioId",audioId):Map.of("model","ai-music-api/timeStamped-lyrics","input",Map.of("task_id",task,"audio_id",audioId));
        var response=send(compatible?"/api/v1/generate/get-timestamped-lyrics":"/api/v1/jobs/createTask",json.writeValueAsString(payload));
        var timed=compatible?response.path("data"):response.path("data").path("data");
        if(!compatible&&response.path("data").path("code").asInt(-1)!=200)throw new Rejected();
        var aligned=timed.path("alignedWords");if(!aligned.isArray())aligned=timed.path("aligned_words");
        if(!aligned.isArray())throw new Rejected();
        if(aligned.size()>20000)throw new Rejected();
        var words=new ArrayList<PlaybackLyrics.Word>();
        for(var w:aligned){
            if(w.has("success")&&!w.path("success").asBoolean())continue;
            String text=w.path("word").asText("");
            double start=w.path("startS").asDouble(w.path("start_s").asDouble(Double.NaN)),end=w.path("endS").asDouble(w.path("end_s").asDouble(Double.NaN));
            if(!text.isBlank()&&text.length()<=12000&&Double.isFinite(start)&&Double.isFinite(end)&&start>=0&&end>start&&end<=86400)words.add(new PlaybackLyrics.Word(text,start,end));
        }
        return List.copyOf(words);
    }
    private String trackId(JsonNode result,String archived){
        for(var candidates:KieAudioResults.groups(result)){
            for(var track:candidates){String audio=track.path("audioUrl").asText(track.path("audio_url").asText(""));
                if(KieAudioResults.sameAudio(audio,archived)){String id=track.path("id").asText(track.path("audioId").asText(track.path("audio_id").asText("")));if(id.matches("[A-Za-z0-9_-]{1,255}"))return id;}
            }
        }
        return "";
    }
    private JsonNode send(String path,String body){
        try{
            var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(55)).header("Authorization","Bearer "+key).header("Accept","application/json");
            if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>4_000_000)throw new Rejected();
            var result=json.readTree(response.body());if(result.path("code").asInt(-1)!=200)throw new Rejected();return result;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("时间轴请求结果未知",interrupted);}
        catch(java.io.IOException|tools.jackson.core.JacksonException error){throw new IllegalStateException("时间轴请求结果未知",error);}
    }
}
