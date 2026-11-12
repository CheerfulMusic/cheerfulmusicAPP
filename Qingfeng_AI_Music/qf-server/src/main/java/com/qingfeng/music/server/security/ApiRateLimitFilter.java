package com.qingfeng.music.server.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** DB Agent admission remains independent. Optional Redis shares HTTP buckets across instances. */
public final class ApiRateLimitFilter extends OncePerRequestFilter {
    @FunctionalInterface public interface SharedGate {boolean allow(String key,int limit);}
    private record Bucket(long window,int count){}
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final Clock clock;
    private final SharedGate shared;
    public ApiRateLimitFilter(){this(Clock.systemUTC());}
    public ApiRateLimitFilter(Clock clock){this(clock,null);}
    public ApiRateLimitFilter(Clock clock,SharedGate shared){this.clock=clock;this.shared=shared;}
    public boolean allow(String key,int limit){
        if(shared!=null)return shared.allow(key,limit);
        return allowLocal(key,limit);
    }
    private synchronized boolean allowLocal(String key,int limit){
        long window=clock.millis()/60000;
        if(buckets.size()>=10000)buckets.entrySet().removeIf(e->e.getValue().window()!=window);
        var old=buckets.get(key);int count=old!=null&&old.window()==window?old.count():0;
        if(count>=limit||(!buckets.containsKey(key)&&buckets.size()>=10000))return false;
        buckets.put(key,new Bucket(window,count+1));return true;
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String path=request.getRequestURI();
        if(!path.startsWith("/api/")||path.equals("/api/public/system/info")||path.equals("/api/public/csrf")){chain.doFilter(request,response);return;}
        boolean recovery=path.equals("/api/public/session/recover"),write=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        boolean analytics="POST".equals(request.getMethod())&&path.equals("/api/public/events");
        boolean webhook="POST".equals(request.getMethod())&&Set.of("/api/public/payments/airwallex/webhook","/api/public/paypal/webhook").contains(path);
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor=auth!=null&&auth.getPrincipal() instanceof CreatorPrincipal creator?creator.id():request.getRemoteAddr();
        String key=webhook?"payment-webhook:"+path:(analytics?"analytics:":recovery?"recovery:":write?"write:":"read:")+(recovery?request.getRemoteAddr():actor);
        boolean allowed;
        try{allowed=allow(key,webhook?600:analytics?300:recovery?5:write?60:180);}
        catch(RuntimeException unavailable){response.setStatus(503);response.setHeader("Retry-After","30");response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"RATE_LIMIT_UNAVAILABLE\",\"message\":\"请求调度暂不可用，请稍后重试\"}");return;}
        if(!allowed){
            response.setStatus(429);response.setHeader("Retry-After","60");response.setHeader("Cache-Control","no-store");response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"操作较频繁，请稍后再试\"}");return;
        }
        chain.doFilter(request,response);
    }
}
