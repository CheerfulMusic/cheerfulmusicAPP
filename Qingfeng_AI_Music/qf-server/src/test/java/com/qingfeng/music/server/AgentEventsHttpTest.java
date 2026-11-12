package com.qingfeng.music.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Only test-profile H2 and loopback HTTP; no external model or production database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:agent_events_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.agent.chat.enabled=true","qingfeng.agent.events.maximum=4","qingfeng.agent.events.per-owner=1"})
@ActiveProfiles("test")
class AgentEventsHttpTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    final ObjectMapper json=new ObjectMapper();
    record Browser(HttpClient client,String conversation){}
    URI uri(String path){return URI.create("http://127.0.0.1:"+port+path);}
    Browser browser()throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=client.send(HttpRequest.newBuilder(uri("/api/public/csrf")).GET().build(),HttpResponse.BodyHandlers.ofString());
        String token=json.readTree(csrf.body()).path("token").asText();
        var created=client.send(HttpRequest.newBuilder(uri("/api/public/session")).header("X-XSRF-TOKEN",token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,created.statusCode());
        var current=client.send(HttpRequest.newBuilder(uri("/api/app/agent/conversations/current")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,current.statusCode());return new Browser(client,json.readTree(current.body()).path("id").asText());
    }
    HttpRequest events(String id){return HttpRequest.newBuilder(uri("/api/app/agent/conversations/"+id+"/events")).header("Accept","text/event-stream").timeout(Duration.ofSeconds(10)).GET().build();}
    @Test void streamRequiresOwnerIsReadOnlyAndBoundsConnections()throws Exception{
        Browser owner=browser(),other=browser();
        assertEquals(401,HttpClient.newHttpClient().send(events(owner.conversation()),HttpResponse.BodyHandlers.ofString()).statusCode());
        var foreign=other.client().send(events(owner.conversation()),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,foreign.statusCode());assertTrue(foreign.body().contains("AGENT_CONVERSATION_NOT_FOUND"));
        int before=db.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class);
        var stream=owner.client().send(events(owner.conversation()),HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200,stream.statusCode());assertEquals("no",stream.headers().firstValue("X-Accel-Buffering").orElse(""));
        try(var reader=new BufferedReader(new InputStreamReader(stream.body(),java.nio.charset.StandardCharsets.UTF_8))){
            var first=new StringBuilder();String line;
            while((line=reader.readLine())!=null&&!line.isEmpty())first.append(line).append('\n');
            assertTrue(first.toString().contains("event:state"),first.toString());
            assertTrue(first.toString().contains("\"messages\":[]"),first.toString());
            assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM agent_message",Integer.class));
            assertEquals(429,owner.client().send(events(owner.conversation()),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
