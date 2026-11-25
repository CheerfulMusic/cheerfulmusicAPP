package com.qingfeng.music.server.web.publicapi;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"mysql", "test"})
class WechatOfficialClient {
    private static final Logger LOG = LoggerFactory.getLogger(WechatOfficialClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private final String appId;
    private final String secret;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();
    private Cached token;
    private Cached ticket;
    private long tokenRetryAfter;
    private long ticketRetryAfter;

    private record Cached(String value, long until) {}
    record News(String title, String description, String url, String picurl) {}
    enum Delivery { SENT, NEEDS_MESSAGE, REJECTED, UNAVAILABLE, UNKNOWN }

    @Autowired
    WechatOfficialClient(@Value("${qingfeng.wechat.official.app-id:}") String appId,
                         @Value("${qingfeng.wechat.official.app-secret:}") String secret) {
        this(appId, secret, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
    }

    WechatOfficialClient(String appId, String secret, HttpClient client) {
        this.appId = appId;
        this.secret = secret;
        this.client = client;
    }

    String appId() { return appId; }
    boolean configured() { return !appId.isBlank() && !secret.isBlank(); }

    synchronized String jsapiTicket() {
        long now = Instant.now().getEpochSecond();
        if (ticket != null && ticket.until() > now) return ticket.value();
        if (now < ticketRetryAfter) throw unavailable();
        String accessToken = accessToken();
        try {
            ticket = cached(request("ticket/getticket?type=jsapi&access_token=" + encoded(accessToken)), "ticket", now);
            return ticket.value();
        } catch (Exception failure) {
            interrupted(failure);
            ticketRetryAfter = now + 30;
            invalidate(accessToken);
            LOG.warn("WeChat ticket unavailable ({})", failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    Delivery sendNews(String openId, News article) {
        String accessToken;
        try { accessToken = accessToken(); }
        catch (ResponseStatusException unavailable) { return Delivery.UNAVAILABLE; }
        var message = Map.of("touser", openId, "msgtype", "news", "news", Map.of("articles", List.of(article)));
        try {
            var response = exchange(post("message/custom/send?access_token=" + encoded(accessToken), message));
            if (response.statusCode() != 200) {
                LOG.warn("WeChat card delivery returned HTTP {}", response.statusCode());
                return Delivery.UNKNOWN;
            }
            JsonNode errcode = json.readTree(response.body()).path("errcode");
            if (!errcode.isIntegralNumber()) return Delivery.UNKNOWN;
            int code = errcode.asInt();
            if (code == 0) return Delivery.SENT;
            if (code == 40001 || code == 40014 || code == 42001) invalidate(accessToken);
            LOG.warn("WeChat card delivery rejected ({})", code);
            return code == 43004 || code == 45015 || code == 45047 ? Delivery.NEEDS_MESSAGE : Delivery.REJECTED;
        } catch (Exception failure) {
            interrupted(failure);
            LOG.warn("WeChat card delivery unconfirmed ({})", failure.getClass().getSimpleName());
            return Delivery.UNKNOWN;
        }
    }

    private synchronized String accessToken() {
        long now = Instant.now().getEpochSecond();
        if (token != null && token.until() > now) return token.value();
        if (!configured() || now < tokenRetryAfter) throw unavailable();
        try {
            token = cached(post("stable_token", Map.of("grant_type", "client_credential", "appid", appId, "secret", secret)),
                    "access_token", now);
            return token.value();
        } catch (Exception failure) {
            interrupted(failure);
            tokenRetryAfter = now + 30;
            LOG.warn("WeChat access token unavailable ({})", failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private synchronized void invalidate(String value) {
        if (token != null && token.value().equals(value)) token = null;
    }

    private Cached cached(HttpRequest.Builder request, String field, long now) throws Exception {
        var response = exchange(request);
        JsonNode result = json.readTree(response.body());
        String value = result.path(field).asString("");
        long ttl = result.path("expires_in").asLong(0);
        if (response.statusCode() != 200 || value.isBlank() || ttl < 2) {
            LOG.warn("WeChat {} request failed: HTTP {}, errcode {}", field, response.statusCode(), result.path("errcode").asInt(-1));
            throw unavailable();
        }
        return new Cached(value, now + ttl - Math.min(300, ttl / 2));
    }

    private HttpRequest.Builder post(String path, Object data) {
        return request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/cgi-bin/" + path));
    }

    private HttpResponse<String> exchange(HttpRequest.Builder request) throws Exception {
        return client.send(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static void interrupted(Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
}
