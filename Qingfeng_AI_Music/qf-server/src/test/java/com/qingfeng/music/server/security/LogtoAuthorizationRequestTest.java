package com.qingfeng.music.server.security;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogtoAuthorizationRequestTest {
    private static final String REDIRECT="http://127.0.0.1:5173/api/public/auth/logto/callback";

    private LogtoOAuthFlow flow(){
        return flow("");
    }
    private LogtoOAuthFlow flow(String appleIdpName){
        var identity=mock(IdentityApi.class);
        when(identity.resolve("creator-session")).thenReturn(Optional.of("creator-1"));
        var registration=ClientRegistration.withRegistrationId("logto")
            .clientId("client-id").clientSecret("test-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT).scope("openid","profile","email","phone")
            .authorizationUri("https://auth.example.test/oidc/auth")
            .tokenUri("https://auth.example.test/oidc/token")
            .jwkSetUri("https://auth.example.test/oidc/jwks")
            .issuerUri("https://auth.example.test/oidc")
            .userInfoUri("https://auth.example.test/oidc/me")
            .userNameAttributeName("sub").build();
        return new LogtoOAuthFlow(identity,null,"https://auth.example.test/oidc",registration,appleIdpName);
    }
    private MockHttpServletRequest request(){
        var request=new MockHttpServletRequest("POST","/api/public/auth/logto/start");
        request.setCookies(new Cookie("QF_SESSION","creator-session"));
        return request;
    }
    private static Map<String,String> query(String url){
        var values=new HashMap<String,String>();
        for(String part:URI.create(url).getRawQuery().split("&")){
            String[] pair=part.split("=",2);
            values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");
        }
        return values;
    }

    @Test @SuppressWarnings("unchecked") void emailHintAddsOnlySupportedLogtoParametersAndPreservesSavedOidcRequest(){
        var flow=flow();var request=request();
        String url=flow.start(request,new MockHttpServletResponse(),"email"," Person+tag@example.com ");
        var params=query(url);
        assertEquals("https://auth.example.test/oidc/auth",URI.create(url).getScheme()+"://"+URI.create(url).getHost()+URI.create(url).getPath());
        assertEquals("identifier:sign_in",params.get("first_screen"));
        assertEquals("email",params.get("identifier"));
        assertEquals("Person+tag@example.com",params.get("login_hint"));
        assertEquals("code",params.get("response_type"));
        assertEquals(REDIRECT,params.get("redirect_uri"));
        assertEquals("S256",params.get("code_challenge_method"));
        assertFalse(params.get("state").isBlank());
        assertFalse(params.get("nonce").isBlank());
        assertFalse(params.get("code_challenge").isBlank());
        assertFalse(params.containsKey("code_verifier"));
        var repository=(AuthorizationRequestRepository<OAuth2AuthorizationRequest>)ReflectionTestUtils.getField(flow,"requests");
        var callback=new MockHttpServletRequest("GET",LogtoOAuthFlow.CALLBACK);
        callback.setSession(request.getSession());
        callback.setParameter("state",params.get("state"));
        var saved=repository.loadAuthorizationRequest(callback);
        assertNotNull(saved);
        assertEquals(url,saved.getAuthorizationRequestUri());
        assertEquals("creator-1",saved.getAttribute("creator"));
        assertNotNull(saved.getAttribute("code_verifier"));
    }

    @Test void phoneHintUsesE164AndGenericEntryHasNoIdentifierOverride(){
        var flow=flow();var phone=query(flow.start(request(),new MockHttpServletResponse(),"phone","+8618585111735"));
        assertEquals("phone",phone.get("identifier"));
        assertEquals("+8618585111735",phone.get("login_hint"));
        var generic=query(flow.start(request(),new MockHttpServletResponse()));
        assertFalse(generic.containsKey("first_screen"));
        assertFalse(generic.containsKey("identifier"));
        assertFalse(generic.containsKey("login_hint"));
        assertEquals("S256",generic.get("code_challenge_method"));
    }

    @Test void appleUsesConfiguredIdpNameWithoutAcceptingClientSelectedProvider(){
        var flow=flow("apple");
        assertTrue(flow.supportsApple());
        var params=query(flow.start(request(),new MockHttpServletResponse(),"apple",null));
        assertEquals("social:apple",params.get("direct_sign_in"));
        assertFalse(params.containsKey("login_hint"));
        assertFalse(params.containsKey("identifier"));
        assertEquals("S256",params.get("code_challenge_method"));
        assertFalse(flow().supportsApple());
        assertEquals("LOGTO_APPLE_UNAVAILABLE",assertThrows(DomainException.class,
            ()->flow().start(request(),new MockHttpServletResponse(),"apple",null)).code());
        assertThrows(IllegalStateException.class,()->flow("apple:malicious"));
    }

    @Test void encodedHintCannotReplaceOidcStateOrOtherAuthorizationParameters(){
        var params=query(flow().start(request(),new MockHttpServletResponse(),"email","name&state=attacker@example.com"));
        assertEquals("name&state=attacker@example.com",params.get("login_hint"));
        assertNotEquals("attacker@example.com",params.get("state"));
        assertEquals("identifier:sign_in",params.get("first_screen"));
    }

    @Test void malformedTargetsAreRejectedBeforeCreatingAnAuthorizationSession(){
        var flow=flow();
        for(String[] target:List.of(
            new String[]{"email","invalid"},
            new String[]{"phone","18585111735"},
            new String[]{"phone","+00123456789"},
            new String[]{"social","google"},
            new String[]{null,"person@example.com"})){
            var request=request();
            var error=assertThrows(DomainException.class,()->flow.start(request,new MockHttpServletResponse(),target[0],target[1]));
            assertEquals("INVALID_LOGTO_IDENTIFIER",error.code());
            assertNull(request.getSession(false));
        }
    }
}
