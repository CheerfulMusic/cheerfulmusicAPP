package com.qingfeng.music.server.config;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.security.GoogleOAuthFlow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
@ConditionalOnProperty(prefix="qingfeng.auth.google",name="enabled",havingValue="true")
public class GoogleOAuthConfiguration {
    @Bean GoogleOAuthFlow googleOAuthFlow(IdentityApi identity,
        @Value("${qingfeng.auth.google.client-id:${GOOGLE_CLIENT_ID:}}")String clientId,
        @Value("${qingfeng.auth.google.client-secret:${GOOGLE_CLIENT_SECRET:}}")String clientSecret,
        @Value("${qingfeng.auth.google.redirect-uri:https://cheerfulmusic.com/api/public/auth/google/callback}")String redirectUri){
        return new GoogleOAuthFlow(identity,clientId,clientSecret,redirectUri);
    }
}
