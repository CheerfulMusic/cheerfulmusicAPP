package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test") @Import(GuestAssociationConcurrencyTest.PauseConfig.class)
class GuestAssociationConcurrencyTest {
    static final CountDownLatch ENTERED=new CountDownLatch(1),RELEASE=new CountDownLatch(1);
    @TestConfiguration(proxyBeanMethods=false) static class PauseConfig {
        @Bean FilterRegistrationBean<OncePerRequestFilter> auditPause() {
            var bean=new FilterRegistrationBean<OncePerRequestFilter>();bean.setOrder(1000);
            bean.setFilter(new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
                    if(request.getRequestURI().equals("/api/app/works")&&"yes".equals(request.getHeader("X-Audit-Pause"))) {
                        ENTERED.countDown();
                        try {if(!RELEASE.await(20,TimeUnit.SECONDS))throw new ServletException("audit timeout");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ServletException(e);}
                    }
                    chain.doFilter(request,response);
                }
            });return bean;
        }
    }
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Value("${local.server.port}") int port;
    @Test void alreadyAuthenticatedRequestCannotCreateAnOrphanAfterGuestMigration()throws Exception {
        var json=new ObjectMapper();String subject=UUID.randomUUID().toString();
        var first=identity.create();var account=identity.signInWithGoogle(first.token(),first.creatorId(),subject,null,null);
        var guest=identity.create();var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+port;
        var csrf=client.send(HttpRequest.newBuilder(URI.create(base+"/api/public/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        String token=json.readTree(csrf.body()).path("token").asText();
        String cookie=csrf.headers().allValues("set-cookie").stream().map(c->c.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));
        String draft=json.writeValueAsString(Map.of("recipient","家人","occasion","生日","story","登录前正在保存的故事","message","谢谢","style","流行","title","在途歌曲"));
        var request=HttpRequest.newBuilder(URI.create(base+"/api/app/works")).header("Content-Type","application/json")
            .header("Cookie",cookie+"; QF_SESSION="+guest.token()).header("X-XSRF-TOKEN",token).header("X-Audit-Pause","yes")
            .POST(HttpRequest.BodyPublishers.ofString(draft)).build();
        var pending=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        try {
            assertTrue(ENTERED.await(10,TimeUnit.SECONDS));
            var signed=identity.signInWithGoogle(guest.token(),guest.creatorId(),subject,null,null);
            assertEquals(account.creatorId(),signed.creatorId());assertTrue(identity.resolve(guest.token()).isEmpty());
            RELEASE.countDown();var response=pending.get(10,TimeUnit.SECONDS);
            assertEquals(409,response.statusCode(),response.body());
            assertEquals("CREATOR_SESSION_CHANGED",json.readTree(response.body()).path("code").asText());
            assertTrue(works.list(guest.creatorId(),0).isEmpty());
            assertTrue(works.list(signed.creatorId(),0).isEmpty());
            var retry=HttpRequest.newBuilder(request,(name,value)->!name.equalsIgnoreCase("Cookie"))
                .header("Cookie",cookie+"; QF_SESSION="+signed.token()).build();
            var saved=client.send(retry,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,saved.statusCode(),saved.body());
            assertEquals(json.readTree(saved.body()).path("id").asText(),works.list(signed.creatorId(),0).get(0).id());

        }finally{RELEASE.countDown();}
    }
}
