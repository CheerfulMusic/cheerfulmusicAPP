package com.qingfeng.music.server.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
public class LogtoCallbackSecurityConfiguration {
    @Bean @Order(1)
    SecurityFilterChain logtoCallbackSecurity(HttpSecurity http,ObjectProvider<LogtoOAuthFlow> provider)throws Exception{
        // This chain only handles the OAuth callback; the main chain owns all other API paths.
        http.securityMatcher(LogtoOAuthFlow.CALLBACK);
        var flow=provider.getIfAvailable();if(flow!=null)flow.configure(http);
        return http.csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth->auth.anyRequest().access((authentication,context)->new org.springframework.security.authorization.AuthorizationDecision(flow!=null)))
            .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable).build();
    }
}
