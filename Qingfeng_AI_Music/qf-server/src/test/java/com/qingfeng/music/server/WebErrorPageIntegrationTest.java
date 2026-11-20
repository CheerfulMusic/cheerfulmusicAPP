package com.qingfeng.music.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 页面路由与错误页回归。
 *
 * <p>覆盖两类线上事故：
 *  1. 前端新增页面路由后忘记同步后端白名单，浏览器直接导航拿到 403；
 *  2. 错误落到 Spring Boot 默认错误页，把 Whitelabel Error Page 暴露给用户。
 *
 * <p>结构化防复发：everySpaRouteIsDirectlyNavigable 会读取前端路由表，
 * 逐条断言页面路由可直接导航，前端加路由而忘同步后端时测试会先失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "qingfeng.invite-gate.enabled=false")
@ActiveProfiles("local")
class WebErrorPageIntegrationTest {
    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String accept) throws Exception {
        return request(path, accept, "GET");
    }

    private HttpResponse<String> request(String path, String accept, String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", accept).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNoWhitelabel(HttpResponse<String> response) {
        assertFalse(response.body().contains("Whitelabel Error Page"),
                "响应不得出现 Spring Boot 白标错误页：" + response.body());
    }

    @Test void agentPageLoadsOnDirectNavigation() throws Exception {
        var response = get("/agent", "text/html");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("id=\"app\""), "应返回 Vue 入口页");
        assertNoWhitelabel(response);
    }

    @Test void unknownPageFallsBackToSpaInsteadOfWhitelabel() throws Exception {
        var response = get("/no-such-page-here", "text/html");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("id=\"app\""));
        assertEquals("noindex", response.headers().firstValue("X-Robots-Tag").orElse(""));
        assertNoWhitelabel(response);
    }

    @Test void searchEntryUrlsAreReadableWithoutAuthentication() throws Exception {
        for (String path : List.of("/", "/zh-CN/", "/en/", "/help")) {
            var response = get(path, "text/html");
            assertEquals(200, response.statusCode(), path);
            assertTrue(response.body().contains("id=\"app\""), path);
            assertTrue(response.headers().firstValue("X-Robots-Tag").isEmpty(), path);
            var head = request(path, "text/html", "HEAD");
            assertEquals(200, head.statusCode(), path);
            assertTrue(head.body().isEmpty(), path);
        }
        for (var document : Map.of("/robots.txt", "Sitemap:", "/sitemap.xml", "<urlset").entrySet()) {
            var response = get(document.getKey(), "*/*");
            assertEquals(200, response.statusCode(), document.getKey());
            assertTrue(response.body().contains(document.getValue()), document.getKey());
            assertEquals(200, request(document.getKey(), "*/*", "HEAD").statusCode(), document.getKey());
        }
        assertEquals(403, get("/home.html", "text/html").statusCode());
    }

    @Test void discoverTemporarilyRedirectsHome() throws Exception {
        for (String path : List.of("/discover", "/discover/", "/discover/index.html")) {
            var response = get(path, "text/html");
            assertEquals(302, response.statusCode(), path);
            assertEquals("/", response.headers().firstValue("Location").orElse(""));
        }
    }

    @Test void duplicateSearchEntryUrlsRedirectPermanentlyWithoutChangingTheQuery() throws Exception {
        var routes = Map.of("/zh-CN", "/zh-CN/", "/zh-CN/index.html", "/zh-CN/",
                "/en", "/en/", "/en/index.html", "/en/",
                "/help/", "/help", "/help/index.html", "/help");
        String query = "?q=%E9%9F%B3%E4%B9%90&ref=a%2Fb";
        for (var route : routes.entrySet()) {
            for (String method : List.of("GET", "HEAD")) {
                var response = request(route.getKey() + query, "text/html", method);
                assertEquals(308, response.statusCode(), route.getKey());
                assertEquals(route.getValue() + query, response.headers().firstValue("Location").orElse(""));
            }
        }
    }

    @Test void privateAppEntryUrlsKeepTheirSpaAndExcludeItFromSearch() throws Exception {
        for (String path : List.of("/account", "/works", "/works/example", "/profile",
                "/recharge", "/analytics", "/create", "/create/photo", "/agent", "/index.html")) {
            var response = get(path, "text/html");
            assertEquals(200, response.statusCode(), path);
            assertEquals("noindex", response.headers().firstValue("X-Robots-Tag").orElse(""), path);
            assertTrue(response.body().contains("id=\"app\""), path);
            var head = request(path, "text/html", "HEAD");
            assertEquals(200, head.statusCode(), path);
            assertEquals("noindex", head.headers().firstValue("X-Robots-Tag").orElse(""), path);
        }
        var publicProfile = get("/artist/example", "text/html");
        assertEquals(200, publicProfile.statusCode());
        assertTrue(publicProfile.headers().firstValue("X-Robots-Tag").isEmpty());
    }

    @Test void missingStaticResourceReturnsFriendlyPageInsteadOfWhitelabel() throws Exception {
        var response = get("/legal/no-such-file.html", "text/html");
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("页面不存在"), "应返回中文提示页：" + response.body());
        assertNoWhitelabel(response);
    }

    @Test void apiErrorsKeepJsonContract() throws Exception {
        var response = get("/api/app/works", "application/json");
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("\"status\":401"), "接口错误应保持 JSON 契约：" + response.body());
        assertNoWhitelabel(response);
    }

    @Test void protectedActuatorStaysForbidden() throws Exception {
        var response = get("/actuator/env", "text/html");
        assertEquals(403, response.statusCode());
        assertNoWhitelabel(response);
    }

    /** 前端每个无参页面路由都必须能直接导航，否则用户刷新深层链接会撞上 403。 */
    @Test void everySpaRouteIsDirectlyNavigable() throws Exception {
        List<String> routes = spaRoutes();
        org.junit.jupiter.api.Assumptions.assumeFalse(routes.isEmpty(), "未找到前端路由表，跳过");
        assertFalse(routes.isEmpty());
        List<String> broken = new ArrayList<>();
        for (String route : routes) {
            var response = get(route, "text/html");
            if ("/discover".equals(route)) {
                assertEquals(302, response.statusCode());
                assertEquals("/", response.headers().firstValue("Location").orElseThrow());
                continue;
            }
            if(java.util.Set.of("/legal/terms","/legal/privacy").contains(route)) {
                assertEquals(302,response.statusCode());
                var target=URI.create("http://127.0.0.1:"+port).resolve(response.headers().firstValue("Location").orElseThrow());
                assertEquals(URI.create("http://127.0.0.1:"+port+route+".html"),target);
                var published=get(route+".html","text/html");
                assertEquals(200,published.statusCode());
                assertNoWhitelabel(published);
                continue;
            }
            boolean ok = response.statusCode() == 200
                    && response.body().contains("id=\"app\"")
                    && !response.body().contains("Whitelabel Error Page");
            if (!ok) broken.add(route + " -> HTTP " + response.statusCode());
        }
        assertTrue(broken.isEmpty(), "以下前端路由无法直接导航（需同步后端白名单与 SPA 转发）：" + broken);
    }

    /** 从前端路由表提取无参页面路由；DEV 专属与带参路由不在此列。 */
    private static List<String> spaRoutes() throws Exception {
        Path source = routerSource();
        if (source == null) return List.of();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int devFrom = text.indexOf("import.meta.env.DEV");
        int devTo = devFrom < 0 ? -1 : text.indexOf(": [])", devFrom);
        Matcher matcher = Pattern.compile("path:\\s*'([^']+)'").matcher(text);
        List<String> routes = new ArrayList<>();
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.contains("pathMatch")) continue;
            if (devFrom >= 0 && matcher.start() > devFrom && (devTo < 0 || matcher.start() < devTo)) continue;
            if (path.contains(":")) continue;
            routes.add(path);
        }
        return routes;
    }

    private static Path routerSource() {
        for (Path candidate : List.of(
                Paths.get("..", "..", "Qingfeng_AI_Music_VUE", "src", "app", "router", "index.ts"),
                Paths.get("Qingfeng_AI_Music_VUE", "src", "app", "router", "index.ts"))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
