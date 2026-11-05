package com.qingfeng.music.server;

import com.qingfeng.music.lyrics.application.InspirationRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Import(InspirationRewriteHttpTest.Offline.class)
class InspirationRewriteHttpTest {
    static final AtomicInteger calls = new AtomicInteger();
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary InspirationRewriter fakeRewriter() {
            return new InspirationRewriter() {
                public boolean isConfigured() { return true; }
                public String rewrite(String text,String language) { calls.incrementAndGet(); return "保留原意，具体说明感谢爸爸平时的关心。"; }
            };
        }
    }
    @Value("${local.server.port}") int port;
    HttpResponse<String> send(HttpClient client,String method,String path,String body,String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (token != null) req.header("X-XSRF-TOKEN",token);
        if (body != null) req.header("Content-Type","application/json");
        return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void sessionAndCsrfAreRequiredAndRewriteDoesNotCreateWorkOrLyrics() throws Exception {
        calls.set(0); var json = new ObjectMapper();
        var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String input="{\"text\":\"我想感谢爸爸\",\"language\":\"zh-CN\"}";
        assertEquals(403,send(http,"POST","/api/app/inspiration/rewrite",input,null).statusCode());
        String csrf=json.readTree(send(http,"GET","/api/public/csrf",null,null).body()).path("token").asText();
        assertEquals(401,send(http,"POST","/api/app/inspiration/rewrite",input,csrf).statusCode());
        assertEquals(200,send(http,"POST","/api/public/session","{}",csrf).statusCode());
        assertEquals(403,send(http,"POST","/api/app/inspiration/rewrite",input,null).statusCode());
        assertEquals(400,send(http,"POST","/api/app/inspiration/rewrite",input.replace("我想感谢爸爸"," "),csrf).statusCode());
        assertEquals(400,send(http,"POST","/api/app/inspiration/rewrite",input.replace("zh-CN","invalid"),csrf).statusCode());
        assertEquals(400,send(http,"POST","/api/app/inspiration/rewrite",input.replace("我想感谢爸爸","x".repeat(4001)),csrf).statusCode());
        assertEquals(0,calls.get());
        var response=send(http,"POST","/api/app/inspiration/rewrite",input,csrf);
        assertEquals(200,response.statusCode(),response.body());
        assertTrue(json.readTree(response.body()).path("text").asText().contains("感谢爸爸")); assertEquals(1,calls.get());
        assertEquals("[]",send(http,"GET","/api/app/works",null,null).body());
    }
}
