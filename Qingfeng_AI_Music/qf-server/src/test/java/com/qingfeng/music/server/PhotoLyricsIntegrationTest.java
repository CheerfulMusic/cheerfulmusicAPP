package com.qingfeng.music.server;

import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.lyrics.application.PhotoLyricsWriter;
import com.qingfeng.music.settings.CreativePlan;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "qingfeng.lyrics.photo.enabled=true", "qingfeng.providers.openai.api-key=offline-test",
    "qingfeng.generation.enabled=true", "qingfeng.invite-gate.enabled=false"
})
@ActiveProfiles("test")
@Import({PhotoLyricsIntegrationTest.OfflinePhoto.class,GenerationSubmissionIntegrationTest.Providers.class})
class PhotoLyricsIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ARRANGEMENT="Warm folk, acoustic guitar, slow tempo";
    @Value("${local.server.port}") int port;
    @Autowired AtomicInteger photoCalls;

    @TestConfiguration(proxyBeanMethods=false)
    static class OfflinePhoto {
        @Bean AtomicInteger photoCalls(){return new AtomicInteger();}
        @Bean @Primary PhotoLyricsWriter offlinePhotoWriter(AtomicInteger photoCalls){
            return (work,language,images,direction)->{
                photoCalls.incrementAndGet();
                assertEquals("zh-CN",language);
                try{
                    for (byte[] image : images) {
                        var decoded=ImageIO.read(new ByteArrayInputStream(image));
                        assertEquals(1600,decoded.getWidth());
                        assertEquals(800,decoded.getHeight());
                    }
                    if ("照片故事".equals(work.story())) {
                        assertEquals(5,images.size());
                        var first=ImageIO.read(new ByteArrayInputStream(images.get(0)));
                        var last=ImageIO.read(new ByteArrayInputStream(images.get(4)));
                        assertTrue(new Color(first.getRGB(0,0)).getRed() > 200);
                        assertTrue(new Color(last.getRGB(0,0)).getBlue() > 200);
                    } else {
                        assertEquals(1,images.size());
                    }
                }catch(java.io.IOException invalid){throw new AssertionError(invalid);}
                return new LyricsWriter.Draft("海边","[Verse]\n风吹过海面",
                    new CreativePlan("",ARRANGEMENT,"","温暖民谣","Warm folk","AUTO","两个人坐在海边。"),"OPENAI");
            };
        }
    }

    @Test void uploadCreatesOneUnconfirmedRevisionAndItsPlanReachesTheMusicTask() throws Exception {
        int initialCalls=photoCalls.get();
        var session=session();
        var capabilities=JSON.readTree(call(session,"GET","/api/public/system/info",null).body()).path("capabilities");
        assertTrue(capabilities.path("photoLyrics").asBoolean());
        String work=field(call(session,"POST","/api/app/works",Map.of(
            "recipient","","occasion","","story","","message","","style","自动推荐","title","照片创作")),"id");
        String root="/api/app/works/"+work;
        String key=UUID.randomUUID().toString();
        byte[] png=withTextChunk(photo(Color.ORANGE),10*1024*1024);
        assertEquals(400,upload(session,root,key,new byte[]{1,2,3}).statusCode());
        assertEquals(400,upload(session,root,key,Arrays.copyOf(png,png.length+1)).statusCode());
        assertEquals(404,upload(session(),root,key,png).statusCode());
        assertEquals(initialCalls,photoCalls.get());

        var created=upload(session,root,key,png);
        String revision=field(created,"id");
        assertFalse(JSON.readTree(created.body()).path("confirmed").asBoolean());
        assertEquals("OPENAI",field(created,"source"));
        assertEquals(revision,field(upload(session,root,key,png),"id"));
        assertEquals(409,upload(session,root,key,photo(Color.BLUE)).statusCode());
        assertEquals(initialCalls+1,photoCalls.get());
        assertEquals(revision,field(call(session,"GET",root+"/lyrics/requests/"+key,null),"revisionId"));
        var settings=JSON.readTree(call(session,"GET",root+"/music-settings",null).body());
        assertEquals("两个人坐在海边。",settings.path("photoInspiration").path("summary").asText());
        assertEquals(ARRANGEMENT,settings.path("direction").path("preferences").path("freeText").asText());

        var generate=Map.of("revisionId",revision,"requestKey",UUID.randomUUID().toString());
        assertEquals(409,call(session,"POST",root+"/generations",generate).statusCode());
        assertEquals(200,call(session,"POST",root+"/lyrics/"+revision+"/confirm",Map.of()).statusCode());
        String task=field(call(session,"POST",root+"/generations",generate),"id");
        var snapshot=call(session,"GET",root+"/generations/"+task+"/music-settings",null);
        assertTrue(field(snapshot,"style").contains(ARRANGEMENT));
    }

    @Test void validatesMultiPhotoUploadsAndReplaysOnlyTheSameOrderedBatch() throws Exception {
        int initialCalls=photoCalls.get();
        var session=session();
        String work=field(call(session,"POST","/api/app/works",Map.of(
            "recipient","","occasion","","story","照片故事","message","","style","自动推荐","title","照片创作")),"id");
        String root="/api/app/works/"+work;
        String key=UUID.randomUUID().toString();
        byte[] first=photo(Color.ORANGE), last=photo(Color.BLUE), other=photo(Color.GREEN);
        assertEquals(400,uploadParts(session,root,key,"photos").statusCode());
        assertEquals(400,uploadParts(session,root,key,"photos",first,last,other,other,other,other).statusCode());
        assertEquals(400,uploadParts(session,root,key,"photo",first,last).statusCode());
        assertEquals(400,uploadParts(session,root,key,"photos",first,new byte[]{1,2,3}).statusCode());
        assertEquals(400,uploadParts(session,root,key,"photos",withTextChunk(first,5*1024*1024),last).statusCode());
        assertEquals(initialCalls,photoCalls.get());

        String revision=field(uploadParts(session,root,key,"photos",first,other,other,other,last),"id");
        assertEquals(revision,field(uploadParts(session,root,key,"photos",first,other,other,other,last),"id"));
        assertEquals(409,uploadParts(session,root,key,"photos",last,other,other,other,first).statusCode());
        assertEquals(initialCalls+1,photoCalls.get());
    }

    private byte[] photo(Color color) throws Exception {
        var image=new BufferedImage(1800,900,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0,0,image.getWidth(),image.getHeight());
        graphics.dispose();
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(image,"png",bytes);
        return bytes.toByteArray();
    }

    private byte[] withTextChunk(byte[] png,int size) {
        // A valid ancillary chunk grows the upload without changing the decoded pixels.
        int endChunk=png.length-12;
        byte[] text=new byte[size-png.length-12];
        Arrays.fill(text,(byte)'a');
        text[1]=0;
        var padded=ByteBuffer.allocate(size);
        padded.put(png,0,endChunk).putInt(text.length).putInt(0x74455874).put(text);
        var checksum=new CRC32();
        checksum.update(padded.array(),endChunk+4,4+text.length);
        padded.putInt((int)checksum.getValue()).put(png,endChunk,12);
        return padded.array();
    }

    private record Session(HttpClient client,String csrf){}
    private Session session() throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf=field(call(new Session(client,""),"GET","/api/public/csrf",null),"token");
        var session=new Session(client,csrf);
        assertEquals(200,call(session,"POST","/api/public/session",Map.of()).statusCode());
        return session;
    }
    private String field(HttpResponse<String> response,String name){
        assertEquals(200,response.statusCode(),response.body());
        var value=JSON.readTree(response.body()).path(name).asText();
        assertFalse(value.isBlank(),name);
        return value;
    }
    private HttpResponse<String> call(Session session,String method,String path,Object body) throws Exception {
        var request=request(session,path);
        if(body!=null)request.header("Content-Type","application/json");
        return session.client().send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody()
            :HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(Session session,String root,String key,byte[] photo) throws Exception {
        return uploadParts(session,root,key,"photo",photo);
    }
    private HttpResponse<String> uploadParts(Session session,String root,String key,String field,byte[]... photos) throws Exception {
        String boundary="PhotoIntegrationBoundary";
        var prefix=new StringBuilder();
        Map.of("workVersion","0","requestKey",key,"language","zh-CN").forEach((name,value)->
            prefix.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(name)
                .append("\"\r\n\r\n").append(value).append("\r\n"));
        var body=new ArrayList<HttpRequest.BodyPublisher>();
        body.add(HttpRequest.BodyPublishers.ofString(prefix.toString()));
        for(byte[] photo : photos) {
            body.add(HttpRequest.BodyPublishers.ofString("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field
                +"\"; filename=\"photo.png\"\r\nContent-Type: image/png\r\n\r\n"));
            body.add(HttpRequest.BodyPublishers.ofByteArray(photo));
            body.add(HttpRequest.BodyPublishers.ofString("\r\n"));
        }
        body.add(HttpRequest.BodyPublishers.ofString("--"+boundary+"--\r\n"));
        return session.client().send(request(session,root+"/lyrics/generate-photo")
            .header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.concat(body.toArray(HttpRequest.BodyPublisher[]::new)))
            .build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpRequest.Builder request(Session session,String path){
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-XSRF-TOKEN",session.csrf());
    }
}
