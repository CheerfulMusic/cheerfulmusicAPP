package com.qingfeng.music.server.security;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, state cookies, RSA-signed ID tokens, JWKS verification, PKCE and database ownership. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(GoogleOAuthSecurityIntegrationTest.ProviderConfiguration.class)
class GoogleOAuthSecurityIntegrationTest {
    private static final RSAKey KEY;
    private static final RSAKey OTHER_KEY;
    private static final HttpServer PROVIDER;
    private static final Map<String,Grant> GRANTS=new ConcurrentHashMap<>();
    private static final String REDIRECT="http://127.0.0.1:8081/api/public/auth/google/callback";
    private record Grant(String subject,String nonce,String challenge,String invalidClaim){}
    static{
        try{
            KEY=new RSAKeyGenerator(2048).keyID("google-test-key").generate();
            OTHER_KEY=new RSAKeyGenerator(2048).keyID("another-test-key").generate();
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/jwks",exchange->{byte[] body=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
            PROVIDER.createContext("/token",exchange->{
                try{
                    var form=query(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String code=form.get("code");Grant grant=GRANTS.get(code);
                    if(grant==null||!Objects.equals(grant.challenge(),challenge(form.get("code_verifier")))||!REDIRECT.equals(form.get("redirect_uri")))throw new IllegalArgumentException("invalid grant");
                    var claims=new JWTClaimsSet.Builder().issuer("issuer".equals(grant.invalidClaim())?"https://invalid.example":"https://accounts.google.com")
                        .audience("audience".equals(grant.invalidClaim())?"another-client":"google-test-client").subject(grant.subject())
                        .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds("expiry".equals(grant.invalidClaim())?-600:300)))
                        .claim("nonce","nonce".equals(grant.invalidClaim())?"incorrect-nonce":grant.nonce()).claim("email","user@example.test").claim("email_verified",true).claim("name","Google测试用户").build();
                    var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),claims);jwt.sign(new RSASSASigner("signature".equals(grant.invalidClaim())?OTHER_KEY:KEY));
                    String body="{\"access_token\":\""+code+"\",\"token_type\":\"Bearer\",\"expires_in\":300,\"scope\":\"openid email profile\",\"id_token\":\""+jwt.serialize()+"\"}";
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Exception error){exchange.sendResponseHeaders(400,-1);}finally{exchange.close();}
            });
            PROVIDER.createContext("/userinfo",exchange->{
                String authorization=exchange.getRequestHeaders().getFirst("Authorization");Grant grant=authorization==null?null:GRANTS.get(authorization.replace("Bearer ",""));
                if(grant==null){exchange.sendResponseHeaders(401,-1);}else{byte[] body=("{\"sub\":\""+grant.subject()+"\",\"email\":\"user@example.test\",\"email_verified\":true,\"name\":\"Google测试用户\"}").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}exchange.close();
            });
            PROVIDER.start();
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class ProviderConfiguration{
        @Bean GoogleOAuthFlow googleTestFlow(IdentityApi identity){
            String base="http://127.0.0.1:"+PROVIDER.getAddress().getPort();
            var client=ClientRegistration.withRegistrationId("google").clientId("google-test-client").clientSecret("not-a-real-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT).scope("openid","email","profile").authorizationUri(base+"/authorize").tokenUri(base+"/token")
                .jwkSetUri(base+"/jwks").issuerUri("https://accounts.google.com").userInfoUri(base+"/userinfo").userNameAttributeName("sub").clientName("Google").build();
            return new GoogleOAuthFlow(identity,client);
        }
    }
    @Value("${local.server.port}")int port;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired GoogleOAuthFlow flow;
    record Browser(HttpClient http,CookieManager cookies,String csrf,String creator){}
    private HttpResponse<String> send(HttpClient client,String method,String path,String body,String csrf)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String field(String json,String name){var match=java.util.regex.Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);assertTrue(match.find(),json);return match.group(1);}
    private Browser browser()throws Exception{
        var cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var http=HttpClient.newBuilder().cookieHandler(cookies).build();String csrf=field(send(http,"GET","/api/public/csrf",null,null).body(),"token");
        var created=send(http,"POST","/api/public/session","{}",csrf);assertEquals(200,created.statusCode());return new Browser(http,cookies,csrf,field(created.body(),"creatorId"));
    }
    private String cookie(Browser browser){return browser.cookies().getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("QF_SESSION")).map(HttpCookie::getValue).findFirst().orElseThrow();}
    private Map<String,String> start(Browser browser)throws Exception{
        var response=send(browser.http(),"POST","/api/public/auth/google/start","{}",browser.csrf());assertEquals(200,response.statusCode(),response.body());
        String url=field(response.body(),"authorizationUrl");return query(URI.create(url).getRawQuery());
    }
    private String code(Map<String,String> authorization,String invalidClaim){String code=UUID.randomUUID().toString();GRANTS.put(code,new Grant(UUID.randomUUID().toString(),authorization.get("nonce"),authorization.get("code_challenge"),invalidClaim));return code;}
    private HttpResponse<String> callback(Browser browser,String state,String code)throws Exception{return send(browser.http(),"GET",GoogleOAuthFlow.CALLBACK+"?state="+URLEncoder.encode(state,StandardCharsets.UTF_8)+"&code="+code,null,null);}
    private static Map<String,String> query(String value){Map<String,String> values=new HashMap<>();if(value==null)return values;for(String pair:value.split("&")){String[] parts=pair.split("=",2);values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}return values;}
    private static String challenge(String verifier)throws Exception{return verifier==null?"":Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}

    @Test void authorizationRequiresPostCsrfAndAnonymousSessionAndUsesPkce()throws Exception{
        var browser=browser();assertEquals(403,send(browser.http(),"GET","/api/public/auth/google/start",null,null).statusCode());assertEquals(403,send(browser.http(),"POST","/api/public/auth/google/start","{}",null).statusCode());
        var authorization=start(browser);assertEquals("code",authorization.get("response_type"));assertEquals(REDIRECT,authorization.get("redirect_uri"));assertEquals(Set.of("openid","email","profile"),new HashSet<>(Arrays.asList(authorization.get("scope").split(" "))));
        assertEquals("S256",authorization.get("code_challenge_method"));assertFalse(authorization.get("state").isBlank());assertFalse(authorization.get("nonce").isBlank());assertFalse(authorization.containsKey("code_verifier"));assertFalse(authorization.containsKey("access_type"));
        assertEquals("select_account",authorization.get("prompt"));
        assertEquals(401,send(HttpClient.newHttpClient(),"GET","/api/app/works",null,null).statusCode());
        var html=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works")).header("Accept","text/html").build();assertEquals(401,HttpClient.newHttpClient().send(html,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void signedOidcSuccessKeepsWorksRotatesCookieAndDoesNotAuthorizeWithOAuthSession()throws Exception{
        var browser=browser();String old=cookie(browser);var work=works.create(browser.creator(),new WorkApi.Draft("妈妈","生日祝福","回忆","谢谢","民谣","家"));var authorization=start(browser);String code=code(authorization,"");
        var response=callback(browser,authorization.get("state"),code);assertEquals(302,response.statusCode(),response.body());assertEquals("/account?google=success",response.headers().firstValue("location").orElseThrow());
        assertTrue(identity.resolve(old).isEmpty());assertEquals(browser.creator(),identity.resolve(cookie(browser)).orElseThrow());assertEquals(work.id(),works.get(browser.creator(),work.id()).id());
        String setCookie=response.headers().allValues("set-cookie").stream().filter(value->value.startsWith("QF_SESSION=")).findFirst().orElseThrow();assertTrue(setCookie.contains("HttpOnly"));assertTrue(setCookie.contains("SameSite=Lax"));assertTrue(setCookie.contains("Path=/api"));
        var status=send(browser.http(),"GET","/api/public/auth/google/status",null,null);assertTrue(status.body().contains("\"authenticated\":true"));assertTrue(status.headers().firstValue("cache-control").orElse("").contains("no-store"));
        var stolenSessionOnly=HttpClient.newBuilder().build();String jsession=browser.cookies().getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).map(HttpCookie::getValue).findFirst().orElse("");
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works")).header("Cookie","JSESSIONID="+jsession).build();assertEquals(401,stolenSessionOnly.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        String current=cookie(browser);assertEquals("/account?google=failed",callback(browser,authorization.get("state"),code).headers().firstValue("location").orElseThrow());assertEquals(current,cookie(browser));
    }
    @Test void wrongStateAndChangedSessionDoNotBindOrOverwriteCurrentIdentity()throws Exception{
        var browser=browser();String old=cookie(browser);var authorization=start(browser);String code=code(authorization,"");
        assertEquals("/account?google=failed",callback(browser,"wrong-state",code).headers().firstValue("location").orElseThrow());assertEquals(old,cookie(browser));assertTrue(identity.googleIdentity(browser.creator()).isEmpty());
        var another=browser();var auth=start(another);var replacement=identity.create();another.cookies().getCookieStore().getCookies().stream().filter(c->c.getName().equals("QF_SESSION")).forEach(c->c.setValue(replacement.token()));
        assertEquals("/account?google=failed",callback(another,auth.get("state"),code(auth,"")).headers().firstValue("location").orElseThrow());assertEquals(replacement.token(),cookie(another));assertTrue(identity.googleIdentity(replacement.creatorId()).isEmpty());
    }
    @Test void invalidIdTokenClaimsNeverCreateBindingOrReplaceAnonymousCookie()throws Exception{
        for(String invalid:List.of("issuer","audience","expiry","nonce","signature")){
            var browser=browser();String old=cookie(browser);var authorization=start(browser);var response=callback(browser,authorization.get("state"),code(authorization,invalid));
            assertEquals("/account?google=failed",response.headers().firstValue("location").orElseThrow(),invalid);assertEquals(old,cookie(browser));assertTrue(identity.googleIdentity(browser.creator()).isEmpty());
        }
    }
    @Test void cancelledAuthorizationKeepsCurrentIdentity()throws Exception{
        var browser=browser();String old=cookie(browser);var authorization=start(browser);var response=send(browser.http(),"GET",GoogleOAuthFlow.CALLBACK+"?state="+URLEncoder.encode(authorization.get("state"),StandardCharsets.UTF_8)+"&error=access_denied",null,null);
        assertEquals("/account?google=cancelled",response.headers().firstValue("location").orElseThrow());assertEquals(old,cookie(browser));assertTrue(identity.googleIdentity(browser.creator()).isEmpty());
    }
    @Test @SuppressWarnings("unchecked") void expiredStateIsConsumedAndRejectedBeforeTokenExchange(){
        var creator=identity.create();var request=new MockHttpServletRequest("POST","/api/public/auth/google/start");request.setCookies(new Cookie("QF_SESSION",creator.token()));var response=new MockHttpServletResponse();String url=flow.start(request,response);String state=query(URI.create(url).getRawQuery()).get("state");
        var repository=(AuthorizationRequestRepository<OAuth2AuthorizationRequest>)ReflectionTestUtils.getField(flow,"requests");var callback=new MockHttpServletRequest("GET",GoogleOAuthFlow.CALLBACK);callback.setSession(request.getSession());callback.setCookies(new Cookie("QF_SESSION",creator.token()));callback.setParameter("state",state);
        var saved=repository.loadAuthorizationRequest(callback);repository.saveAuthorizationRequest(OAuth2AuthorizationRequest.from(saved).attributes(a->a.put("qingfeng_started",Instant.now().minusSeconds(601).getEpochSecond())).build(),request,response);
        assertNull(repository.removeAuthorizationRequest(callback,response));assertNull(repository.removeAuthorizationRequest(callback,response));assertEquals(creator.creatorId(),identity.resolve(creator.token()).orElseThrow());
    }
    @Test void logoutRequiresPostAndCsrfClearsCookieAndInvalidatesPendingOAuth()throws Exception{
        var browser=browser();String original=cookie(browser);var pending=start(browser);
        assertEquals(403,send(browser.http(),"GET","/api/public/session/logout",null,null).statusCode());
        assertEquals(403,send(browser.http(),"POST","/api/public/session/logout","{}",null).statusCode());assertEquals(browser.creator(),identity.resolve(original).orElseThrow());
        var loggedOut=send(browser.http(),"POST","/api/public/session/logout","{}",browser.csrf());assertEquals(200,loggedOut.statusCode());assertEquals("{\"loggedOut\":true}",loggedOut.body());assertTrue(identity.resolve(original).isEmpty());
        String deletion=loggedOut.headers().allValues("set-cookie").stream().filter(value->value.startsWith("QF_SESSION=")).findFirst().orElseThrow();assertTrue(deletion.contains("Max-Age=0"));assertTrue(deletion.contains("Path=/api"));assertTrue(deletion.contains("HttpOnly"));assertTrue(deletion.contains("SameSite=Lax"));assertEquals("no-store",loggedOut.headers().firstValue("cache-control").orElseThrow());
        assertEquals(401,send(browser.http(),"GET","/api/app/works",null,null).statusCode());
        assertEquals("/account?google=failed",callback(browser,pending.get("state"),code(pending,"")).headers().firstValue("location").orElseThrow());assertTrue(identity.googleIdentity(browser.creator()).isEmpty());
        assertEquals(200,send(browser.http(),"POST","/api/public/session/logout","{}",browser.csrf()).statusCode());
    }
}
