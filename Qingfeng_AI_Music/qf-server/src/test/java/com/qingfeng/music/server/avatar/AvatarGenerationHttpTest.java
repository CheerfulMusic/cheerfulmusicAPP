package com.qingfeng.music.server.avatar;

import com.qingfeng.music.agent.api.AgentChatModel;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:avatar_generation_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.agent.chat.enabled=true", "qingfeng.credits.enabled=true",
    "qingfeng.generation.scheduler-enabled=false", "qingfeng.cover.ai.scheduler-enabled=false",
    "qingfeng.avatar.ai.scheduler-enabled=false", "qingfeng.agent.admission.user-rpm=60"})
@ActiveProfiles("test") @Import(AvatarGenerationHttpTest.Offline.class)
class AvatarGenerationHttpTest {
    static final AtomicInteger submits=new AtomicInteger();
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary AgentChatModel avatarModel(){return context -> new AgentChatModel.Plan("generate_avatar",Map.of("prompt","一只蓝猫"),"准备生成头像。");}
        @Bean @Primary AvatarImageProvider avatarProvider(){return new AvatarImageProvider(){
            public boolean configured(){return true;}
            public String submit(String prompt){submits.incrementAndGet();return "offline-task";}
            public Result query(String task){throw new AssertionError("scheduler is disabled");}
            public InputStream download(String url){throw new AssertionError("external downloads are forbidden");}
        };}
    }
    @Value("${local.server.port}") int port;
    @Autowired com.qingfeng.music.credits.CreditsLedger credits;
    private final ObjectMapper json=new ObjectMapper();
    private HttpResponse<String> call(HttpClient client,String method,String path,Object body,String csrf)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf!=null)b.header("X-XSRF-TOKEN",csrf);
        if(body!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> response){assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());}
    private record Browser(HttpClient client,String csrf,String conversation,String owner) {}
    private Browser browser() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf=ok(call(client,"GET","/api/public/csrf",null,null)).path("token").asText();
        String owner=ok(call(client,"POST","/api/public/session",Map.of(),csrf)).path("creatorId").asText();
        credits.credit(owner,20,"avatar-http-test:"+UUID.randomUUID());
        String conversation=ok(call(client,"GET","/api/app/agent/conversations/current",null,null)).path("id").asText();
        return new Browser(client,csrf,conversation,owner);
    }
    @Test void anonymousCsrfConfirmationAndOwnerIsolation() throws Exception {
        submits.set(0);
        var anonymous=HttpClient.newHttpClient();
        assertNotEquals(200,call(anonymous,"GET","/api/app/avatar/generation/latest",null,null).statusCode());
        Browser alice=browser(), bob=browser();
        String messages="/api/app/agent/conversations/"+alice.conversation()+"/messages";
        var request=Map.of("text","生成一张蓝猫头像","requestKey",UUID.randomUUID().toString());
        assertEquals(403,call(alice.client(),"POST",messages,request,null).statusCode());
        assertEquals(404,call(bob.client(),"POST",messages,request,bob.csrf()).statusCode());
        ok(call(alice.client(),"POST",messages,request,alice.csrf()));
        assertEquals(0,submits.get(),"paid provider must not run before user confirmation");
        assertEquals(20,credits.balance(alice.owner()));
        ok(call(alice.client(),"POST",messages,Map.of("text","确认执行","requestKey",UUID.randomUUID().toString()),alice.csrf()));
        assertEquals(1,submits.get());
        assertEquals(15,credits.balance(alice.owner()));
        assertEquals(20,credits.balance(bob.owner()));
        assertEquals("RUNNING",ok(call(alice.client(),"GET","/api/app/avatar/generation/latest",null,null)).path("status").asText());
        assertEquals("NONE",ok(call(bob.client(),"GET","/api/app/avatar/generation/latest",null,null)).path("status").asText());
    }
}
