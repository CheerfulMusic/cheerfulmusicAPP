package com.qingfeng.music.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

// The scaffold test must not inherit the developer's private beta-gate switch.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "qingfeng.invite-gate.enabled=false")
@ActiveProfiles("local")
class BootstrapIntegrationTest {
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void localProfileStartsWithoutDatabaseAndReportsUnavailableFeatures() throws Exception {
        var response = get("/api/public/system/info");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"musicGeneration\":false"));
        assertTrue(response.body().contains("SCAFFOLD"));
    }
    @Test void onlyHealthIsExposedThroughActuator() throws Exception {
        assertEquals(200, get("/actuator/health").statusCode());
        assertEquals(403, get("/actuator/env").statusCode());
    }
    @Test void rechargePageIsAccessibleOnDirectNavigation() throws Exception {
        assertEquals(200, get("/recharge").statusCode());
    }
    @Test void analyticsPageIsAccessibleOnDirectNavigation() throws Exception {
        assertEquals(200, get("/analytics").statusCode());
    }
    @Test void privateBusinessRoutesAreClosedUntilAuthenticationIsImplemented() throws Exception {
        assertEquals(401, get("/api/app/works").statusCode());
    }
}
