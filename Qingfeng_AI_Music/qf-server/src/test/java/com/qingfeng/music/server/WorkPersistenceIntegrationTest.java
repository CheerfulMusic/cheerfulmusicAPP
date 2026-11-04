package com.qingfeng.music.server;

import java.net.*;
import java.net.http.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实HTTP、安全链、Flyway、MyBatis和H2 MySQL兼容模式；另行验证本地MySQL。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkPersistenceIntegrationTest {
    @Value("${local.server.port}") int port;
    record Browser(HttpClient http,String csrf) {}
    private HttpResponse<String> send(HttpClient client,String method,String path,String body,String csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(csrf!=null) request.header("X-XSRF-TOKEN",csrf);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String field(String json,String key) { var m=Pattern.compile("\""+key+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);assertTrue(m.find(),"缺少字段："+key);return m.group(1); }
    private Browser browser() throws Exception {
        var http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var csrf=send(http,"GET","/api/public/csrf",null,null);assertEquals(200,csrf.statusCode());
        String token=field(csrf.body(),"token");assertEquals(200,send(http,"POST","/api/public/session","{}",token).statusCode());
        return new Browser(http,token);
    }
    private final String draft="{\"recipient\":\"妈妈\",\"occasion\":\"生日祝福\",\"story\":\"一起做饭的回忆\",\"message\":\"谢谢你\",\"style\":\"民谣\",\"title\":\"回家的风\"}";
    @Test void persistsAndRestoresOwnedDraft() throws Exception {
        var b=browser();var created=send(b.http(),"POST","/api/app/works",draft,b.csrf());assertEquals(200,created.statusCode(),created.body());
        String id=field(created.body(),"id");var read=send(b.http(),"GET","/api/app/works/"+id,null,null);assertEquals(200,read.statusCode());assertTrue(read.body().contains("一起做饭的回忆"));assertFalse(read.body().contains("ownerId"));assertEquals("AUTO",field(read.body(),"vocalGender"));
        assertTrue(send(b.http(),"GET","/api/app/works",null,null).body().contains(id));
    }
    @Test void rejectsOtherCreatorAndForgedOwner() throws Exception {
        var a=browser();var b=browser();String id=field(send(a.http(),"POST","/api/app/works",draft,a.csrf()).body(),"id");
        assertEquals(404,send(b.http(),"GET","/api/app/works/"+id,null,null).statusCode());
        assertEquals(404,send(b.http(),"PUT","/api/app/works/"+id,draft.replace("}",",\"expectedVersion\":0}"),b.csrf()).statusCode());
        assertFalse(send(b.http(),"GET","/api/app/works",null,null).body().contains(id));
    }
    @Test void staleVersionCannotOverwriteNewDraft() throws Exception {
        var b=browser();String id=field(send(b.http(),"POST","/api/app/works",draft,b.csrf()).body(),"id");
        String update=draft.replace("回家的风","更新后的歌").replace("}",",\"expectedVersion\":0}");
        assertEquals(200,send(b.http(),"PUT","/api/app/works/"+id,update,b.csrf()).statusCode());
        assertEquals(409,send(b.http(),"PUT","/api/app/works/"+id,update,b.csrf()).statusCode());
    }
    @Test void persistsVoiceSelectionAndRejectsInvalidValues()throws Exception{
        var b=browser();String male=draft.replace("}",",\"vocalGender\":\"MALE\"}");var created=send(b.http(),"POST","/api/app/works",male,b.csrf());assertEquals(200,created.statusCode());String id=field(created.body(),"id");assertEquals("MALE",field(send(b.http(),"GET","/api/app/works/"+id,null,null).body(),"vocalGender"));
        String female=draft.replace("}",",\"vocalGender\":\"FEMALE\",\"expectedVersion\":0}");assertEquals(200,send(b.http(),"PUT","/api/app/works/"+id,female,b.csrf()).statusCode());assertEquals("FEMALE",field(send(b.http(),"GET","/api/app/works/"+id,null,null).body(),"vocalGender"));
        assertEquals(400,send(b.http(),"POST","/api/app/works",draft.replace("}",",\"vocalGender\":\"ROBOT\"}"),b.csrf()).statusCode());
    }
    @Test void csrfAndAuthenticationAreRequired() throws Exception {
        var b=browser();assertEquals(403,send(b.http(),"POST","/api/app/works",draft,null).statusCode());
        assertEquals(401,send(HttpClient.newHttpClient(),"GET","/api/app/works",null,null).statusCode());
    }
    @Test void initialAutosaveCanReplayAfterLostResponseWithoutCreatingAnotherWork()throws Exception{
        var a=browser();String key=java.util.UUID.randomUUID().toString();String input=draft.replace("}",",\"draftKey\":\""+key+"\"}");
        var first=send(a.http(),"POST","/api/app/works",input,a.csrf());assertEquals(200,first.statusCode());assertEquals(key,field(first.body(),"id"));
        var replay=send(a.http(),"POST","/api/app/works",input,a.csrf());assertEquals(200,replay.statusCode());assertEquals(first.body(),replay.body());
        var b=browser();var stolen=send(b.http(),"POST","/api/app/works",input,b.csrf());assertEquals(404,stolen.statusCode());assertFalse(stolen.body().contains("一起做饭"));
    }
}
