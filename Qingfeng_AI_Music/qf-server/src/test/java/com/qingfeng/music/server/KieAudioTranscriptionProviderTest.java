package com.qingfeng.music.server;

import com.qingfeng.music.infrastructure.provider.KieAudioTranscriptionProvider;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.lyrics.playback.AudioTranscriptionProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KieAudioTranscriptionProviderTest {
    HttpServer server;String response;int status;AtomicReference<String> body;AllowlistedAudioDownloader files;
    KieAudioTranscriptionProvider provider;
    @BeforeEach void start()throws Exception {
        response="{\"code\":200,\"data\":{\"taskId\":\"test-task\"}}";status=200;body=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{body.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});server.start();
        files=mock(AllowlistedAudioDownloader.class);
        provider=new KieAudioTranscriptionProvider("local-test-key",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient(),()->{},()->{},files);
    }
    @AfterEach void stop(){server.stop(0);}
    void result(String text)throws Exception {
        response="{\"code\":200,\"data\":{\"taskId\":\"test-task\",\"state\":\"success\",\"resultJson\":\"{\\\"resultObject\\\":{\\\"url\\\":\\\"https://tempfile.aiquickdraw.com/test.json\\\"}}\"}}";
        when(files.open("https://tempfile.aiquickdraw.com/test.json")).thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void submissionRequestsWordTimestampsAndReturnsDurableTaskId(){assertEquals("test-task",provider.submit("https://audio.example/song.mp3"));assertTrue(body.get().contains("\"timestamps\":true"));assertTrue(body.get().contains("nvidia/nemotron-3-5-transcribe"));}
    @Test void successfulResultContainsActualWordTimes()throws Exception{result("{\"text\":\"Hello\",\"words\":[{\"word\":\"Hello\",\"start\":2.4,\"end\":3.1}]}");var r=provider.poll("test-task");assertEquals("READY",r.state());assertEquals(2.4,r.words().get(0).start());}
    @Test void truncatedOrUntimedTranscriptNeedsReviewInsteadOfBeingPublished()throws Exception {
        result("{\"truncated\":true,\"text\":\"repetition\",\"words\":[]}");assertEquals("REVIEW",provider.poll("test-task").state());
        result("{\"text\":\"中文识别有文字但没有时间\",\"words\":[]}");assertEquals("REVIEW",provider.poll("test-task").state());
    }
    @Test void malformedWordTimingIsRejected()throws Exception{result("{\"words\":[{\"word\":\"bad\",\"start\":3,\"end\":2}]}");assertThrows(AudioTranscriptionProvider.Rejected.class,()->provider.poll("test-task"));}
    @Test void modelAuthorizationIsAnExplicitRejection(){response="{\"code\":401,\"data\":null}";assertEquals("PROVIDER_401",assertThrows(AudioTranscriptionProvider.Rejected.class,()->provider.submit("https://example.org/test.mp3")).code);}
    @Test void httpAuthorizationWithoutProviderCodeRemainsTerminal(){status=401;response="{\"message\":\"Unauthorized\"}";assertEquals("PROVIDER_401",assertThrows(AudioTranscriptionProvider.Rejected.class,()->provider.submit("https://example.org/test.mp3")).code);}
    @Test void mismatchedTaskIsNotAccepted(){response="{\"code\":200,\"data\":{\"taskId\":\"another-task\",\"state\":\"success\"}}";assertThrows(IllegalStateException.class,()->provider.poll("test-task"));}
}
