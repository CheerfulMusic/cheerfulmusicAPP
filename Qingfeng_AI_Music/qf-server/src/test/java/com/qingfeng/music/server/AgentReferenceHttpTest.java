package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.ReferenceAnalyst;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** 接口层契约：鉴权、参数校验、输出结构与降级错误码。模型本身用 @Primary 替身，不伪造分析结论。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Import(AgentReferenceHttpTest.Offline.class)
class AgentReferenceHttpTest {
    static final AtomicReference<ReferenceAnalyst.Request> captured = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods = false) static class Offline {
        @Bean @Primary ReferenceAnalyst fakeReferenceAnalyst() {
            return new ReferenceAnalyst() {
                public boolean isConfigured() { return true; }
                public Suggestion analyse(Request request) {
                    captured.set(request);
                    return new Suggestion("电影配乐", "有画面感", 90, java.util.List.of("弦乐"), java.util.List.of("电音"),
                        "参考音乐偏电影配乐方向，适合叙事。");
                }
            };
        }
    }

    @Value("${local.server.port}") int port;

    private HttpResponse<String> send(HttpClient client, String path, String body, String token) throws Exception {
        return send(client, "POST", path, body, token);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("X-XSRF-TOKEN", token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String payload(String locale, double bpm, String text) {
        return "{\"text\":\"" + text + "\",\"fileName\":\"ref.m4a\",\"duration\":128,\"locale\":\"" + locale
            + "\",\"features\":{\"bpm\":" + bpm + ",\"bpmConfidence\":0.82,\"rms\":0.31,\"peak\":0.87,\"dynamicRange\":0.42,"
            + "\"low\":0.52,\"mid\":0.31,\"high\":0.17,\"brightness\":0.63,\"noisiness\":0.21,\"duration\":128,\"analyzedSeconds\":90}}";
    }

    @Test void requiresSessionAndCsrfAndValidatesBounds() throws Exception {
        captured.set(null);
        var json = new ObjectMapper();
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String valid = payload("zh-CN", 128.4, "想写一首给妈妈的歌");

        assertEquals(403, send(http, "/api/app/agent/reference", valid, null).statusCode());
        String csrf = json.readTree(send(http, "GET", "/api/public/csrf", null, null).body()).path("token").asText();
        assertEquals(401, send(http, "/api/app/agent/reference", valid, csrf).statusCode());
        assertEquals(200, send(http, "/api/public/session", "{}", csrf).statusCode());

        assertEquals(400, send(http, "/api/app/agent/reference", payload("fr", 128.4, "x"), csrf).statusCode());
        assertEquals(400, send(http, "/api/app/agent/reference", payload("zh-CN", 999, "x"), csrf).statusCode());
        assertEquals(400, send(http, "/api/app/agent/reference", "{\"locale\":\"zh-CN\",\"duration\":128}", csrf).statusCode());
        assertEquals(400, send(http, "/api/app/agent/reference", payload("zh-CN", 128.4, "x".repeat(401)), csrf).statusCode());
        assertNull(captured.get(), "非法请求不应该触发模型调用");
    }

    @Test void returnsStructuredAnalysisAndKeepsMeasuredTempo() throws Exception {
        captured.set(null);
        var json = new ObjectMapper();
        var http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(send(http, "GET", "/api/public/csrf", null, null).body()).path("token").asText();
        assertEquals(200, send(http, "/api/public/session", "{}", csrf).statusCode());

        var response = send(http, "/api/app/agent/reference", payload("zh-CN", 128.4, "想写一首给妈妈的歌"), csrf);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertEquals("电影配乐", body.path("style").asText());
        assertEquals("有画面感", body.path("mood").asText());
        // 模型给 90，测量 128.4，偏差超 8：接口必须返回测量值。
        assertEquals(128.4, body.path("tempo").asDouble());
        assertTrue(body.path("tempoVerified").asBoolean());
        assertEquals("弦乐", body.path("inferredInstrumentation").path(0).asText());
        assertEquals("电音", body.path("negativeTags").path(0).asText());
        assertEquals("AI", body.path("source").asText());
        assertEquals(8, body.size(), "响应只应包含约定的 8 个字段");

        var request = captured.get();
        assertNotNull(request);
        assertEquals(128.4, request.features().bpm());
        assertEquals("想写一首给妈妈的歌", request.text());
        assertEquals("zh-CN", request.locale());
    }
}
