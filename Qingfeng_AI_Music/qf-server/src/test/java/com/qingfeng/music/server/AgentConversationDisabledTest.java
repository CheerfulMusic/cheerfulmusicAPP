package com.qingfeng.music.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 验收 5：开关默认 false 时所有 W1 新端点不注册，既有 /agent 行为不受影响。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AgentConversationDisabledTest {

    @Value("${local.server.port}") int port;

    @Test
    void newEndpointsReturn404WhenSwitchIsOff() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/public/csrf")).build(),
                HttpResponse.BodyHandlers.ofString());
        var matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf.body());
        assertTrue(matcher.find());
        String token = matcher.group(1);
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/public/session"))
                        .header("X-XSRF-TOKEN", token).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());

        for (String path : new String[]{
                "/api/app/agent/conversations/current",
                "/api/app/agent/conversations/any-id/messages"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), path + " 在开关关闭时不应注册");
        }
    }
}
