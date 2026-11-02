package com.qingfeng.music.server.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** 本地单实例限流；不信任客户端转发头，找回码失败和成功使用同一计数。 */
public final class ApiRateLimitFilter extends OncePerRequestFilter {
    private record Bucket(long window,int count){}
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final Clock clock;
    public ApiRateLimitFilter(){this(Clock.systemUTC());}
    public ApiRateLimitFilter(Clock clock){this.clock=clock;}
    public synchronized boolean allow(String key,int limit){
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
        var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String actor=auth!=null&&auth.getPrincipal() instanceof CreatorPrincipal creator?creator.id():request.getRemoteAddr();
        String key=(recovery?"recovery:":write?"write:":"read:")+(recovery?request.getRemoteAddr():actor);
        if(!allow(key,recovery?5:write?60:180)){
            response.setStatus(429);response.setHeader("Retry-After","60");response.setHeader("Cache-Control","no-store");response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"操作较频繁，请稍后再试\"}");return;
        }
        chain.doFilter(request,response);
    }
}
