package com.qingfeng.music.server;

import com.qingfeng.music.infrastructure.provider.KieSunoMusicProvider;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.common.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class KieSunoProviderTest {
    @Test void inspirationBriefsSendTheirServerPromptsAndExplicitVoicesWithoutChangingLyrics(){
        response="{\"code\":200,\"data\":{\"taskId\":\"inspiration\"}}";
        for(var preset:com.qingfeng.music.settings.InspirationMusicPresets.all()){
            provider.submit(new MusicProvider.Request("歌名",request.confirmedLyrics(),preset.label(),preset.voice()),"preset-key");
            var input=new ObjectMapper().readTree(body).path("input");
            assertEquals(preset.prompt(),input.path("style").asText());assertEquals(request.confirmedLyrics(),input.path("lyrics").asText());
            if(preset.voice().equals("AUTO"))assertFalse(input.has("vocal_gender"));
            else assertEquals(preset.voice().equals("MALE")?"m":"f",input.path("vocal_gender").asText());
        }
        assertEquals(5,calls.get());
    }
    @Test void structuredDirectionIsSentExactlyWithSeparateExclusions(){
        response="{\"code\":200,\"data\":{\"taskId\":\"structured\"}}";
        var prepared=new MusicProvider.Request("title","lyrics","acoustic folk, warm female lead vocals","FEMALE","rap, heavy drums",true);
        provider.submit(prepared,"one");var input=new ObjectMapper().readTree(body).path("input");assertEquals(prepared.style(),input.path("style").asText());assertEquals("rap, heavy drums",input.path("negative_tags").asText());assertEquals("f",input.path("vocal_gender").asText());assertEquals(1,calls.get());
    }
    @Test void readsUnifiedNestedLyricsDataAndItsMatchingAudioMetadata(){
        var json=new ObjectMapper();
        var tracks=java.util.List.of(
            java.util.Map.of("id","clip-first","audio_url","https://audio.test/first.mp3","stream_audio_url","https://audio.test/stream-first","duration",183.42),
            java.util.Map.of("id","clip-second","audio_url","https://audio.test/second.mp3","duration",194.12));
        var nested=java.util.Map.of("resultObject",java.util.Map.of("code",200,"lyricsData",tracks));
        var approved=new java.util.ArrayList<String>();provider.approveAudioWith(approved::add);
        // 供应商resultJson可能为JSON字符串，也可能直接为对象。
        for(Object result:new Object[]{json.writeValueAsString(nested),nested}){
            response=json.writeValueAsString(java.util.Map.of("code",200,"data",java.util.Map.of("taskId","nested-task","state","success","resultJson",result)));
            var ready=provider.query("nested-task");assertEquals(GenerationStatus.SAVING,ready.status());
            assertEquals("https://audio.test/first.mp3",ready.temporaryAudioUrl());assertNotNull(ready.audioInfo());
            assertEquals("clip-first",ready.audioInfo().audioId());assertEquals(183.42,ready.audioInfo().duration());
            var second=provider.sourceInfo("nested-task","https://audio.test/second.mp3");assertNotNull(second);assertEquals("clip-second",second.audioId());
        }
        assertEquals(java.util.List.of("https://audio.test/first.mp3","https://audio.test/second.mp3","https://audio.test/first.mp3","https://audio.test/second.mp3"),approved);
    }
    @Test void nestedStreamingOnlyResultDoesNotBecomeCompletedAudio(){
        response="""
            {"code":200,"data":{"taskId":"stream-task","state":"success","resultJson":{"resultObject":{"lyricsData":[{"id":"clip","stream_audio_url":"https://audio.test/live","duration":120}]}}}}
            """;
        assertThrows(IllegalStateException.class,()->provider.query("stream-task"));assertEquals(1,calls.get());
    }
    @Test void sectionReplacementUsesOriginalIdentifiersAndBothLyricsContexts(){
        response="{\"code\":200,\"data\":{\"taskId\":\"edited-task\"}}";
        var edit=new MusicProvider.Replacement("original-task","original-clip",12.5,24.5,"换成这句歌词");
        assertEquals("edited-task",provider.replace(request,edit,"local-key").providerTaskId());
        var input=new ObjectMapper().readTree(body);assertEquals("ai-music-api/replace-section",input.path("model").asText());input=input.path("input");
        assertEquals("original-task",input.path("task_id").asText());assertEquals("original-clip",input.path("audio_id").asText());assertEquals(12.5,input.path("infill_start_s").asDouble());assertEquals(24.5,input.path("infill_end_s").asDouble());
        assertEquals(request.confirmedLyrics(),input.path("full_lyrics").asText());assertEquals("换成这句歌词",input.path("lyrics").asText());assertEquals(0.85,input.path("audio_weight").asDouble());assertEquals(1,calls.get());
    }
    @Test void sectionSourceLookupMatchesTheArchivedVariantAndPreservesIdentityQueryParameters(){
        response="{\"code\":200,\"data\":{\"taskId\":\"original\",\"state\":\"success\",\"resultJson\":{\"sunoData\":[{\"id\":\"wrong\",\"audioUrl\":\"https://audio.test/song?id=first&Expires=2\",\"duration\":120},{\"id\":\"correct\",\"audioUrl\":\"https://audio.test/song?id=second&Expires=3\",\"duration\":125}]}}}";
        var source=provider.sourceInfo("original","https://audio.test/song?id=second&Expires=1");assertNotNull(source);assertEquals("correct",source.audioId());assertEquals(125,source.duration());assertEquals(1,calls.get());
        assertNull(provider.sourceInfo("original","https://audio.test/song?id=missing"));
    }
    @Test void invalidSectionDoesNotCallProviderAndAmbiguousResponseIsNotRetried(){
        assertThrows(MusicProvider.Rejected.class,()->provider.replace(request,new MusicProvider.Replacement("task","clip",0,9,"歌词"),"key"));assertEquals(0,calls.get());
        status=500;assertThrows(IllegalStateException.class,()->provider.replace(request,new MusicProvider.Replacement("task","clip",0,10,"歌词"),"key"));assertEquals(1,calls.get());
    }
    HttpServer server; KieSunoMusicProvider provider;
    String body="", auth="", response="{}"; int status=200; AtomicInteger calls=new AtomicInteger();
    MusicProvider.Request request=new MusicProvider.Request("给妈妈的歌","[Verse]\n陪伴每个清晨","温暖民谣");
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{calls.incrementAndGet();auth=exchange.getRequestHeaders().getFirst("Authorization");body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start(); provider=new KieSunoMusicProvider("test-only-key","V6",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void sendsConfirmedLyricsWithoutRewritingOrAutomaticRetries() {
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        assertEquals("task1",provider.submit(request,"local-key").providerTaskId());
        var json=new ObjectMapper().readTree(body);
        assertEquals("ai-music-api/generate",json.path("model").asText());
        assertEquals(request.confirmedLyrics(),json.path("input").path("lyrics").asText());
        assertTrue(json.path("input").path("custom_mode").asBoolean());
        assertFalse(json.path("input").path("instrumental").asBoolean());
        assertEquals("Bearer test-only-key",auth);assertEquals(1,calls.get());
        assertFalse(json.path("input").has("vocal_gender"));
        var input=json.path("input");
        assertTrue(input.path("style").asText().contains("fingerpicked acoustic guitar"));
        assertEquals(0.85,input.path("style_weight").asDouble());
        assertEquals(0.25,input.path("weirdness_constraint").asDouble());
        assertEquals(0,input.path("variety").asInt());
    }
    @Test void rejectsOversizeLyricsBeforeNetwork(){assertThrows(DomainException.class,()->provider.submit(new MusicProvider.Request("title","字".repeat(5001),"folk"),"key"));assertEquals(0,calls.get());}
    @Test void sendsExplicitMaleAndFemalePreferencesToKie(){
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        provider.submit(new MusicProvider.Request("歌名","歌词","民谣","MALE"),"male");assertEquals("m",new ObjectMapper().readTree(body).path("input").path("vocal_gender").asText());
        provider.submit(new MusicProvider.Request("歌名","歌词","民谣","FEMALE"),"female");assertEquals("f",new ObjectMapper().readTree(body).path("input").path("vocal_gender").asText());assertEquals(2,calls.get());
    }
    @Test void carriesStructuredTailThroughPresetExpansionWithoutDuplication(){
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        var prepared=provider.prepare(new MusicProvider.Request("歌名","歌词","温暖民谣 | 90 BPM, piano, Mandarin vocals","FEMALE"));
        assertTrue(prepared.preparedStyle());
        // 基础风格仍命中预设词典翻成英文，补充描述原样跟随，且只出现一次。
        assertTrue(prepared.style().contains("Warm acoustic folk ballad"));
        assertTrue(prepared.style().endsWith(" | 90 BPM, piano, Mandarin vocals"));
        assertEquals(1,prepared.style().split("90 BPM",-1).length-1);
        provider.submit(prepared,"tail");
        var style=new ObjectMapper().readTree(body).path("input").path("style").asText();
        assertEquals(prepared.style(),style);
        assertEquals("f",new ObjectMapper().readTree(body).path("input").path("vocal_gender").asText());
    }
    @Test void keepsBaseWhenTheStructuredTailExceedsTheStyleBudget(){
        String tail="x".repeat(900)+", "+"y".repeat(400);
        var prepared=provider.prepare(new MusicProvider.Request("歌名","歌词","温暖民谣 | "+tail,"AUTO"));
        assertTrue(prepared.style().length()<=provider.limits().style());
        assertTrue(prepared.style().startsWith("Warm acoustic folk ballad"));
        assertFalse(prepared.style().contains("y".repeat(400)));
    }
    @Test void refusesInvalidVoiceBeforeAnyNetworkCall(){assertThrows(DomainException.class,()->provider.submit(new MusicProvider.Request("歌名","歌词","民谣","unknown"),"key"));assertEquals(0,calls.get());}
    @Test void expandsAllPresetsWithoutRewritingLyricsOrForcingVoiceGender(){
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        String[] styles={"温暖民谣","抒情流行","轻柔爵士","浪漫钢琴","治愈轻音乐","清新校园","国风古韵","梦幻电子","轻快流行","复古港风","R&B节奏蓝调","独立摇滚","柔和声乐","电影配乐","浪漫弦乐","轻快乡村","甜蜜情歌","童趣儿歌","舒缓Lo-fi","清新波萨诺瓦","热情拉丁","轻快说唱","悠扬古典","空灵氛围"};
        var prompts=new java.util.HashSet<String>();
        for(String style:styles){
            provider.submit(new MusicProvider.Request("歌名",request.confirmedLyrics(),style),"key");
            var input=new ObjectMapper().readTree(body).path("input");String expanded=input.path("style").asText();
            assertTrue(expanded.contains("BPM"),style);assertTrue(expanded.length()>80&&expanded.length()<=1000,style);
            assertTrue(prompts.add(expanded),"不同风格需要不同的编曲描述");
            assertFalse(input.has("vocal_gender"));assertFalse(input.path("instrumental").asBoolean());
            assertEquals(request.confirmedLyrics(),input.path("lyrics").asText());
        }
        assertEquals(24,calls.get());
    }
    @Test void preservesCustomDirectionAndReinforcesTheExplicitVocalChoice(){
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        String custom="温暖民谣，但不用吉他；用钢琴，95 BPM";
        provider.submit(new MusicProvider.Request("歌名","歌词",custom),"key");
        assertEquals(custom,new ObjectMapper().readTree(body).path("input").path("style").asText());
        provider.submit(new MusicProvider.Request("歌名","歌词",custom,"FEMALE"),"key2");
        String actual=new ObjectMapper().readTree(body).path("input").path("style").asText();
        assertTrue(actual.startsWith(custom));assertTrue(actual.contains("Female lead vocals"));assertFalse(actual.contains("fingerpicked"));
        provider.submit(new MusicProvider.Request("歌名","歌词","轻柔爵士","MALE"),"key3");
        actual=new ObjectMapper().readTree(body).path("input").path("style").asText();
        assertTrue(actual.contains("gentle swing"));assertTrue(actual.contains("Male lead vocals"));
    }
    @Test void rejectsInvalidTuningAndOverlongExpandedStyleBeforeNetwork(){
        assertThrows(IllegalArgumentException.class,()->new KieSunoMusicProvider.Tuning(Double.NaN,0.25,0));
        assertThrows(IllegalArgumentException.class,()->new KieSunoMusicProvider.Tuning(1.1,0.25,0));
        assertThrows(IllegalArgumentException.class,()->new KieSunoMusicProvider.Tuning(0.8,-0.1,0));
        assertThrows(IllegalArgumentException.class,()->new KieSunoMusicProvider.Tuning(0.8,0.25,5));
        assertThrows(DomainException.class,()->provider.submit(new MusicProvider.Request("歌名","歌词","x".repeat(1000),"FEMALE"),"key"));assertEquals(0,calls.get());
    }
    @Test void acceptsConfigurableTuningWithinTheDocumentedRange(){
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\"}}";
        var configured=new KieSunoMusicProvider("test-only-key","V6",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient(),new KieSunoMusicProvider.Tuning(0.7,0.3,1));
        configured.submit(request,"key");var input=new ObjectMapper().readTree(body).path("input");assertEquals(0.7,input.path("style_weight").asDouble());assertEquals(0.3,input.path("weirdness_constraint").asDouble());assertEquals(1,input.path("variety").asInt());
    }
    @Test void explicitRejectionIsDifferentFromUnknownSubmission(){response="{\"code\":402}";assertThrows(MusicProvider.Rejected.class,()->provider.submit(request,"key"));status=500;assertThrows(IllegalStateException.class,()->provider.submit(request,"key"));assertEquals(2,calls.get());}
    @Test void mapsPendingFailureAndCompleteResults(){
        var approved=new java.util.ArrayList<String>();provider.approveAudioWith(approved::add);
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\",\"state\":\"generating\"}}";assertEquals(GenerationStatus.RUNNING,provider.query("task1").status());
        response=response.replace("generating","fail");assertEquals(GenerationStatus.FAILED,provider.query("task1").status());
        response="{\"code\":200,\"data\":{\"taskId\":\"task1\",\"state\":\"success\",\"resultJson\":\"{\\\"resultUrls\\\":[\\\"https://cdn.example/song.mp3\\\"]}\"}}";
        assertEquals("https://cdn.example/song.mp3",provider.query("task1").temporaryAudioUrl());
        assertEquals(java.util.List.of("https://cdn.example/song.mp3"),approved);
    }
    @Test void refusesMismatchedTaskAndMissingSubmissionId(){response="{\"code\":200,\"data\":{\"taskId\":\"other\",\"state\":\"success\"}}";assertThrows(IllegalStateException.class,()->provider.query("task1"));response="{\"code\":200}";assertThrows(IllegalStateException.class,()->provider.submit(request,"key"));}
}
