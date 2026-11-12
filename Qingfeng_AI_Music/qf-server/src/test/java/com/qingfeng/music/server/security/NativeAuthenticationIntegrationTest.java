package com.qingfeng.music.server.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.*;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.test.context.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP Experience/OIDC/ES384 protocol fixture; no real messages, passwords or provider credentials. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:native_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.auth.logto.email-enabled=true","qingfeng.auth.logto.phone-enabled=true","qingfeng.auth.logto.phone-international-enabled=true"})
@ActiveProfiles("test") @Import(NativeAuthenticationIntegrationTest.Fixture.class)
class NativeAuthenticationIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,Flow> FLOWS=new ConcurrentHashMap<>();
    private static final Set<String> USERS=ConcurrentHashMap.newKeySet();
    private static final AtomicInteger SENT=new AtomicInteger();
    private static final ECKey KEY,WRONG;
    private static final HttpServer PROVIDER;
    private static final String REDIRECT="http://127.0.0.1:8081/api/public/auth/logto/callback";
    private static final class Flow {
        String id,state,nonce,challenge,event,channel,value,verification;
        boolean verified,identified,redeemed;
    }
    static {
        try{
            KEY=new ECKeyGenerator(Curve.P_384).keyID("native").generate();WRONG=new ECKeyGenerator(Curve.P_384).keyID("wrong").generate();
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/",exchange->{try{handle(exchange);}catch(Exception error){reply(exchange,500,Map.of("code","fixture_failure"));}});
            PROVIDER.start();
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    static String issuer(){return "http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/oidc";}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("qingfeng.auth.logto.issuer",NativeAuthenticationIntegrationTest::issuer);}
    @TestConfiguration(proxyBeanMethods=false) static class Fixture {
        @Bean LogtoNativeClient nativeClient(){
            var registration=ClientRegistration.withRegistrationId("logto").clientId("native-client").clientSecret("fixture-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC).authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT).scope("openid","profile","email","phone").issuerUri(issuer()).authorizationUri(issuer()+"/auth")
                .tokenUri(issuer()+"/token").jwkSetUri(issuer()+"/jwks").userInfoUri(issuer()+"/me").userNameAttributeName("sub").build();
            var factory=new OidcIdTokenDecoderFactory();factory.setJwsAlgorithmResolver(client->SignatureAlgorithm.ES384);
            return new LogtoNativeClient(registration,factory.createDecoder(registration));
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired IdentityApi identity;
    @Autowired CreditsLedger credits;
    private record Browser(HttpClient client,CookieManager cookies,String csrf,String creator){}
    private Browser browser()throws Exception{
        var jar=new CookieManager(null,CookiePolicy.ACCEPT_ALL);var client=HttpClient.newBuilder().cookieHandler(jar).followRedirects(HttpClient.Redirect.NEVER).build();
        String csrf=JSON.readTree(send(client,"GET","/api/public/csrf",null,null).body()).path("token").asText();
        var response=send(client,"POST","/api/public/session",Map.of(),csrf);assertEquals(200,response.statusCode(),response.body());
        return new Browser(client,jar,csrf,JSON.readTree(response.body()).path("creatorId").asText());
    }
    private HttpResponse<String> send(HttpClient client,String method,String path,Object data,String csrf)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);
        if(data!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(Browser browser,String path,Object data)throws Exception{return send(browser.client,"POST","/api/public/auth/native"+path,data,browser.csrf);}
    private HttpResponse<String> code(Browser browser,String channel,String value,String purpose)throws Exception{return post(browser,"/code",Map.of("channel",channel,"identifier",value,"purpose",purpose));}
    private static String email(){return "user-"+UUID.randomUUID()+"@example.test";}
    private String cookie(Browser browser){return browser.cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("QF_SESSION")).map(HttpCookie::getValue).findFirst().orElseThrow();}

    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void existingEmailRegistrationDoesNotBlameCorrectCode()throws Exception{
        var browser=browser();String email=email();USERS.add(email);
        assertEquals(200,code(browser,"email",email,"register").statusCode());
        var response=post(browser,"/register",Map.of("email",email,"password","Password123","code","123456"));
        assertEquals(409,response.statusCode(),response.body());
        assertEquals("LOGIN_ACCOUNT_EXISTS",JSON.readTree(response.body()).path("code").asText());
        assertEquals(0,credits.balance(browser.creator));
    }

    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void passwordResetRevokesOldBusinessSession()throws Exception{
        var loggedIn=browser();String email=email();USERS.add(email);
        assertEquals(200,post(loggedIn,"/password/sign-in",Map.of("email",email,"password","correct-password")).statusCode());
        String oldToken=cookie(loggedIn);
        var recoveryBrowser=browser();
        assertEquals(200,code(recoveryBrowser,"email",email,"reset").statusCode());
        assertEquals(200,post(recoveryBrowser,"/password/reset",Map.of("email",email,"password","NewPassword123","code","123456")).statusCode());
        assertTrue(identity.resolve(oldToken).isEmpty(),"Password reset must revoke existing business sessions");
    }
    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void failedSendPreservesPreviouslyValidCode()throws Exception{
        var b=browser();String email=email();
        assertEquals(200,code(b,"email",email,"register").statusCode());
        assertEquals(503,code(b,"email","unavailable-"+email,"register").statusCode());
        var result=post(b,"/register",Map.of("email",email,"password","Password123","code","123456"));
        assertEquals(200,result.statusCode(),result.body());
    }

    @org.springframework.test.annotation.DirtiesContext(methodMode=org.springframework.test.annotation.DirtiesContext.MethodMode.BEFORE_METHOD)
    @Test void sessionEndpointConfirmsGuestSourceAfterNativeLogin()throws Exception {
        String email=email();USERS.add(email);var first=browser();
        assertEquals(200,post(first,"/password/sign-in",Map.of("email",email,"password","correct-password")).statusCode());
        var guest=browser();assertEquals(200,code(guest,"email",email,"sign-in").statusCode());
        assertEquals(200,post(guest,"/email/sign-in",Map.of("email",email,"code","123456")).statusCode());
        var response=send(guest.client,"POST","/api/public/session",Map.of(),guest.csrf);
        assertEquals(200,response.statusCode(),response.body());
        assertEquals(first.creator,JSON.readTree(response.body()).path("creatorId").asText());
        assertEquals(guest.creator,JSON.readTree(response.body()).path("associatedGuestCreatorId").asText());
    }

    @Test void passwordLoginCompletesOidcRotatesSessionAndGrants50Once()throws Exception{
        var browser=browser();String email=email(),old=cookie(browser);USERS.add(email);
        var response=post(browser,"/password/sign-in",Map.of("email",email,"password","correct-password"));
        assertEquals(200,response.statusCode(),response.body());assertEquals("{\"authenticated\":true}",response.body());
        assertTrue(response.headers().firstValue("cache-control").orElse("").contains("no-store"));
        assertTrue(response.headers().allValues("set-cookie").stream().anyMatch(c->c.startsWith("QF_SESSION=")&&c.contains("HttpOnly")&&c.contains("SameSite=Lax")));
        assertTrue(identity.resolve(old).isEmpty());assertEquals(browser.creator,identity.resolve(cookie(browser)).orElseThrow());assertEquals(50,credits.balance(browser.creator));
        var second=browser();assertEquals(200,post(second,"/password/sign-in",Map.of("email",email,"password","correct-password")).statusCode());
        assertEquals(browser.creator,identity.resolve(cookie(second)).orElseThrow());assertEquals(50,credits.balance(browser.creator));
    }
    @Test void emailRegistrationRequiresVerifiedCodeAndSetsPasswordBeforeIdentification()throws Exception{
        var browser=browser();String email=email();assertEquals(200,code(browser,"email",email,"register").statusCode());
        var wrong=post(browser,"/register",Map.of("email",email,"password","correct-password","code","000000"));
        assertEquals(400,wrong.statusCode());assertEquals(0,credits.balance(browser.creator));
        var response=post(browser,"/register",Map.of("email",email,"password","correct-password","code","123456"));
        assertEquals(200,response.statusCode(),response.body());assertTrue(USERS.contains(email));assertEquals(50,credits.balance(browser.creator));
    }
    @Test void phoneOtpAndEmailOtpRegisterWithoutPasswordAndStayOnSameOrigin()throws Exception{
        for(String channel:List.of("phone","email")){
            var browser=browser();String value="phone".equals(channel)?"+447700"+String.format("%06d",new Random().nextInt(1000000)):email();
            var sent=code(browser,channel,value,"sign-in");assertEquals(200,sent.statusCode(),sent.body());assertFalse(sent.body().contains("verificationId"));
            var response=post(browser,"/"+channel+"/sign-in",Map.of(channel,value,"code","123456"));
            assertEquals(200,response.statusCode(),response.body());assertTrue(response.headers().firstValue("location").isEmpty());assertEquals(50,credits.balance(browser.creator));
        }
    }
    @Test void resetRequiresBoundVerifiedCodeAndDoesNotLogInOrGrantCredits()throws Exception{
        var browser=browser();String email=email();USERS.add(email);String token=cookie(browser);
        assertEquals(200,code(browser,"email",email,"reset").statusCode());
        var response=post(browser,"/password/reset",Map.of("email",email,"password","new-password","code","123456"));
        assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("\"reset\":true"));assertEquals(token,cookie(browser));assertEquals(0,credits.balance(browser.creator));
        assertEquals(409,post(browser,"/password/reset",Map.of("email",email,"password","new-password","code","123456")).statusCode());
    }
    @Test void csrfIdentifierPurposeAndBrowserSessionAreEnforced()throws Exception{
        var first=browser();String email=email();int sent=SENT.get();
        assertEquals(403,send(first.client,"POST","/api/public/auth/native/code",Map.of("channel","email","identifier",email,"purpose","register"),null).statusCode());
        assertEquals(sent,SENT.get());assertEquals(200,code(first,"email",email,"register").statusCode());
        var second=browser();assertEquals(409,post(second,"/register",Map.of("email",email,"password","correct-password","code","123456")).statusCode());
        assertEquals(409,post(first,"/password/reset",Map.of("email",email,"password","correct-password","code","123456")).statusCode());
        assertEquals(0,credits.balance(first.creator));
        var other=browser();String original=email();assertEquals(200,code(other,"email",original,"register").statusCode());
        assertEquals(409,post(other,"/register",Map.of("email",email(),"password","correct-password","code","123456")).statusCode());
    }
    @Test void resendRateLimitCannotBeBypassedWithNewBrowserSession()throws Exception{
        String email=email();assertEquals(200,code(browser(),"email",email,"register").statusCode());
        var second=code(browser(),"email",email,"register");assertEquals(429,second.statusCode());assertEquals("60",second.headers().firstValue("retry-after").orElseThrow());
    }
    @Test void fiveWrongCodesInvalidateTheBrowserVerificationFlow()throws Exception{
        var browser=browser();String email=email();assertEquals(200,code(browser,"email",email,"sign-in").statusCode());
        for(int i=0;i<5;i++)assertEquals(400,post(browser,"/email/sign-in",Map.of("email",email,"code","000000")).statusCode());
        assertEquals(409,post(browser,"/email/sign-in",Map.of("email",email,"code","123456")).statusCode());
        assertEquals(0,credits.balance(browser.creator));
    }
    @Test void passwordErrorsAreGenericAndSubmittedSecretsNeverAppearInResponses()throws Exception{
        var browser=browser();String email=email();String password="private-password-value";
        var response=post(browser,"/password/sign-in",Map.of("email",email,"password",password));
        assertEquals(401,response.statusCode());assertFalse(response.body().contains(email));assertFalse(response.body().contains(password));assertFalse(response.body().contains("Logto"));assertEquals(0,credits.balance(browser.creator));
    }
    @Test void invalidSignatureIssuerAudienceExpiryNonceStateAndProfileCannotAuthenticate()throws Exception{
        for(String invalid:List.of("signature","issuer","audience","expiry","nonce","state","unverified","different-email","different-sub","redirect")){
            var browser=browser();String email=invalid+"-"+UUID.randomUUID()+"@example.test";USERS.add(email);String token=cookie(browser);
            var response=post(browser,"/password/sign-in",Map.of("email",email,"password","correct-password"));
            assertTrue(response.statusCode()>=400,invalid+": "+response.body());assertEquals(token,cookie(browser));assertEquals(0,credits.balance(browser.creator),invalid);
        }
    }

    private static void handle(HttpExchange x)throws Exception{
        String path=x.getRequestURI().getPath();
        if(path.equals("/oidc/jwks")){reply(x,200,new JWKSet(KEY.toPublicJWK()).toJSONObject());return;}
        if(path.equals("/api/.well-known/experience")){reply(x,200,Map.of("signIn",Map.of("methods",List.of(Map.of("identifier","email","password",true,"verificationCode",true),Map.of("identifier","phone","verificationCode",true))),"forgotPassword",Map.of("email",true)));return;}
        if(path.equals("/oidc/auth")){
            var query=query(x.getRequestURI().getRawQuery());var f=new Flow();f.id=UUID.randomUUID().toString();f.state=query.get("state");f.nonce=query.get("nonce");f.challenge=query.get("code_challenge");FLOWS.put(f.id,f);
            assertEquals("S256",query.get("code_challenge_method"));assertFalse(query.containsKey("code_verifier"));
            x.getResponseHeaders().add("Set-Cookie","interaction="+f.id+"; Path=/; HttpOnly");redirect(x,"/sign-in");return;
        }
        if(path.equals("/sign-in")){reply(x,200,Map.of("page","provider-ui-not-shown-to-user"));return;}
        if(path.equals("/oidc/token")){
            var form=query(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));var f=FLOWS.get(form.get("code"));
            if(f==null||f.redeemed||!challenge(form.get("code_verifier")).equals(f.challenge)||!REDIRECT.equals(form.get("redirect_uri"))) {reply(x,400,Map.of("code","invalid_grant"));return;}
            f.redeemed=true;String invalid=f.value.split("-")[0];
            var claims=new JWTClaimsSet.Builder().issuer(invalid.equals("issuer")?"https://other.invalid":issuer()).audience(invalid.equals("audience")?"other":"native-client")
                .subject(subject(f)).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(invalid.equals("expiry")?-600:300)))
                .claim("nonce",invalid.equals("nonce")?"wrong":f.nonce).build();
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES384).keyID(KEY.getKeyID()).build(),claims);jwt.sign(new ECDSASigner(invalid.equals("signature")?WRONG:KEY));
            reply(x,200,Map.of("id_token",jwt.serialize(),"access_token",f.id,"token_type","Bearer"));return;
        }
        if(path.equals("/oidc/me")){
            var f=FLOWS.get(x.getRequestHeaders().getFirst("Authorization").replace("Bearer ",""));Map<String,Object> profile=new HashMap<>();
            profile.put("sub",f.value.startsWith("different-sub")?"someone-else":subject(f));profile.put("name","测试歌手");
            if(f.channel.equals("email")){profile.put("email",f.value.startsWith("different-email")?"someone@example.test":f.value);profile.put("email_verified",!f.value.startsWith("unverified"));}
            else{profile.put("phone_number","+"+f.value);profile.put("phone_number_verified",true);}
            reply(x,200,profile);return;
        }
        var f=flow(x);if(f==null){reply(x,400,Map.of("code","session.missing"));return;}
        if(path.equals("/oidc/resume")) {redirect(x,REDIRECT+"?state="+(f.value.startsWith("state-")?"wrong":f.state)+"&code="+f.id);return;}
        JsonNode body=x.getRequestMethod().equals("GET")?JSON.createObjectNode():JSON.readTree(x.getRequestBody().readAllBytes());
        if(path.equals("/api/experience")){f.event=body.path("interactionEvent").asText();reply(x,204,null);return;}
        if(path.equals("/api/experience/verification/password")){
            f.channel="email";f.value=body.path("identifier").path("value").asText();
            if(!USERS.contains(f.value)||!body.path("password").asText().equals("correct-password")){reply(x,422,Map.of("code","session.invalid_credentials"));return;}
            f.verified=true;f.verification="v-"+f.id;reply(x,200,Map.of("verificationId",f.verification));return;
        }
        if(path.equals("/api/experience/verification/verification-code")){
            if(body.path("identifier").path("value").asText().startsWith("unavailable-")){reply(x,503,Map.of("code","connector.unavailable"));return;}
            SENT.incrementAndGet();f.channel=body.path("identifier").path("type").asText();f.value=body.path("identifier").path("value").asText();f.verification="v-"+f.id;
            if(f.channel.equals("phone"))assertTrue(f.value.matches("[0-9]+"));reply(x,200,Map.of("verificationId",f.verification));return;
        }
        if(path.equals("/api/experience/verification/verification-code/verify")){
            if(!"123456".equals(body.path("code").asText())||!Objects.equals(f.verification,body.path("verificationId").asText())||!Objects.equals(f.value,body.path("identifier").path("value").asText())){reply(x,400,Map.of("code","verification_code.code_mismatch"));return;}
            f.verified=true;reply(x,200,Map.of("verificationId",f.verification));return;
        }
        if(path.equals("/api/experience/interaction-event")){assertTrue(f.verified);f.event="Register";reply(x,204,null);return;}
        if(path.equals("/api/experience/profile")){assertTrue(f.verified);assertEquals("Register",f.event);assertEquals("password",body.path("type").asText());reply(x,204,null);return;}
        if(path.equals("/api/experience/identification")){
            if(!f.verified||!Objects.equals(f.verification,body.path("verificationId").asText())){reply(x,400,Map.of("code","session.verification_failed"));return;}
            if(f.event.equals("Register")){
                if(!USERS.add(f.value)){reply(x,422,Map.of("code","user.email_already_in_use"));return;}f.identified=true;
                // Real Logto/Koa responds 201 text/plain Created, rather than a JSON object.
                byte[] created="Created".getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
                x.sendResponseHeaders(201,created.length);x.getResponseBody().write(created);x.close();return;
            }
            if(!USERS.contains(f.value)){reply(x,404,Map.of("code","user.user_not_exist"));return;}
            f.identified=true;reply(x,204,null);return;
        }
        if(path.equals("/api/experience/profile/password")){assertTrue(f.identified);assertEquals("ForgotPassword",f.event);reply(x,204,null);return;}
        if(path.equals("/api/experience/submit")){assertTrue(f.identified);reply(x,200,Map.of("redirectTo",f.value.startsWith("redirect-")?"https://attacker.invalid/":f.event.equals("ForgotPassword")?"/sign-in":"/oidc/resume"));return;}
        reply(x,404,Map.of("code","not_found"));
    }
    private static Flow flow(HttpExchange x){
        for(String header:x.getRequestHeaders().getOrDefault("Cookie",List.of()))for(String part:header.split(";")){
            String[] pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("interaction"))return FLOWS.get(pair[1].replace("\"",""));
        }
        return null;
    }
    private static String subject(Flow f){return UUID.nameUUIDFromBytes(f.value.getBytes(StandardCharsets.UTF_8)).toString();}
    private static String challenge(String verifier)throws Exception{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
    private static Map<String,String> query(String raw){Map<String,String> result=new HashMap<>();for(String pair:raw.split("&")){String[] p=pair.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p.length==2?p[1]:"",StandardCharsets.UTF_8));}return result;}
    private static void redirect(HttpExchange x,String location)throws Exception{x.getResponseHeaders().set("Location",location);x.sendResponseHeaders(303,-1);x.close();}
    private static void reply(HttpExchange x,int status,Object data)throws java.io.IOException{
        byte[] bytes=data==null?new byte[0]:JSON.writeValueAsBytes(data);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,status==204?-1:bytes.length);
        if(status!=204)x.getResponseBody().write(bytes);x.close();
    }
}
