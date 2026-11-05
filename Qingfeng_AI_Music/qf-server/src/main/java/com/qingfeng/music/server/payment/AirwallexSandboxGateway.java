package com.qingfeng.music.server.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Airwallex sandbox only. No configurable production endpoint is exposed. */
@Component
@Profile({"mysql", "test"})
public final class AirwallexSandboxGateway implements PaymentGateway {
    private final RestClient http = RestClient.builder().baseUrl("https://api.sandbox.airwallex.com").build();
    private final ObjectMapper json = new ObjectMapper();
    @Value("${qingfeng.payments.client-id:}") private String clientId;
    @Value("${qingfeng.payments.api-key:}") private String apiKey;
    @Value("${qingfeng.payments.account-id:}") private String accountId;
    @Value("${qingfeng.payments.webhook-secret:}") private String webhookSecret;
    public boolean configured() { return !clientId.isBlank() && !apiKey.isBlank() && !accountId.isBlank() && !webhookSecret.isBlank(); }
    public Link create(String reference, String title, BigDecimal amount, String currency) {
        JsonNode link=call("POST", "/api/v1/pa/payment_links/create", Map.of("reusable",false,"title",title,"amount",amount,"currency",currency,"reference",reference));
        String id=link.path("id").asString(), url=link.path("url").asString();
        return validateLink(id,url);
    }
    static Link validateLink(String id, String url) {
        URI uri;
        try { uri=URI.create(url); } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"支付链接无效"); }
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}") || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || !(uri.getHost().equals("airwallex.com") || uri.getHost().endsWith(".airwallex.com"))) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"支付链接无效");
        return new Link(id,url);
    }
    public Snapshot retrieve(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return snapshot(call("GET", "/api/v1/pa/payment_links/"+id, null));
    }
    static Snapshot snapshot(JsonNode remote) {
        return new Snapshot(remote.path("id").asString(), remote.path("reference").asString(), remote.path("status").asString(),
                remote.path("reusable").asBoolean(true), remote.path("currency").asString(),
                remote.path("amount").isNumber()?remote.path("amount").decimalValue():null,
                remote.path("latest_successful_payment_intent_id").asString());
    }
    public Notification notification(String timestamp, String signature, byte[] body) {
        if (!configured() || !verify(timestamp,signature,body)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        JsonNode event=json.readTree(body);
        if (!accountId.equals(event.path("account_id").asString())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!"payment_link.paid".equals(event.path("name").asString())) return null;
        JsonNode object=event.path("data").path("object");
        String id=object.path("reference").asString(), linkId=object.path("id").asString();
        if(id.isBlank() || linkId.isBlank() || !linkId.matches("[A-Za-z0-9_-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new Notification(id,linkId);
    }
    private boolean verify(String timestamp,String signature,byte[] body) {
        try {
            long time=Long.parseLong(timestamp);
            if(Math.abs(System.currentTimeMillis()-time)>Duration.ofMinutes(5).toMillis()) return false;
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature));
        } catch(Exception e) {return false;}
    }
    private JsonNode call(String method,String path,Object payload) {
        JsonNode token=http.post().uri("/api/v1/authentication/login").header("x-client-id",clientId).header("x-api-key",apiKey).retrieve().body(JsonNode.class);
        if(token==null || token.path("token").asString().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        String bearer=token.path("token").asString();
        return "GET".equals(method) ? http.get().uri(path).headers(h->h.setBearerAuth(bearer)).retrieve().body(JsonNode.class)
            : http.post().uri(path).headers(h->h.setBearerAuth(bearer)).body(payload).retrieve().body(JsonNode.class);
    }
}
