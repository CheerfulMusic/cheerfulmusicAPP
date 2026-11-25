package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:wechat_card_web;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.flyway.target=44", "qingfeng.invite-gate.enabled=false",
    "qingfeng.wechat.official.self-card.enabled=true", "qingfeng.wechat.official.self-card.account-name=测试公众号",
    "qingfeng.wechat.official.self-card.callback-token=fixture-token",
    "qingfeng.wechat.official.self-card.encoding-aes-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    "qingfeng.wechat.official.app-id=wx-fixture", "qingfeng.wechat.official.app-secret=fixture-app-secret"
})
@ActiveProfiles("test")
@Import(WechatSelfCardHttpTest.Fixtures.class)
class WechatSelfCardHttpTest {
    private static final String SONG = "A".repeat(43);
    private static final String PATH = "/song/" + SONG;
    private static final String TOKEN = "fixture-token";
    private static final String APP_ID = "wx-fixture";
    private static final String CALLBACK = WechatSelfCardController.CALLBACK;
    private static final String BINDING = "/api/app/wechat-card/binding";
    private static final String SEND = "/api/app/wechat-card/send";
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    @Autowired DataSource source;
    @Autowired IdentityApi identity;
    @Autowired WechatOfficialClient provider;
    @Value("${local.server.port}") int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary WechatOfficialClient cardProvider() {
            var client = mock(WechatOfficialClient.class);
            when(client.configured()).thenReturn(true);
            when(client.appId()).thenReturn(APP_ID);
            when(client.sendNews(anyString(), any())).thenReturn(WechatOfficialClient.Delivery.SENT);
            return client;
        }
        @Bean @Primary PublicSongAccess shareFixture() {
            var access = mock(PublicSongAccess.class);
            when(access.require(SONG)).thenReturn(new PublicSongAccess.Grant("id", "测试歌曲", "测试歌手", "w", "a", "c", "owner", null, 0));
            return access;
        }
    }

    @Test void verifiesPushAndAllowsOnlyTheLinkedAccountsCsrfProtectedSend() throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V58__wechat_self_card.sql")).execute(source);
        var enabled = request("GET", WechatSelfCardController.AVAILABILITY, null, null, null);
        assertEquals(200, enabled.statusCode());
        assertEquals("测试公众号", json.readTree(enabled.body()).path("accountName").asString());
        assertEquals(401, request("GET", BINDING, null, null, null).statusCode());

        var guest = identity.create();
        var login = identity.signInWithGoogle(guest.token(), guest.creatorId(), "fixture-subject", "fixture@example.test", "Fixture");
        String session = "QF_SESSION=" + login.token();
        var csrf = request("GET", "/api/public/csrf", null, session, null);
        String csrfToken = json.readTree(csrf.body()).path("token").asString();
        String cookies = session + "; " + csrf.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";", 2)[0];
        assertEquals(403, request("POST", BINDING, "{}", cookies, null).statusCode());
        var challenge = request("POST", BINDING, "{}", cookies, csrfToken);
        assertEquals(200, challenge.statusCode(), challenge.body());
        String command = json.readTree(challenge.body()).path("command").asString();
        assertTrue(command.matches("绑定卡片 [A-F0-9]{16}"));

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String query = "?timestamp=" + timestamp + "&nonce=fixture";
        var check = request("GET", CALLBACK + query + "&echostr=fixture-echo&signature=" + signature(TOKEN, timestamp, "fixture"), null, null, null);
        assertEquals("fixture-echo", check.body());
        String encrypted = encryptedPush(command, Long.parseLong(timestamp), APP_ID);
        String callbackBody = json.writeValueAsString(Map.of("Encrypt", encrypted));
        String push = CALLBACK + query + "&encrypt_type=aes&msg_signature=";
        assertEquals(403, request("POST", push + "0".repeat(40), callbackBody, null, null).statusCode());
        String wrongAccount = encryptedPush(command, Long.parseLong(timestamp), "another-account");
        assertEquals(403, request("POST", push + signature(TOKEN, timestamp, "fixture", wrongAccount),
                json.writeValueAsString(Map.of("Encrypt", wrongAccount)), null, null).statusCode());
        String signed = push + signature(TOKEN, timestamp, "fixture", encrypted);
        assertEquals("success", request("POST", signed, callbackBody, null, null).body());
        assertEquals("success", request("POST", signed, callbackBody, null, null).body());
        assertTrue(json.readTree(request("GET", BINDING, null, cookies, null).body()).path("bound").asBoolean());

        String card = json.writeValueAsString(Map.of("requestId", UUID.randomUUID(), "path", PATH));
        assertEquals(403, request("POST", SEND, card, cookies, null).statusCode());
        for (int i = 0; i < 2; i++) {
            var sent = request("POST", SEND, card, cookies, csrfToken);
            assertEquals(200, sent.statusCode(), sent.body());
            assertEquals("SENT", json.readTree(sent.body()).path("state").asString());
        }
        verify(provider).sendNews("fixture-openid", new WechatOfficialClient.News("测试歌曲 · 测试歌手",
                PublicSongController.DESCRIPTION, "https://cheerfulmusic.com" + PATH,
                "https://cheerfulmusic.com/api/songs/" + SONG + "/thumbnail.png"));
        assertEquals(200, request("DELETE", BINDING, null, cookies, csrfToken).statusCode());
        assertFalse(json.readTree(request("GET", BINDING, null, cookies, null).body()).path("bound").asBoolean());
    }

    private HttpResponse<String> request(String method, String path, String body, String cookie, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String encryptedPush(String command, long timestamp, String receiverApp) throws Exception {
        byte[] message = json.writeValueAsBytes(Map.of("MsgType", "text", "FromUserName", "fixture-openid",
                "CreateTime", timestamp, "Content", command));
        byte[] app = receiverApp.getBytes(StandardCharsets.UTF_8);
        int length = 20 + message.length + app.length;
        int padding = 32 - length % 32;
        var block = ByteBuffer.allocate(length + padding).put(new byte[16]).putInt(message.length).put(message).put(app);
        Arrays.fill(block.array(), length, block.capacity(), (byte) padding);
        byte[] key = new byte[32];
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(block.array()));
    }

    private static String signature(String... fields) throws Exception {
        Arrays.sort(fields);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("", fields).getBytes(StandardCharsets.UTF_8)));
    }
}
