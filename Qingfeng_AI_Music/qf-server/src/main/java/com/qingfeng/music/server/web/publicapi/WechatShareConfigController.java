package com.qingfeng.music.server.web.publicapi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
@Profile({"mysql","test"})
public class WechatShareConfigController {
    private static final java.util.regex.Pattern SHARE_PATH = java.util.regex.Pattern.compile(
            "/song/[A-Za-z0-9_-]{43}|(?:/en)?/discover/songs/"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[1-9][0-9]{0,18}");
    private final String appId, secret, origin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final ObjectMapper json = new ObjectMapper();
    private record Cached(String value, long until) {}
    private Cached token, ticket;
    private long retryAfter;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WechatShareConfigController.class);

    public WechatShareConfigController(@Value("${qingfeng.wechat.official.app-id:}") String appId,
            @Value("${qingfeng.wechat.official.app-secret:}") String secret,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin) {
        this.appId = appId;
        this.secret = secret;
        this.origin = origin.replaceAll("/+$", "");
    }

    @GetMapping("/api/wechat/jssdk-config")
    public ResponseEntity<Map<String,Object>> config(@RequestParam String url) throws Exception {
        URI page;
        try { page = URI.create(url); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        if (url.length() > 4096 || !url.startsWith(origin + "/") || page.getFragment() != null
                || !SHARE_PATH.matcher(page.getRawPath()).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String nonce = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();
        String payload = "jsapi_ticket=" + currentTicket() + "&noncestr=" + nonce + "&timestamp=" + now + "&url=" + url;
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload.getBytes(StandardCharsets.UTF_8)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("appId",appId,"timestamp",now,"nonceStr",nonce,"signature",signature));
    }

    public enum ClientFailure { INVALID_SIGNATURE, PERMISSION_DENIED, SDK_LOAD, CONFIG_FAILED }
    private final EnumMap<ClientFailure,Long> reported = new EnumMap<>(ClientFailure.class);

    @PostMapping("/api/wechat/jssdk-error")
    public synchronized ResponseEntity<Void> clientError(@RequestParam ClientFailure reason) {
        long minute = Instant.now().getEpochSecond()/60;
        if (!Long.valueOf(minute).equals(reported.put(reason,minute)))
            LOG.warn("WeChat JS-SDK client reported {}", reason);
        return ResponseEntity.noContent().build();
    }

    private synchronized String currentTicket() {
        long now = Instant.now().getEpochSecond();
        if (ticket != null && ticket.until() > now) return ticket.value();
        if (appId.isBlank() || secret.isBlank() || now < retryAfter) throw unavailable();
        try {
            if (token == null || token.until() <= now) {
                String body = json.writeValueAsString(Map.of("grant_type","client_credential","appid",appId,"secret",secret));
                token = fetch(HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/cgi-bin/stable_token"))
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)), "access_token", now);
            }
            ticket = fetch(HttpRequest.newBuilder(URI.create("https://api.weixin.qq.com/cgi-bin/ticket/getticket?type=jsapi&access_token="
                    + java.net.URLEncoder.encode(token.value(), StandardCharsets.UTF_8))), "ticket", now);
            return ticket.value();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            retryAfter = now + 30;
            token = null;
            LOG.warn("WeChat signing temporarily unavailable ({})", failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private Cached fetch(HttpRequest.Builder request, String field, long now) throws Exception {
        var response = client.send(request.timeout(Duration.ofSeconds(6)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode result = json.readTree(response.body());
        String value = result.path(field).asString("");
        long ttl = result.path("expires_in").asLong(0);
        if (response.statusCode() != 200 || value.isBlank() || ttl < 2) {
            LOG.warn("WeChat {} request failed: HTTP {}, errcode {}", field, response.statusCode(), result.path("errcode").asInt(-1));
            throw unavailable();
        }
        return new Cached(value, now + ttl - Math.min(300, ttl / 2));
    }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE); }
}