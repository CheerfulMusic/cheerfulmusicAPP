package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.application.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** H2 + 真实 HTTP：同一 QF_SESSION 会话稳定、跨 creator 隔离、消息回放与 revision 冲突。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "qingfeng.agent.chat.enabled=true")
@ActiveProfiles("test")
class AgentConversationHttpTest {

    @Value("${local.server.port}") int port;
    @Autowired ConversationService service;

    private record Browser(HttpClient http, String csrf, String creatorId) {}

    private HttpResponse<String> send(HttpClient client, String method, String path,
                                      String body, String csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String field(String json, String key) {
        var matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), "缺少字段：" + key + " -> " + json);
        return matcher.group(1);
    }

    private Browser browser() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> csrf = send(http, "GET", "/api/public/csrf", null, null);
        assertEquals(200, csrf.statusCode());
        String token = field(csrf.body(), "token");
        HttpResponse<String> session = send(http, "POST", "/api/public/session", "{}", token);
        assertEquals(200, session.statusCode(), session.body());
        return new Browser(http, token, field(session.body(), "creatorId"));
    }

    private String currentId(Browser browser) throws Exception {
        HttpResponse<String> response = send(browser.http(), "GET",
                "/api/app/agent/conversations/current", null, null);
        assertEquals(200, response.statusCode(), response.body());
        return field(response.body(), "id");
    }

    @Test
    void sameCreatorKeepsStableConversationIdAndOtherCreatorsAreIsolated() throws Exception {
        Browser a = browser();
        String first = currentId(a);
        assertEquals(first, currentId(a));
        assertNotEquals(first, currentId(browser()));
    }

    @Test
    void appendedMessagesAreReplayedInOrder() throws Exception {
        Browser a = browser();
        String id = currentId(a);
        service.appendMessage(a.creatorId(), id, "user", AgentMessage.KIND_TEXT,
                AgentMessage.STATE_DONE, "{\"text\":\"写一首关于旅行的歌\"}", null);
        service.appendMessage(a.creatorId(), id, "assistant", AgentMessage.KIND_PLACEHOLDER,
                AgentMessage.STATE_PENDING, "{}", "task-1");
        HttpResponse<String> response = send(a.http(), "GET",
                "/api/app/agent/conversations/" + id + "/messages", null, null);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"seq\":1"));
        assertTrue(response.body().contains("\"seq\":2"));
        assertTrue(response.body().contains("PLACEHOLDER"));
        assertTrue(response.body().contains("PENDING"));
    }

    @Test
    void resetAndDeleteClearMessagesButKeepConversationId() throws Exception {
        Browser a = browser();
        String id = currentId(a);
        service.appendMessage(a.creatorId(), id, "user", AgentMessage.KIND_TEXT,
                AgentMessage.STATE_DONE, "{}", null);
        assertEquals(200, send(a.http(), "POST", "/api/app/agent/conversations/" + id + "/reset",
                "{}", a.csrf()).statusCode());
        assertEquals(id, currentId(a));
        assertTrue(send(a.http(), "GET", "/api/app/agent/conversations/" + id + "/messages",
                null, null).body().equals("[]"));

        service.appendMessage(a.creatorId(), id, "user", AgentMessage.KIND_TEXT,
                AgentMessage.STATE_DONE, "{}", null);
        assertEquals(200, send(a.http(), "DELETE", "/api/app/agent/conversations/" + id,
                null, a.csrf()).statusCode());
        assertEquals(id, currentId(a));
    }

    @Test
    void staleRevisionIsRejectedByTheStateTable() throws Exception {
        Browser a = browser();
        String id = currentId(a);
        long revision = service.state(a.creatorId(), id).revision();
        service.applyToolResult(a.creatorId(), id, "m1", "{}", "work-1", "{\"title\":\"a\"}",
                null, revision);
        DomainException error = assertThrows(DomainException.class,
                () -> service.applyToolResult(a.creatorId(), id, "m2", "{}", "work-2",
                        "{\"title\":\"b\"}", null, revision));
        assertEquals(ConversationService.CONFLICT, error.code());
        // W2 的 AgentToolController 会把该异常映射为 HTTP 409。
        assertEquals(revision + 1, service.state(a.creatorId(), id).revision());
    }
}
