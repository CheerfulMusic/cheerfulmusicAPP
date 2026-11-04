package com.qingfeng.music.server;
import com.qingfeng.music.infrastructure.provider.KieLyricsTimingProvider;
import com.qingfeng.music.lyrics.playback.LyricsTimingProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class KieLyricsTimingProviderTest {
    @Test void nestedUnifiedMusicResultSelectsTheArchivedClipBeforeOneTimingPost(){
        var json=new ObjectMapper();
        var nested=java.util.Map.of("resultObject",java.util.Map.of("lyricsData",java.util.List.of(
            java.util.Map.of("id","wrong-clip","audio_url","https://cdn.example/song.mp3?id=first&Expires=2"),
            java.util.Map.of("id","right-clip","audio_url","https://cdn.example/song.mp3?id=second&Expires=2"))));
        for(Object result:new Object[]{nested,json.writeValueAsString(nested)}){
            getReply=json.writeValueAsString(java.util.Map.of("code",200,"data",java.util.Map.of("taskId","task1","state","success","resultJson",result)));
            var words=provider.fetch("task1","https://cdn.example/song.mp3?id=second&Expires=1");
            assertEquals(1,words.size());assertEquals("right-clip",json.readTree(received).path("audioId").asText());
        }
        assertEquals(2,posts.get());assertEquals(2,gets.get());
    }
    @Test void matchingResourcePathCannotHideDifferentClipQueryIdentity(){
        getReply="""
            {"code":200,"data":{"taskId":"task1","state":"success","resultJson":{"resultObject":{"lyricsData":[{"id":"other","audio_url":"https://cdn.example/song.mp3?id=other"}]}}}}
            """;
        assertThrows(LyricsTimingProvider.Rejected.class,()->provider.fetch("task1","https://cdn.example/song.mp3?id=mine"));
        assertEquals(0,posts.get());assertEquals(1,gets.get());
    }
    HttpServer server;KieLyricsTimingProvider provider;AtomicInteger posts=new AtomicInteger(),gets=new AtomicInteger();String getReply=null;String received="",endpoint="",reply="{\"code\":200,\"data\":{\"alignedWords\":[{\"word\":\"hello\\n\",\"success\":true,\"startS\":2.3,\"endS\":5.6}]}}";
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));String response;
            if(exchange.getRequestMethod().equals("GET")){gets.incrementAndGet();response="{\"code\":200,\"data\":{\"taskId\":\"task1\",\"state\":\"success\",\"resultJson\":{\"data\":[{\"id\":\"wrong-version\",\"audio_url\":\"https://cdn.example/other.mp3\"},{\"id\":\"right-version\",\"audio_url\":\"https://cdn.example/song.mp3?Expires=2\"}]}}}";}
            else{posts.incrementAndGet();endpoint=exchange.getRequestURI().getPath();received=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);response=reply;}
            if(exchange.getRequestMethod().equals("GET")&&getReply!=null)response=getReply;
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();provider=create("compatible");
    }
    KieLyricsTimingProvider create(String mode){return new KieLyricsTimingProvider("test-key",mode,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());}
    @AfterEach void stop(){server.stop(0);}
    @Test void fetchesTimingForTheArchivedVersionAndDoesNotResubmitMusic(){
        var result=provider.fetch("task1","https://cdn.example/song.mp3?Expires=1");assertEquals(1,result.size());assertEquals(2.3,result.get(0).start());assertEquals(1,posts.get());assertEquals(1,gets.get());assertEquals("/api/v1/generate/get-timestamped-lyrics",endpoint);
        var json=new ObjectMapper().readTree(received);assertEquals("right-version",json.path("audioId").asText());assertEquals("task1",json.path("taskId").asText());
    }
    @Test void explicitFailureDoesNotAutomaticallyFallbackOrRetry(){reply="{\"code\":500,\"msg\":\"Connection refused\"}";assertThrows(LyricsTimingProvider.Rejected.class,()->provider.fetch("task1","https://cdn.example/song.mp3"));assertEquals(1,posts.get());}
    @Test void refusesAnUnmatchedAudioBeforePost(){assertThrows(LyricsTimingProvider.Rejected.class,()->provider.fetch("task1","https://different.example/unknown.mp3"));assertEquals(0,posts.get());}
    @Test void supportsConfiguredUnifiedResponseAndSnakeCaseTiming(){
        provider=create("unified");reply="{\"code\":200,\"data\":{\"code\":200,\"data\":{\"aligned_words\":[{\"word\":\"hello\",\"success\":true,\"start_s\":3,\"end_s\":4}]}}}";
        assertEquals(3,provider.fetch("task1","https://cdn.example/song.mp3").get(0).start());var request=new ObjectMapper().readTree(received);assertEquals("ai-music-api/timeStamped-lyrics",request.path("model").asText());assertEquals("right-version",request.path("input").path("audio_id").asText());
    }
}
