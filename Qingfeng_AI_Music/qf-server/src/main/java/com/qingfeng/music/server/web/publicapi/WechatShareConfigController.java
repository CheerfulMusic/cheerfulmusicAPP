package com.qingfeng.music.server.web.publicapi;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile({"mysql","test"})
public class WechatShareConfigController {
    private static final java.util.regex.Pattern SHARE_PATH = java.util.regex.Pattern.compile(
            "/song/[A-Za-z0-9_-]{43}|/s/[A-Za-z0-9_-]{22}|(?:/en)?/discover/songs/"
            + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[1-9][0-9]{0,18}");
    private final WechatOfficialClient client;
    private final String origin;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WechatShareConfigController.class);

    public WechatShareConfigController(WechatOfficialClient client,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin) {
        this.client = client;
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
        String payload = "jsapi_ticket=" + client.jsapiTicket() + "&noncestr=" + nonce + "&timestamp=" + now + "&url=" + url;
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload.getBytes(StandardCharsets.UTF_8)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("appId",client.appId(),"timestamp",now,"nonceStr",nonce,"signature",signature));
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

}
