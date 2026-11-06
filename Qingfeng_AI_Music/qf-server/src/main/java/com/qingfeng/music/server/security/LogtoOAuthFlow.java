package com.qingfeng.music.server.security;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.common.exception.DomainException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** Server-side OIDC authorization-code flow. Logto tokens never reach the SPA. */
public final class LogtoOAuthFlow {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LogtoOAuthFlow.class);
    public static final String CALLBACK="/api/public/auth/logto/callback";
    private static final String CONTEXT=LogtoOAuthFlow.class.getName()+".request";
    private static final String CREATOR="creator",TOKEN="token_hash",CREATED="started";
    private static final Pattern EMAIL=Pattern.compile("^[^\\s@\\p{Cntrl}]+@[^\\s@\\p{Cntrl}]+\\.[^\\s@\\p{Cntrl}]+$");
    private static final Pattern PHONE=Pattern.compile("\\+[1-9][0-9]{7,14}");
    private static final Pattern SOCIAL_IDP=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final IdentityApi identity;
    private final LogtoIdentityService identities;
    private final ClientRegistrationRepository clients;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> requests=new BoundRequests();
    private final String issuer;
    private final String appleIdpName;

    public LogtoOAuthFlow(IdentityApi identity,LogtoIdentityService identities,String issuer,String clientId,String clientSecret,String redirectUri,String appleIdpName){
        this(identity,identities,issuer,registration(issuer,clientId,clientSecret,redirectUri),appleIdpName);
    }
    private static ClientRegistration registration(String issuer,String clientId,String clientSecret,String redirectUri){
        URI url=URI.create(issuer);
        boolean localIssuer="http".equals(url.getScheme())&&Set.of("localhost","127.0.0.1").contains(url.getHost())&&url.getPort()==3001;
        boolean productionIssuer="https".equals(url.getScheme())&&"auth.cheerfulmusic.com".equals(url.getHost())&&url.getPort()==-1;
        if(!(localIssuer||productionIssuer)||!"/oidc".equals(url.getPath())||url.getQuery()!=null||url.getFragment()!=null||url.getUserInfo()!=null)
            throw new IllegalStateException("Invalid Logto issuer");
        URI redirect=URI.create(redirectUri);
        boolean production="https".equals(redirect.getScheme())&&"cheerfulmusic.com".equals(redirect.getHost())&&redirect.getPort()==-1;
        boolean local="http".equals(redirect.getScheme())&&Set.of("localhost","127.0.0.1").contains(redirect.getHost());
        if(!(production||local)||!CALLBACK.equals(redirect.getPath())||redirect.getQuery()!=null||redirect.getFragment()!=null||redirect.getUserInfo()!=null)
            throw new IllegalStateException("Invalid Logto redirect URI");
        if(clientId.isBlank()||clientSecret.isBlank())throw new IllegalStateException("Logto server credentials required");
        // OIDC discovery supplies Logto's current authorization, token and signing-key endpoints.
        return ClientRegistrations.fromIssuerLocation(issuer).registrationId("logto")
            .clientId(clientId).clientSecret(clientSecret).redirectUri(redirectUri).scope("openid","profile","email","phone").build();
    }
    // A local registration lets protocol tests inspect the authorization request without a live provider.
    LogtoOAuthFlow(IdentityApi identity,LogtoIdentityService identities,String issuer,ClientRegistration registration){
        this(identity,identities,issuer,registration,"");
    }
    LogtoOAuthFlow(IdentityApi identity,LogtoIdentityService identities,String issuer,ClientRegistration registration,String appleIdpName){
        this.identity=identity;this.identities=identities;this.issuer=issuer;
        if(appleIdpName!=null&&!appleIdpName.isBlank()&&!SOCIAL_IDP.matcher(appleIdpName).matches())
            throw new IllegalStateException("Invalid Logto Apple identity provider name");
        this.appleIdpName=appleIdpName==null||appleIdpName.isBlank()?null:appleIdpName;
        clients=new InMemoryClientRegistrationRepository(registration);
        resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/unused-logto-entry");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    }
    public String start(HttpServletRequest request,HttpServletResponse response){
        return start(request,response,null,null);
    }
    public String start(HttpServletRequest request,HttpServletResponse response,String method,String loginHint){
        var target=LoginTarget.parse(method,loginHint,appleIdpName);
        String token=CreatorSessionFilter.token(request);
        String creator=identity.resolve(token).orElseThrow(()->new DomainException("LOGTO_SESSION_REQUIRED","请刷新页面后重新开始登录"));
        request.getSession(true).setMaxInactiveInterval(600);
        var authorization=resolver.resolve(request,"logto");
        String baseAuthorizationUri=authorization.getAuthorizationRequestUri();
        authorization=OAuth2AuthorizationRequest.from(authorization)
            .additionalParameters(parameters->{
                if(target!=null){
                    if("apple".equals(target.method()))parameters.put("direct_sign_in","social:"+appleIdpName);
                    else{
                        parameters.put("first_screen","identifier:sign_in");
                        parameters.put("identifier",target.method());
                        parameters.put("login_hint",target.hint());
                    }
                }
            })
            .attributes(attributes->{attributes.put(CREATOR,creator);attributes.put(TOKEN,fingerprint(token));attributes.put(CREATED,Instant.now().getEpochSecond());})
            .build();
        if(target!=null){
            // Spring's default URI builder leaves '+' literal; in a query it is decoded as a space.
            // Append strictly form-encoded Logto parameters to the original OIDC URI instead.
            String custom="apple".equals(target.method())
                ?"direct_sign_in="+URLEncoder.encode("social:"+appleIdpName,StandardCharsets.UTF_8)
                :"first_screen=identifier%3Asign_in&identifier="+target.method()
                    +"&login_hint="+URLEncoder.encode(target.hint(),StandardCharsets.UTF_8);
            authorization=OAuth2AuthorizationRequest.from(authorization)
                .authorizationRequestUri(baseAuthorizationUri+(baseAuthorizationUri.contains("?")?"&":"?")+custom).build();
        }
        requests.saveAuthorizationRequest(authorization,request,response);
        return authorization.getAuthorizationRequestUri();
    }
    public boolean supportsApple(){return appleIdpName!=null;}
    /** Only configured, validated origins contribute to the Account Center redirect. */
    public URI accountCenterUri(){
        URI issuerUri=URI.create(issuer);
        URI appRedirect=URI.create(clients.findByRegistrationId("logto").getRedirectUri());
        URI returnUri=appRedirect.resolve("/account");
        String origin=issuerUri.getScheme()+"://"+issuerUri.getAuthority();
        return URI.create(origin+"/account/security?redirect="+URLEncoder.encode(returnUri.toString(),StandardCharsets.UTF_8));
    }
    private record LoginTarget(String method,String hint){
        static LoginTarget parse(String method,String hint,String appleIdpName){
            if(method==null&&hint==null)return null;
            if("apple".equals(method)){
                if(hint!=null&&!hint.isBlank())throw invalid();
                if(appleIdpName==null)throw new DomainException("LOGTO_APPLE_UNAVAILABLE","Apple 登录暂未启用");
                return new LoginTarget("apple",null);
            }
            if(!"email".equals(method)&&!"phone".equals(method))throw invalid();
            if(hint==null||hint.length()>254)throw invalid();
            String value=hint.trim();
            if("email".equals(method)?!EMAIL.matcher(value).matches():!PHONE.matcher(value).matches())throw invalid();
            return new LoginTarget(method,value);
        }
        private static DomainException invalid(){return new DomainException("INVALID_LOGTO_IDENTIFIER","请填写有效的邮箱或国际格式手机号");}
    }
    public void configure(HttpSecurity http)throws Exception{
        http.securityContext(context->context.securityContextRepository(new NullSecurityContextRepository()));
        http.oauth2Login(oauth->oauth.clientRegistrationRepository(clients)
            .authorizedClientRepository(new DiscardClients())
            .authorizationEndpoint(endpoint->endpoint.authorizationRequestRepository(requests).authorizationRequestResolver(new OAuth2AuthorizationRequestResolver(){
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request){return null;}
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request,String registration){return null;}
            }))
            .redirectionEndpoint(endpoint->endpoint.baseUri(CALLBACK))
            .successHandler(this::success)
            .failureHandler((request,response,error)->{
                String code=error instanceof OAuth2AuthenticationException oauthError?oauthError.getError().getErrorCode():error.getClass().getSimpleName();
                log.warn("Logto OIDC callback rejected: {}",code);
                finish(request,response,"access_denied".equals(code)?"cancelled":"failed");
            }));
    }
    private void success(HttpServletRequest request,HttpServletResponse response,Authentication authentication)throws IOException{
        String outcome="failed";
        try{
            var saved=(OAuth2AuthorizationRequest)request.getAttribute(CONTEXT);
            if(saved==null||!(authentication.getPrincipal() instanceof OidcUser user))throw new IllegalStateException("Missing Logto context");
            String token=CreatorSessionFilter.token(request);
            if(!Objects.equals(saved.getAttribute(TOKEN),fingerprint(token)))throw new IllegalStateException("Creator session changed");
            String email=Boolean.TRUE.equals(user.getClaim("email_verified"))?user.getEmail():null;
            String phone=Boolean.TRUE.equals(user.getClaim("phone_number_verified"))?user.getClaimAsString("phone_number"):null;
            var session=identities.signIn(token,saved.getAttribute(CREATOR),issuer,user.getSubject(),email,phone,user.getFullName());
            var cookie=ResponseCookie.from("QF_SESSION",session.token()).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/api").maxAge(Duration.ofDays(30)).build();
            response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());outcome="success";
        }catch(com.qingfeng.music.common.exception.DomainException error){log.warn("Logto identity binding rejected: {}",error.code());if("LOGTO_ACCOUNT_CONFLICT".equals(error.code()))outcome="conflict";}
        catch(RuntimeException error){log.warn("Logto identity binding failed: {}",error.getClass().getSimpleName());}
        finish(request,response,outcome);
    }
    private void finish(HttpServletRequest request,HttpServletResponse response,String outcome)throws IOException{
        SecurityContextHolder.clearContext();var session=request.getSession(false);if(session!=null)session.invalidate();
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setStatus(HttpServletResponse.SC_FOUND);response.setHeader(HttpHeaders.LOCATION,"/account?logto="+outcome);
    }
    private static String fingerprint(String value){
        if(value==null)return "";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private final class BoundRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest>{
        private final HttpSessionOAuth2AuthorizationRequestRepository delegate=new HttpSessionOAuth2AuthorizationRequestRepository();
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request){return delegate.loadAuthorizationRequest(request);}
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,HttpServletRequest request,HttpServletResponse response){delegate.saveAuthorizationRequest(authorization,request,response);}
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,HttpServletResponse response){
            var authorization=delegate.removeAuthorizationRequest(request,response);if(authorization==null)return null;
            Object created=authorization.getAttribute(CREATED);String token=CreatorSessionFilter.token(request);
            if(!(created instanceof Long started)||Instant.now().getEpochSecond()-started>600||!Objects.equals(authorization.getAttribute(TOKEN),fingerprint(token))
                ||identity.resolve(token).filter(id->id.equals(authorization.getAttribute(CREATOR))).isEmpty())return null;
            request.setAttribute(CONTEXT,authorization);return authorization;
        }
    }
    private static final class DiscardClients implements OAuth2AuthorizedClientRepository{
        public <T extends OAuth2AuthorizedClient>T loadAuthorizedClient(String registration,Authentication principal,HttpServletRequest request){return null;}
        public void saveAuthorizedClient(OAuth2AuthorizedClient client,Authentication principal,HttpServletRequest request,HttpServletResponse response){}
        public void removeAuthorizedClient(String registration,Authentication principal,HttpServletRequest request,HttpServletResponse response){}
    }
}
