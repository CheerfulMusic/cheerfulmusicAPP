package com.qingfeng.music.server.avatar;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:avatar_upload_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.media.storage=local", "OSS_BUCKET=", "qingfeng.generation.scheduler-enabled=false",
    "qingfeng.cover.ai.scheduler-enabled=false", "qingfeng.avatar.ai.scheduler-enabled=false"})
@ActiveProfiles("test")
class AvatarUploadHttpTest {
    private static final Path TEMP = temp();
    private static Path temp(){try{return Files.createTempDirectory("avatar-upload-http-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry registry){
        registry.add("qingfeng.cover.directory",()->TEMP.resolve("covers").toString());
        registry.add("qingfeng.media.directory",()->TEMP.resolve("audio").toString());
    }
    @Value("${local.server.port}") int port;
    private final ObjectMapper json = new ObjectMapper();
    private record Browser(HttpClient client,String csrf,String owner) {}
    private URI url(String path){return URI.create("http://127.0.0.1:"+port+path);}
    private HttpResponse<String> jsonCall(HttpClient client,String method,String path,String csrf,String body)throws Exception{
        var b=HttpRequest.newBuilder(url(path)); if(csrf!=null)b.header("X-XSRF-TOKEN",csrf);
        if(body!=null)b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());return json.readTree(r.body());}
    private Browser browser()throws Exception{
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf=ok(jsonCall(client,"GET","/api/public/csrf",null,null)).path("token").asText();
        String owner=ok(jsonCall(client,"POST","/api/public/session",csrf,"{}")).path("creatorId").asText();
        assertFalse(owner.isBlank());return new Browser(client,csrf,owner);
    }
    private byte[] png(Color color)throws Exception{
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(color);g.fillRect(0,0,32,32);g.dispose();
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    private HttpResponse<String> upload(Browser b,String expected,String csrf,byte[] image)throws Exception{
        String boundary="avatar-"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"expectedCreatorId\"\r\n\r\n"+expected+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(image);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var req=HttpRequest.newBuilder(url("/api/app/avatar")).header("Content-Type","multipart/form-data; boundary="+boundary);
        if(csrf!=null)req.header("X-XSRF-TOKEN",csrf);
        return b.client().send(req.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<byte[]> image(HttpClient client)throws Exception{return client.send(HttpRequest.newBuilder(url("/api/app/avatar/image")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    @Test void csrfAccountSwitchAndPrivateImageIsolation()throws Exception{
        var anon=HttpClient.newHttpClient(); assertNotEquals(200,image(anon).statusCode());
        Browser alice=browser(), bob=browser(); assertNotEquals(alice.owner(),bob.owner());
        assertEquals(403,upload(alice,alice.owner(),null,png(Color.RED)).statusCode());
        assertEquals(404,image(alice.client()).statusCode());
        assertEquals(409,upload(bob,alice.owner(),bob.csrf(),png(Color.RED)).statusCode());
        assertEquals(404,image(bob.client()).statusCode());
        JsonNode saved=ok(upload(alice,alice.owner(),alice.csrf(),png(Color.RED)));
        assertEquals("/api/app/avatar/image",saved.path("imageUrl").asText()); assertFalse(saved.path("version").asText().isBlank());
        var a=image(alice.client()); assertEquals(200,a.statusCode()); assertTrue(a.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals(404,image(bob.client()).statusCode());
        ok(upload(bob,bob.owner(),bob.csrf(),png(Color.BLUE)));
        var b=image(bob.client()); assertEquals(200,b.statusCode()); assertFalse(java.util.Arrays.equals(a.body(),b.body()));
        assertArrayEquals(a.body(),image(alice.client()).body());
    }
}
