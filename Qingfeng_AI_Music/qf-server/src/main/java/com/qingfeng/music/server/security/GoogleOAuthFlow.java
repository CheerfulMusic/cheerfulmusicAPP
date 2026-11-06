package com.qingfeng.music.server.security;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.common.exception.DomainException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.*;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** Google credentials and authorization tokens never reach the browser application. */
public final class GoogleOAuthFlow {
    public static final String CALLBACK="/api/public/auth/google/callback";
    private static final String CONTEXT=GoogleOAuthFlow.class.getName()+".request";
    private static final String CREATOR="qingfeng_creator",TOKEN="qingfeng_token_hash",CREATED="qingfeng_started";
    private final IdentityApi identity;
    private final ClientRegistrationRepository clients;
    private final DefaultOAuth2AuthorizationRequestResolver resolver;
    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> requests;

    public GoogleOAuthFlow(IdentityApi identity,String clientId,String clientSecret,String redirectUri){
        this(identity,registration(clientId,clientSecret,redirectUri));
    }
    private static ClientRegistration registration(String clientId,String clientSecret,String redirectUri){
        if(clientId.isBlank()||clientSecret.isBlank())throw new IllegalStateException("Google login requires private server credentials");
        URI redirect=URI.create(redirectUri);
        boolean production="https".equals(redirect.getScheme())&&"cheerfulmusic.com".equals(redirect.getHost())&&redirect.getPort()==-1;
        boolean local="http".equals(redirect.getScheme())&&Set.of("localhost","127.0.0.1").contains(redirect.getHost());
        if(!(production||local)||!CALLBACK.equals(redirect.getPath())||redirect.getQuery()!=null||redirect.getFragment()!=null||redirect.getUserInfo()!=null)throw new IllegalStateException("Google login redirect URI is invalid");
        return CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(clientId).clientSecret(clientSecret).issuerUri("https://accounts.google.com").redirectUri(redirectUri).scope("openid","email","profile").build();
    }
    // Package-private constructor lets protocol tests use an isolated signed-token provider.
    GoogleOAuthFlow(IdentityApi identity,ClientRegistration client){
        this.identity=identity;clients=new InMemoryClientRegistrationRepository(client);
        resolver=new DefaultOAuth2AuthorizationRequestResolver(clients,"/unused-google-get-entry");
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce().andThen(builder->builder.additionalParameters(parameters->parameters.put("prompt","select_account"))));
        requests=new BoundRequests();
    }

    public String start(HttpServletRequest request,HttpServletResponse response){
        String token=CreatorSessionFilter.token(request);
        String creator=identity.resolve(token).orElseThrow(()->new DomainException("GOOGLE_SESSION_REQUIRED","请刷新页面后重新开始Google登录"));
        request.getSession(true).setMaxInactiveInterval(600);
        var authorization=resolver.resolve(request,"google");
        authorization=OAuth2AuthorizationRequest.from(authorization).attributes(attributes->{
            attributes.put(CREATOR,creator);attributes.put(TOKEN,fingerprint(token));attributes.put(CREATED,Instant.now().getEpochSecond());
        }).build();
        requests.saveAuthorizationRequest(authorization,request,response);
        return authorization.getAuthorizationRequestUri();
    }

    public void configure(HttpSecurity http)throws Exception{
        // QF_SESSION remains the only API identity; OAuth state is a short-lived HttpSession.
        http.securityContext(context->context.securityContextRepository(new NullSecurityContextRepository()));
        http.oauth2Login(oauth->oauth.clientRegistrationRepository(clients)
            .authorizedClientRepository(new DiscardAuthorizedClients())
            .authorizationEndpoint(endpoint->endpoint.authorizationRequestRepository(requests).authorizationRequestResolver(new OAuth2AuthorizationRequestResolver(){
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request){return null;}
                public OAuth2AuthorizationRequest resolve(HttpServletRequest request,String registration){return null;}
            }))
            .redirectionEndpoint(endpoint->endpoint.baseUri(CALLBACK))
            .successHandler(this::success)
            .failureHandler((request,response,error)->finish(request,response,error instanceof OAuth2AuthenticationException oauthError&&"access_denied".equals(oauthError.getError().getErrorCode())?"cancelled":"failed")));
    }

    private void success(HttpServletRequest request,HttpServletResponse response,Authentication authentication)throws IOException{
        String outcome="failed";
        try{
            var saved=(OAuth2AuthorizationRequest)request.getAttribute(CONTEXT);
            if(saved==null||!(authentication.getPrincipal() instanceof OidcUser user))throw new IllegalStateException("Missing Google login context");
            String token=CreatorSessionFilter.token(request);
            if(!Objects.equals(saved.getAttribute(TOKEN),fingerprint(token)))throw new IllegalStateException("Creator session changed");
            var session=identity.signInWithGoogle(token,saved.getAttribute(CREATOR),user.getSubject(),user.getEmail(),user.getFullName());
            GuestDraftAssociation.prepare(request,saved.getAttribute(CREATOR),session.creatorId());
            var cookie=ResponseCookie.from("QF_SESSION",session.token()).httpOnly(true).secure(request.isSecure()).sameSite("Lax").path("/api").maxAge(Duration.ofDays(30)).build();
            response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());outcome="success";
        }catch(DomainException error){if("GOOGLE_ACCOUNT_CONFLICT".equals(error.code()))outcome="conflict";}
        catch(RuntimeException error){/* Never expose provider tokens, credentials or database errors. */}
        finish(request,response,outcome);
    }

    private void finish(HttpServletRequest request,HttpServletResponse response,String outcome)throws IOException{
        SecurityContextHolder.clearContext();
        GuestDraftAssociation.rotate(request);
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");response.setHeader("Referrer-Policy","no-referrer");
        response.setStatus(HttpServletResponse.SC_FOUND);response.setHeader(HttpHeaders.LOCATION,"/account?google="+outcome);
    }

    private static String fingerprint(String value){
        if(value==null)return "";
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }

    private final class BoundRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest>{
        private final HttpSessionOAuth2AuthorizationRequestRepository delegate=new HttpSessionOAuth2AuthorizationRequestRepository();
        public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request){return delegate.loadAuthorizationRequest(request);}
        public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorization,HttpServletRequest request,HttpServletResponse response){delegate.saveAuthorizationRequest(authorization,request,response);}
        public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,HttpServletResponse response){
            var authorization=delegate.removeAuthorizationRequest(request,response);
            if(authorization==null)return null;
            Object created=authorization.getAttribute(CREATED);
            String token=CreatorSessionFilter.token(request);
            if(!(created instanceof Long started)||Instant.now().getEpochSecond()-started>600||!Objects.equals(authorization.getAttribute(TOKEN),fingerprint(token))
                ||identity.resolve(token).filter(id->id.equals(authorization.getAttribute(CREATOR))).isEmpty())return null;
            request.setAttribute(CONTEXT,authorization);return authorization;
        }
    }

    private static final class DiscardAuthorizedClients implements OAuth2AuthorizedClientRepository{
        public <T extends OAuth2AuthorizedClient>T loadAuthorizedClient(String registration,Authentication principal,HttpServletRequest request){return null;}
        public void saveAuthorizedClient(OAuth2AuthorizedClient client,Authentication principal,HttpServletRequest request,HttpServletResponse response){}
        public void removeAuthorizedClient(String registration,Authentication principal,HttpServletRequest request,HttpServletResponse response){}
    }
}
