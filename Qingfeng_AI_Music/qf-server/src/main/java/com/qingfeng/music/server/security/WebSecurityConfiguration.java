package com.qingfeng.music.server.security;

import com.qingfeng.music.server.invite.InviteGateFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/** 公开引导与带令牌的礼物读取；个人写接口要求创建者会话与CSRF。 */
@Configuration(proxyBeanMethods = false)
public class WebSecurityConfiguration {
    @Bean UserDetailsService unavailableIdentity() {
        return username -> { throw new UsernameNotFoundException("身份服务尚未接入"); };
    }

    @Bean @org.springframework.core.annotation.Order(2) SecurityFilterChain apiSecurity(HttpSecurity http, ObjectProvider<CreatorSessionFilter> sessionFilter, ObjectProvider<GoogleOAuthFlow> googleFlow, ObjectProvider<InviteGateFilter> inviteGate) throws Exception {
        var google=googleFlow.getIfAvailable();if(google!=null)google.configure(http);
        var filter=sessionFilter.getIfAvailable();
        if (filter!=null) http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        var invite=inviteGate.getIfAvailable();
        if (invite!=null) http.addFilterBefore(invite, org.springframework.security.web.csrf.CsrfFilter.class);
        if (filter!=null) http.addFilterAfter(new ApiRateLimitFilter(),CreatorSessionFilter.class);
        return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/public/payments/airwallex/webhook", "/api/public/paypal/webhook", "/api/public/events", "/api/public/hot-songs/*/play")
            .ignoringRequestMatchers(request->"POST".equals(request.getMethod())&&"/api/public/auth/sms/deliver".equals(request.getServletPath()))
            .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/invite/status").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/invite/enter").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/","/index.html","/assets/**","/brand/**","/media/**","/create","/create/photo","/works","/works/*","/gift/*","/account","/profile","/artist/*","/recharge","/analytics","/agent","/legal/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,"/gift/*","/brand/share-default-cover.png").permitAll()
                .requestMatchers("/api/public/system/info", "/api/public/csrf", "/api/public/session", "/api/public/session/recover", "/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/events").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/hot-songs").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/hot-songs/*/play").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/auth/google/status",GoogleOAuthFlow.CALLBACK).permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/auth/google/start","/api/public/session/logout").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/auth/logto/status").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/auth/logto/start").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/auth/native/status").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/auth/native/password/sign-in","/api/public/auth/native/code","/api/public/auth/native/register","/api/public/auth/native/password/reset","/api/public/auth/native/phone/sign-in","/api/public/auth/native/email/sign-in").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/payments/airwallex/webhook").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/paypal/webhook").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/public/auth/sms/deliver").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/gifts/*","/api/public/gifts/*/audio","/api/public/gifts/*/cover").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,"/api/public/gifts/*","/api/public/gifts/*/audio","/api/public/gifts/*/cover").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/artists/*","/api/public/artists/*/avatar/*","/api/public/artists/*/songs/*/*/audio","/api/public/artists/*/songs/*/*/cover").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.HEAD,"/api/public/artists/*","/api/public/artists/*/avatar/*","/api/public/artists/*/songs/*/*/audio","/api/public/artists/*/songs/*/*/cover").permitAll()
                .requestMatchers("/api/app/**").hasRole("CREATOR")
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error)->{
                if(request.getRequestURI().startsWith("/api/app/")){response.sendError(401);return;}
                if(!isPageNavigation(request.getRequestURI())){response.sendError(403);return;}
                // 页面级导航未列入白名单：交给 Vue 入口，未知路径由前端 catch-all 渲染。
                // 直接 sendError 会落到 Spring Boot 默认白标错误页，把 Whitelabel 暴露给用户。
                request.getRequestDispatcher("/index.html").forward(request,response);
            }))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .build();
    }

    /**
     * 页面导航判定：无扩展名，且不是 /api、/actuator、/media 这类接口前缀。
     * 接口与静态资源被拒时保持 403；页面导航被拒时改由 Vue 入口接管。
     */
    private static boolean isPageNavigation(String uri){
        if(uri==null||uri.isEmpty())return false;
        if(uri.startsWith("/api/")||uri.startsWith("/actuator/")||uri.startsWith("/media/"))return false;
        int slash=uri.lastIndexOf('/');
        String last=slash<0?uri:uri.substring(slash+1);
        return !last.contains(".");
    }
}
