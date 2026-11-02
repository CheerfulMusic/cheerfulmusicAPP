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
    }
    @Test void rejectsOversizeLyricsBeforeNetwork(){assertThrows(DomainException.class,()->provider.submit(new MusicProvider.Request("title","字".repeat(5001),"folk"),"key"));assertEquals(0,calls.get());}
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
