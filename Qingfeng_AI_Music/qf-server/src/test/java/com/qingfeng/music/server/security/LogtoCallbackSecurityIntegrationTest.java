package com.qingfeng.music.server.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the HTTP callback against a fake ES384 OIDC provider, without a real Logto secret. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"qingfeng.auth.logto.email-enabled=true", "qingfeng.auth.logto.phone-enabled=true"})
@ActiveProfiles("test")
@Import(LogtoCallbackSecurityIntegrationTest.ProviderConfiguration.class)
class LogtoCallbackSecurityIntegrationTest {
    private static final ECKey KEY;
    private static final ECKey OTHER_KEY;
    private static final HttpServer PROVIDER;
    private static final Map<String, Grant> GRANTS = new ConcurrentHashMap<>();
    private static final Map<String, Grant> REDEEMED = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKEN_REQUESTS = new AtomicInteger();
    private static final String REDIRECT = "http://127.0.0.1:8081/api/public/auth/logto/callback";
    private record Grant(String subject, String nonce, String challenge, String invalidClaim, boolean verified) {}

    static {
        try {
            KEY = new ECKeyGenerator(Curve.P_384).keyID("logto-test-key").generate();
            OTHER_KEY = new ECKeyGenerator(Curve.P_384).keyID("other-test-key").generate();
            PROVIDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PROVIDER.createContext("/oidc/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            PROVIDER.createContext("/oidc/token", exchange -> {
                TOKEN_REQUESTS.incrementAndGet();
                try {
                    var form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    Grant grant = GRANTS.remove(form.get("code"));
                    if (grant == null || !Objects.equals(grant.challenge(), challenge(form.get("code_verifier")))
                        || !REDIRECT.equals(form.get("redirect_uri"))) throw new IllegalArgumentException("invalid grant");
                    String issuer = issuer();
                    var claims = new JWTClaimsSet.Builder()
                        .issuer("issuer".equals(grant.invalidClaim()) ? "https://invalid.example" : issuer)
                        .audience("audience".equals(grant.invalidClaim()) ? "other-client" : "logto-test-client")
                        .subject(grant.subject())
                        .issueTime(new Date())
                        .expirationTime(Date.from(Instant.now().plusSeconds("expiry".equals(grant.invalidClaim()) ? -600 : 300)))
                        .claim("nonce", "nonce".equals(grant.invalidClaim()) ? "incorrect-nonce" : grant.nonce())
                        .claim("email", "singer@example.test")
                        .claim("email_verified", grant.verified())
                        .claim("phone_number", "+8613800001234")
                        .claim("phone_number_verified", grant.verified())
                        .claim("name", "测试歌手")
                        .build();
                    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES384).keyID(KEY.getKeyID()).build(), claims);
                    jwt.sign(new ECDSASigner("signature".equals(grant.invalidClaim()) ? OTHER_KEY : KEY));
                    REDEEMED.put(form.get("code"), grant);
                    String body = "{\"access_token\":\"" + form.get("code") + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                        + "\"scope\":\"openid profile email phone\",\"id_token\":\"" + jwt.serialize() + "\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception error) {
                    exchange.sendResponseHeaders(400, -1);
                } finally {
                    exchange.close();
                }
            });
            PROVIDER.createContext("/oidc/me", exchange -> {
                String bearer = exchange.getRequestHeaders().getFirst("Authorization");
                Grant grant = bearer == null ? null : REDEEMED.get(bearer.replace("Bearer ", ""));
                if (grant == null) { exchange.sendResponseHeaders(401, -1); exchange.close(); return; }
                String json = "{\"sub\":\"" + grant.subject() + "\",\"name\":\"测试歌手\","
                    + "\"email\":\"singer@example.test\",\"email_verified\":" + grant.verified() + ","
                    + "\"phone_number\":\"+8613800001234\",\"phone_number_verified\":" + grant.verified() + "}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            PROVIDER.start();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String issuer() { return "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/oidc"; }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean LogtoOAuthFlow logtoTestFlow(IdentityApi identity, LogtoIdentityService identities) {
            String base = issuer();
            ClientRegistration registration = ClientRegistration.withRegistrationId("logto")
                .clientId("logto-test-client").clientSecret("not-a-real-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT).scope("openid", "profile", "email", "phone")
                .authorizationUri(base + "/auth").tokenUri(base + "/token")
                .jwkSetUri(base + "/.well-known/jwks.json").issuerUri(base)
                .userInfoUri(base + "/me").userNameAttributeName("sub").clientName("Logto").build();
            return new LogtoOAuthFlow(identity, identities, base, registration, "apple");
        }
        @Bean JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
            var factory = new OidcIdTokenDecoderFactory();
            factory.setJwsAlgorithmResolver(client -> SignatureAlgorithm.ES384);
            return factory;
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;

    private record Browser(HttpClient http, CookieManager cookies, String csrf, String creator) {}
    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private static String field(String json, String name) {
        var match = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(match.find(), json);
        return match.group(1);
    }
    private Browser browser() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf = field(send(http, "GET", "/api/public/csrf", null, null).body(), "token");
        var created = send(http, "POST", "/api/public/session", "{}", csrf);
        assertEquals(200, created.statusCode());
        return new Browser(http, cookies, csrf, field(created.body(), "creatorId"));
    }
    private String cookie(Browser browser) {
        return browser.cookies().getCookieStore().getCookies().stream().filter(c -> c.getName().equals("QF_SESSION"))
            .map(HttpCookie::getValue).findFirst().orElseThrow();
    }
    private Map<String, String> start(Browser browser, String body) throws Exception {
        var response = send(browser.http(), "POST", "/api/public/auth/logto/start", body, browser.csrf());
        assertEquals(200, response.statusCode(), response.body());
        return query(URI.create(field(response.body(), "authorizationUrl")).getRawQuery());
    }
    private String code(Map<String, String> auth, String invalidClaim, boolean verified) {
        String code = UUID.randomUUID().toString();
        GRANTS.put(code, new Grant(UUID.randomUUID().toString(), auth.get("nonce"), auth.get("code_challenge"), invalidClaim, verified));
        return code;
    }
    private HttpResponse<String> callback(Browser browser, String state, String code) throws Exception {
        return send(browser.http(), "GET", LogtoOAuthFlow.CALLBACK + "?state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) + "&code=" + code, null, null);
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }
    private static String challenge(String verifier) throws Exception {
        return verifier == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test void startUsesCsrfStateNoncePkceAndOnlyValidatedProviderHints() throws Exception {
        var browser = browser();
        assertEquals(403, send(browser.http(), "GET", "/api/public/auth/logto/start", null, null).statusCode());
        assertEquals(403, send(browser.http(), "POST", "/api/public/auth/logto/start", "{}", null).statusCode());
        var email = start(browser, "{\"method\":\"email\",\"loginHint\":\"singer+tag@example.test\"}");
        assertEquals("singer+tag@example.test", email.get("login_hint"));
        assertEquals("identifier:sign_in", email.get("first_screen"));
        assertEquals("email", email.get("identifier"));
        assertEquals("S256", email.get("code_challenge_method"));
        assertFalse(email.get("state").isBlank());
        assertFalse(email.get("nonce").isBlank());
        assertFalse(email.containsKey("code_verifier"));
        assertFalse(email.containsKey("client_secret"));
        assertEquals(REDIRECT, email.get("redirect_uri"));
        var phone = start(browser(), "{\"method\":\"phone\",\"loginHint\":\"+8613800001234\"}");
        assertEquals("+8613800001234", phone.get("login_hint"));
        assertEquals("phone", phone.get("identifier"));
        var apple = start(browser(), "{\"method\":\"apple\"}");
        assertEquals("social:apple", apple.get("direct_sign_in"));
        assertFalse(apple.containsKey("login_hint"));
        var invalidBrowser = browser();
        var invalid = send(invalidBrowser.http(), "POST", "/api/public/auth/logto/start",
            "{\"method\":\"email\",\"loginHint\":\"bad\"}", invalidBrowser.csrf());
        assertEquals(400, invalid.statusCode());
    }

    @Test void es384CallbackRotatesSessionPreservesWorksAndConsumesState() throws Exception {
        var browser = browser();
        String original = cookie(browser);
        var work = works.create(browser.creator(), new WorkApi.Draft("妈妈", "生日祝福", "回忆", "谢谢", "民谣", "家"));
        var auth = start(browser, "{\"method\":\"email\",\"loginHint\":\"singer@example.test\"}");
        String code = code(auth, "", true);
        var response = callback(browser, auth.get("state"), code);
        assertEquals(302, response.statusCode(), response.body());
        assertEquals("/account?logto=success", response.headers().firstValue("location").orElseThrow());
        assertTrue(identity.resolve(original).isEmpty());
        assertEquals(browser.creator(), identity.resolve(cookie(browser)).orElseThrow());
        assertEquals(work.id(), works.get(browser.creator(), work.id()).id());
        String setCookie = response.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("QF_SESSION=")).findFirst().orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Path=/api"));
        var status = send(browser.http(), "GET", "/api/public/auth/logto/status", null, null);
        assertTrue(status.body().contains("\"authenticated\":true"));
        assertTrue(status.body().contains("\"email\":\"singer@example.test\""));
        assertTrue(status.body().contains("\"phoneMasked\":\"****1234\""));
        assertTrue(status.headers().firstValue("cache-control").orElse("").contains("no-store"));
        String newToken = cookie(browser);
        assertEquals("/account?logto=failed", callback(browser, auth.get("state"), code).headers().firstValue("location").orElseThrow());
        assertEquals(newToken, cookie(browser));
    }

    @Test void wrongStateOrChangedCreatorSessionCannotBindIdentity() throws Exception {
        var wrong = browser();
        String token = cookie(wrong);
        var auth = start(wrong, "{}");
        int before = TOKEN_REQUESTS.get();
        assertEquals("/account?logto=failed", callback(wrong, "wrong-state", code(auth, "", true)).headers().firstValue("location").orElseThrow());
        assertEquals(before, TOKEN_REQUESTS.get(), "state must fail before token exchange");
        assertEquals(token, cookie(wrong));
        var changed = browser();
        var otherAuth = start(changed, "{}");
        var replacement = identity.create();
        changed.cookies().getCookieStore().getCookies().stream().filter(c -> c.getName().equals("QF_SESSION"))
            .forEach(c -> c.setValue(replacement.token()));
        before = TOKEN_REQUESTS.get();
        assertEquals("/account?logto=failed", callback(changed, otherAuth.get("state"), code(otherAuth, "", true))
            .headers().firstValue("location").orElseThrow());
        assertEquals(before, TOKEN_REQUESTS.get(), "creator change must fail before token exchange");
        assertEquals(replacement.creatorId(), identity.resolve(cookie(changed)).orElseThrow());
    }

    @Test void invalidIdTokensNeverReplaceTheBrowserSession() throws Exception {
        for (String invalid : new String[]{"issuer", "audience", "expiry", "nonce", "signature"}) {
            var browser = browser();
            String original = cookie(browser);
            var auth = start(browser, "{}");
            var response = callback(browser, auth.get("state"), code(auth, invalid, true));
            assertEquals("/account?logto=failed", response.headers().firstValue("location").orElseThrow(), invalid);
            assertEquals(original, cookie(browser), invalid);
            assertEquals(browser.creator(), identity.resolve(original).orElseThrow(), invalid);
        }
    }

    @Test void unverifiedContactClaimsAreNotSavedAndCancellationKeepsSession() throws Exception {
        var browser = browser();
        var auth = start(browser, "{}");
        var cancelled = send(browser.http(), "GET", LogtoOAuthFlow.CALLBACK + "?state="
            + URLEncoder.encode(auth.get("state"), StandardCharsets.UTF_8) + "&error=access_denied", null, null);
        assertEquals("/account?logto=cancelled", cancelled.headers().firstValue("location").orElseThrow());
        var auth2 = start(browser, "{}");
        assertEquals("/account?logto=success", callback(browser, auth2.get("state"), code(auth2, "", false))
            .headers().firstValue("location").orElseThrow());
        var status = send(browser.http(), "GET", "/api/public/auth/logto/status", null, null);
        assertTrue(status.body().contains("\"authenticated\":true"));
        assertFalse(status.body().contains("\"email\":"));
        assertFalse(status.body().contains("\"phoneMasked\":"));
    }
}
