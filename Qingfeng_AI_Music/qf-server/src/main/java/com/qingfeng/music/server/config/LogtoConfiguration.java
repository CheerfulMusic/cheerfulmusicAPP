package com.qingfeng.music.server.config;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
@ConditionalOnProperty(prefix="qingfeng.auth.logto",name="enabled",havingValue="true")
public class LogtoConfiguration {
    @Bean LogtoNativeClient logtoNativeClient(LogtoOAuthFlow flow, JwtDecoderFactory<ClientRegistration> decoders){
        return new LogtoNativeClient(flow,decoders);
    }
    /** Logto signs ID tokens with ES384; legacy Google sign-in still uses RS256. */
    @Bean JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(){
        var factory=new OidcIdTokenDecoderFactory();
        factory.setJwsAlgorithmResolver(client->"logto".equals(client.getRegistrationId())?SignatureAlgorithm.ES384:SignatureAlgorithm.RS256);
        return factory;
    }
    @Bean LogtoOAuthFlow logtoOAuthFlow(IdentityApi identity,LogtoIdentityService identities,
        @Value("${qingfeng.auth.logto.issuer:}")String issuer,
        @Value("${qingfeng.auth.logto.client-id:}")String clientId,
        @Value("${qingfeng.auth.logto.client-secret:}")String clientSecret,
        @Value("${qingfeng.auth.logto.redirect-uri:https://cheerfulmusic.com/api/public/auth/logto/callback}")String redirectUri,
        @Value("${qingfeng.auth.logto.apple-idp-name:}")String appleIdpName){
        return new LogtoOAuthFlow(identity,identities,issuer,clientId,clientSecret,redirectUri,appleIdpName);
    }
}
