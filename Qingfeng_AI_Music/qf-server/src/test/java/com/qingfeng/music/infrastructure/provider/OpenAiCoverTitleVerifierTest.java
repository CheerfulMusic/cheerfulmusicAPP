package com.qingfeng.music.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiCoverTitleVerifierTest {
    @Test void transcribesIndependentlyAndNeverTreatsAnOutageAsWrongLettering() throws Exception {
        var json=new ObjectMapper();
        var title=new AtomicReference<>("偏偏\n很快乐");
        var status=new AtomicInteger(200);
        var payload=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var recognized=json.writeValueAsString(Map.of("title",title.get(),"readable",true));
            byte[] result=json.writeValueAsBytes(Map.of("status","completed","output",List.of(
                    Map.of("type","message","role","assistant","status","completed","content",
                            List.of(Map.of("type","output_text","text",recognized))))));
            exchange.sendResponseHeaders(status.get(),result.length);
            try (var output=exchange.getResponseBody()) { output.write(result); }
        });
        server.start();
        try {
            var verifier=new OpenAiCoverTitleVerifier("test","gpt-6.1-sol",
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),HttpClient.newHttpClient());
            assertTrue(verifier.matches(new byte[]{1,2,3},"偏偏很快乐"));
            assertFalse(payload.get().contains("偏偏很快乐"),"Do not provide the expected text to the visual recognizer");
            assertFalse(json.readTree(payload.get()).path("store").asBoolean());
            assertEquals("low",json.readTree(payload.get()).path("reasoning").path("effort").asText());
            title.set("偏偏/很快乐");
            assertFalse(verifier.matches(new byte[]{1},"偏偏很快乐"));
            title.set("偏偏很快东");
            assertFalse(verifier.matches(new byte[]{1},"偏偏很快乐"));
            status.set(503);
            assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"偏偏很快乐"));
        } finally { server.stop(0); }
    }
}
