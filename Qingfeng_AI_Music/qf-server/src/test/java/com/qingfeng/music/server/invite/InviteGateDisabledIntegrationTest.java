package com.qingfeng.music.server.invite;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "qingfeng.invite-gate.enabled=false")
@ActiveProfiles("test")
class InviteGateDisabledIntegrationTest {
    @Value("${local.server.port}") int port;
    @Test void configurationSwitchRestoresOrdinaryAccessWithoutInvitationSecrets() throws Exception {
        var http = HttpClient.newHttpClient();
        var status = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/public/invite/status")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, status.statusCode()); assertTrue(status.body().contains("\"enabled\":false")); assertTrue(status.body().contains("\"admitted\":true"));
        var info = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/public/system/info")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, info.statusCode());
    }
}
