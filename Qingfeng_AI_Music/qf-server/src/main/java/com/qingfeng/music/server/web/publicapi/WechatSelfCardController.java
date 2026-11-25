package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("(mysql | test) & !sg-reader")
@ConditionalOnProperty(name = "qingfeng.wechat.official.self-card.enabled", havingValue = "true")
public class WechatSelfCardController {
    public static final String CALLBACK = "/api/wechat/card/callback";
    public static final String AVAILABILITY = "/api/wechat/card";
    private static final String APP = "/api/app/wechat-card";
    private static final int MAX_PUSH_BYTES = 32 * 1024;
    private final WechatSelfCardService cards;
    private final ArtistProfileService profiles;
    private final WechatCardPush push;
    private final String accountName;

    WechatSelfCardController(WechatSelfCardService cards, ArtistProfileService profiles, WechatOfficialClient client,
            @Value("${qingfeng.wechat.official.self-card.account-name:}") String accountName,
            @Value("${qingfeng.wechat.official.self-card.callback-token:}") String callbackToken,
            @Value("${qingfeng.wechat.official.self-card.encoding-aes-key:}") String encodingAesKey) {
        this.cards = cards;
        this.profiles = profiles;
        this.accountName = accountName.strip();
        if (this.accountName.isBlank()) throw new IllegalArgumentException("WeChat card delivery requires the official-account name");
        this.push = new WechatCardPush(callbackToken, encodingAesKey, client.appId());
    }

    public record Availability(String accountName) {}
    public record Binding(boolean bound) {}
    public record SendRequest(@NotNull UUID requestId, @NotBlank @Size(max = 120) String path) {}
    public record SendResult(WechatSelfCardService.State state) {}

    @GetMapping(AVAILABILITY)
    public ResponseEntity<Availability> availability() {
        return noStore(new Availability(accountName));
    }

    @GetMapping(APP + "/binding")
    public ResponseEntity<Binding> binding(@AuthenticationPrincipal CreatorPrincipal creator) {
        return noStore(new Binding(cards.bound(account(creator))));
    }

    @PostMapping(APP + "/binding")
    public ResponseEntity<WechatSelfCardService.Challenge> bind(@AuthenticationPrincipal CreatorPrincipal creator) {
        return noStore(cards.challenge(account(creator)));
    }

    @DeleteMapping(APP + "/binding")
    public ResponseEntity<Binding> unlink(@AuthenticationPrincipal CreatorPrincipal creator) {
        cards.unlink(account(creator));
        return noStore(new Binding(false));
    }

    @PostMapping(APP + "/send")
    public ResponseEntity<SendResult> send(@AuthenticationPrincipal CreatorPrincipal creator, @Valid @RequestBody SendRequest input) {
        return noStore(new SendResult(cards.send(account(creator), input.requestId(), input.path())));
    }

    @GetMapping(value = CALLBACK, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam String signature, @RequestParam String timestamp,
                                        @RequestParam String nonce, @RequestParam String echostr) {
        return noStore(push.challenge(signature, timestamp, nonce, echostr));
    }

    @PostMapping(value = CALLBACK, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> receive(@RequestParam("msg_signature") String signature, @RequestParam String timestamp,
            @RequestParam String nonce, @RequestParam("encrypt_type") String encryptType, HttpServletRequest request) throws IOException {
        if (!"aes".equals(encryptType)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        byte[] body = request.getInputStream().readNBytes(MAX_PUSH_BYTES + 1);
        if (body.length > MAX_PUSH_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        var text = push.accept(signature, timestamp, nonce, body);
        if (text != null) cards.accept(text);
        return noStore("success");
    }

    private String account(CreatorPrincipal creator) {
        if (creator == null || !profiles.hasAccount(creator.id()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录音乐账号。");
        return creator.id();
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
