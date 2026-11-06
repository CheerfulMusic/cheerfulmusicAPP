package com.qingfeng.music.server.security;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Server-only Experience API adapter. Each browser flow gets an isolated provider cookie jar. */
public final class LogtoNativeClient {
    private final ClientRegistration registration;
    private final JwtDecoder decoder;
    private final URI origin;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private volatile Capabilities capabilities;
    private volatile long capabilitiesUntil;

    public record Capabilities(boolean emailPassword, boolean emailCode, boolean phone, boolean reset) {}
    public record VerifiedUser(String subject, String email, String phone, String name) {}
    public static final class ProviderFailure extends RuntimeException {
        private final int status;
        private final String code;
        ProviderFailure(int status, String code) { super("Identity verification failed"); this.status=status; this.code=code; }
        public int status(){return status;}
        public String code(){return code;}
    }
    public static final class Interaction {
        private final HttpClient http;
        private final String state, nonce, verifier;
        private Interaction(HttpClient http, String state, String nonce, String verifier) {
            this.http=http; this.state=state; this.nonce=nonce; this.verifier=verifier;
        }
    }

    public LogtoNativeClient(LogtoOAuthFlow flow, JwtDecoderFactory<ClientRegistration> decoders) {
        this(flow.clientRegistration(), decoders.createDecoder(flow.clientRegistration()));
    }
    LogtoNativeClient(ClientRegistration registration, JwtDecoder decoder) {
        this.registration=registration; this.decoder=decoder;
        this.origin=URI.create(registration.getProviderDetails().getIssuerUri()).resolve("/");
    }

    public synchronized Capabilities capabilities() {
        if(capabilities!=null && System.currentTimeMillis()<capabilitiesUntil)return capabilities;
        JsonNode config=api(newHttp(),"GET","/api/.well-known/experience",null);
        boolean password=false, email=false, phone=false;
        for(JsonNode method:config.path("signIn").path("methods")) {
            String type=method.path("identifier").asText();
            if("email".equals(type)){password=method.path("password").asBoolean();email=method.path("verificationCode").asBoolean();}
            if("phone".equals(type))phone=method.path("verificationCode").asBoolean();
        }
        capabilities=new Capabilities(password,email,phone,config.path("forgotPassword").path("email").asBoolean());
        capabilitiesUntil=System.currentTimeMillis()+60000;
        return capabilities;
    }

    public Interaction begin(String event) {
        var flow=new Interaction(newHttp(), token(), token(), token());
        String query="response_type=code&client_id="+encode(registration.getClientId())
            +"&redirect_uri="+encode(registration.getRedirectUri())+"&scope=openid%20profile%20email%20phone"
            +"&state="+encode(flow.state)+"&nonce="+encode(flow.nonce)
            +"&code_challenge_method=S256&code_challenge="+encode(digest(flow.verifier))+"&prompt=login";
        follow(flow, URI.create(registration.getProviderDetails().getAuthorizationUri()+"?"+query), false);
        api(flow.http,"PUT","/api/experience",Map.of("interactionEvent",event));
        return flow;
    }

    public String password(Interaction flow,String email,String password) {
        return required(api(flow.http,"POST","/api/experience/verification/password",
            Map.of("identifier",identifier("email",email),"password",password)),"verificationId");
    }
    public String sendCode(Interaction flow,String channel,String value,String event) {
        return required(api(flow.http,"POST","/api/experience/verification/verification-code",
            Map.of("identifier",identifier(channel,value),"interactionEvent",event)),"verificationId");
    }
    public void verifyCode(Interaction flow,String channel,String value,String verification,String code) {
        api(flow.http,"POST","/api/experience/verification/verification-code/verify",
            Map.of("identifier",identifier(channel,value),"verificationId",verification,"code",code));
    }
    public void identify(Interaction flow,String verification) {
        api(flow.http,"POST","/api/experience/identification",Map.of("verificationId",verification));
    }
    public void switchToRegister(Interaction flow) {
        api(flow.http,"PUT","/api/experience/interaction-event",Map.of("interactionEvent","Register"));
    }
    public void registrationPassword(Interaction flow,String password) {
        api(flow.http,"POST","/api/experience/profile",Map.of("type","password","value",password));
    }
    public void resetPassword(Interaction flow,String password) {
        api(flow.http,"PUT","/api/experience/profile/password",Map.of("password",password));
        api(flow.http,"POST","/api/experience/submit",Map.of());
    }

    public VerifiedUser complete(Interaction flow) {
        String redirect=required(api(flow.http,"POST","/api/experience/submit",Map.of()),"redirectTo");
        URI callback=follow(flow,origin.resolve(redirect),true);
        if(!sameCallback(callback))throw unavailable();
        Map<String,String> query=query(callback);
        if(!MessageDigest.isEqual(flow.state.getBytes(StandardCharsets.UTF_8),query.getOrDefault("state","").getBytes(StandardCharsets.UTF_8))
            ||query.containsKey("error")||!query.containsKey("code"))throw unavailable();
        String body="grant_type=authorization_code&code="+encode(query.get("code"))
            +"&redirect_uri="+encode(registration.getRedirectUri())+"&code_verifier="+encode(flow.verifier);
        String basic=Base64.getEncoder().encodeToString((encode(registration.getClientId())+":"+encode(registration.getClientSecret())).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create(registration.getProviderDetails().getTokenUri())).timeout(Duration.ofSeconds(15))
            .header("Content-Type","application/x-www-form-urlencoded").header("Authorization","Basic "+basic)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode tokens=parse(send(flow.http,request));
        org.springframework.security.oauth2.jwt.Jwt claims;
        try{claims=decoder.decode(required(tokens,"id_token"));}
        catch(org.springframework.security.oauth2.jwt.JwtException invalidToken){throw unavailable();}
        if(!flow.nonce.equals(claims.getClaimAsString("nonce"))||claims.getSubject()==null||claims.getSubject().isBlank())throw unavailable();
        // Verified email/phone and profile may be returned only by userinfo. Its subject must match the signed ID token.
        String userInfo=registration.getProviderDetails().getUserInfoEndpoint().getUri();
        checkProvider(URI.create(userInfo));
        JsonNode user=parse(send(flow.http,HttpRequest.newBuilder(URI.create(userInfo)).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer "+required(tokens,"access_token")).GET().build()));
        if(!claims.getSubject().equals(required(user,"sub")))throw unavailable();
        return new VerifiedUser(claims.getSubject(),user.path("email_verified").asBoolean()?nullable(user,"email"):null,
            user.path("phone_number_verified").asBoolean()?nullable(user,"phone_number"):null,nullable(user,"name"));
    }

    private HttpClient newHttp() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build();
    }
    private URI follow(Interaction flow,URI start,boolean expectCallback) {
        URI current=start;
        for(int i=0;i<8;i++) {
            if(sameCallback(current)) { if(expectCallback)return current; throw unavailable(); }
            checkProvider(current);
            HttpResponse<String> response=send(flow.http,HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(15))
                .header("Accept","text/html").GET().build());
            if(response.statusCode()>=300&&response.statusCode()<400) {
                current=current.resolve(response.headers().firstValue("location").orElseThrow(LogtoNativeClient::unavailable));
            } else if(response.statusCode()==200&&!expectCallback) return current;
            else throw unavailable();
        }
        throw unavailable();
    }
    private JsonNode api(HttpClient http,String method,String path,Object data) {
        var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(15))
            .header("Accept","application/json").header("Origin",origin.toString().replaceAll("/$",""))
            .header("Referer",origin.resolve("/sign-in").toString());
        if(data==null)builder.method(method,HttpRequest.BodyPublishers.noBody());
        else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return parse(send(http,builder.build()));
    }
    private HttpResponse<String> send(HttpClient http,HttpRequest request) {
        checkProvider(request.uri());
        try{return http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(java.io.IOException e){throw unavailable();}
    }
    private JsonNode parse(HttpResponse<String> response) {
        // Koa returns text/plain "Created" for successful registration identification, with no JSON payload.
        if(response.statusCode()==201||response.statusCode()==204)return json.createObjectNode();
        JsonNode value;
        try{value=response.body().isBlank()?json.createObjectNode():json.readTree(response.body());}
        catch(RuntimeException malformed){throw unavailable();}
        if(response.statusCode()<200||response.statusCode()>=300)throw new ProviderFailure(response.statusCode(),value.path("code").asText("unknown"));
        return value;
    }
    private void checkProvider(URI uri) {
        if(!Objects.equals(origin.getScheme(),uri.getScheme())||!Objects.equals(origin.getRawAuthority(),uri.getRawAuthority())||uri.getUserInfo()!=null||uri.getFragment()!=null)throw unavailable();
    }
    private boolean sameCallback(URI uri) {
        URI expected=URI.create(registration.getRedirectUri());
        return Objects.equals(expected.getScheme(),uri.getScheme())&&Objects.equals(expected.getRawAuthority(),uri.getRawAuthority())
            &&Objects.equals(expected.getPath(),uri.getPath())&&uri.getFragment()==null&&uri.getUserInfo()==null;
    }
    private static Map<String,String> query(URI uri) {
        Map<String,String> result=new HashMap<>();
        for(String pair:Optional.ofNullable(uri.getRawQuery()).orElse("").split("&")) {
            String[] parts=pair.split("=",2);if(parts.length!=2)continue;
            String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
            if(result.put(key,URLDecoder.decode(parts[1],StandardCharsets.UTF_8))!=null)throw unavailable();
        }
        return result;
    }
    private static Map<String,String> identifier(String channel,String value) {
        // Logto stores international numbers as country code + subscriber digits, without the display '+'.
        return Map.of("type",channel,"value","phone".equals(channel)?value.substring(1):value);
    }
    private String token(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String digest(String value) {
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String required(JsonNode value,String name){String result=nullable(value,name);if(result==null||result.isBlank())throw unavailable();return result;}
    private static String nullable(JsonNode value,String name){return value.path(name).isString()?value.path(name).asText():null;}
    private static ProviderFailure unavailable(){return new ProviderFailure(503,"unavailable");}
}
