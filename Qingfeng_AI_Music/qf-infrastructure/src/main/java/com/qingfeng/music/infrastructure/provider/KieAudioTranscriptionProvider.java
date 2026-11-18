package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.*;

/** KIE Nemotron: word timestamps come from audio recognition, never from estimated song duration. */
public final class KieAudioTranscriptionProvider implements AudioTranscriptionProvider {
    private final String key;
    private final URI base;
    private final HttpClient http;
    private final Runnable createGate, readGate;
    private final AllowlistedAudioDownloader results;
    private final ObjectMapper json = new ObjectMapper();
    public KieAudioTranscriptionProvider(String key, Runnable creates, Runnable reads) {
        this(key, URI.create("https://api.kie.ai"), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), creates, reads,
                new AllowlistedAudioDownloader(Set.of("tempfile.aiquickdraw.com", "static.aiquickdraw.com")));
    }
    public KieAudioTranscriptionProvider(String key, URI base, HttpClient http, Runnable creates, Runnable reads,
            AllowlistedAudioDownloader results) {
        this.key=key.trim(); this.base=base; this.http=http; this.createGate=creates; this.readGate=reads; this.results=results;
    }
    public boolean isConfigured() { return !key.isBlank(); }
    public String submit(String audioUrl) {
        var response=send("/api/v1/jobs/createTask",json.writeValueAsString(Map.of("model","nvidia/nemotron-3-5-transcribe",
                "input",Map.of("audio_url",audioUrl,"timestamps",true))));
        String task=response.path("data").path("taskId").asText("");
        if(!task.matches("[A-Za-z0-9_-]{1,255}"))throw new IllegalStateException("音频识别提交结果未知");
        return task;
    }
    public Result poll(String task) {
        var data=send("/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(task,StandardCharsets.UTF_8),null).path("data");
        if(!task.equals(data.path("taskId").asText()))throw new IllegalStateException("音频识别任务不匹配");
        String state=data.path("state").asText("");
        if(state.equals("fail"))return new Result("FAILED",List.of());
        if(!state.equals("success"))return new Result("PENDING",List.of());
        var result=data.path("resultJson"); if(result.isTextual())result=json.readTree(result.asText());
        String url=result.path("resultObject").path("url").asText("");
        try(var input=results.open(url)) {
            byte[] bytes=input.readNBytes(4_000_001);
            if(bytes.length>4_000_000)throw new Rejected("RESULT_TOO_LARGE");
            var transcript=json.readTree(bytes);
            if(transcript.path("truncated").asBoolean(false))return new Result("REVIEW",List.of());
            var raw=transcript.path("words");
            if(!raw.isArray()||raw.size()>20000)throw new Rejected("INVALID_WORD_TIMING");
            var words=new ArrayList<PlaybackLyrics.Word>(); double previous=-1;
            for(var w:raw) {
                String text=w.path("word").asText("");double start=w.path("start").asDouble(Double.NaN),end=w.path("end").asDouble(Double.NaN);
                if(text.isBlank())continue;
                if(text.length()>1000||!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end>3600||start<previous)
                    throw new Rejected("INVALID_WORD_TIMING");
                previous=start; words.add(new PlaybackLyrics.Word(text,start,end));
            }
            return new Result(words.isEmpty() ? (transcript.path("text").asText("").isBlank() ? "EMPTY" : "REVIEW") : "READY",List.copyOf(words));
        }catch(java.io.IOException error){throw new IllegalStateException("音频识别结果读取失败",error);}
    }
    private JsonNode send(String path,String body) {
        if(body==null)readGate.run();else createGate.run();
        try {
            var request=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(55))
                    .header("Authorization","Bearer "+key).header("Accept","application/json");
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            // Transport/server ambiguity must not cause an automatic second POST.
            if(response.statusCode()>=500||response.body().length()>4_000_000)throw new IllegalStateException("音频识别请求结果未知");
            var parsed=json.readTree(response.body());int code=parsed.path("code").asInt(-1);
            if(response.statusCode()!=200||code!=200)throw new Rejected("PROVIDER_"+(response.statusCode()!=200?response.statusCode():code));
            return parsed;
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("音频识别请求中断",error);}
        catch(java.io.IOException|tools.jackson.core.JacksonException error){throw new IllegalStateException("音频识别请求结果未知",error);}
    }
}
