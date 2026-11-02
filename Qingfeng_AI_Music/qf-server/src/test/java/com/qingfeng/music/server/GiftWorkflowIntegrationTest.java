package com.qingfeng.music.server;

import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GiftWorkflowIntegrationTest {
 @Value("${local.server.port}")int port;
 record Session(HttpClient client,String csrf){}
 String text(HttpResponse<byte[]> response){return new String(response.body(),StandardCharsets.UTF_8);}
 String field(String json,String key){var m=Pattern.compile("\""+key+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);assertTrue(m.find(),"缺少"+key);return m.group(1);}
 HttpResponse<byte[]> call(Session s,String method,String path,String body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
  if(s!=null&&s.csrf()!=null)b.header("X-XSRF-TOKEN",s.csrf());if(body!=null)b.header("Content-Type","application/json");
  return (s==null?HttpClient.newHttpClient():s.client()).send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 Session session()throws Exception{var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();var s=new Session(client,null);String csrf=field(text(call(s,"GET","/api/public/csrf",null)),"token");s=new Session(client,csrf);assertEquals(200,call(s,"POST","/api/public/session","{}").statusCode());return s;}
 final String draft="{\"recipient\":\"小雨\",\"occasion\":\"恋爱纪念\",\"story\":\"secret-only-story\",\"message\":\"原始祝福\",\"style\":\"民谣\",\"title\":\"故事\"}";
 String work(Session s)throws Exception{return field(text(call(s,"POST","/api/app/works",draft)),"id");}
 String lyrics(Session s,String w,String value)throws Exception{return field(text(call(s,"POST","/api/app/works/"+w+"/lyrics","{\"workVersion\":0,\"title\":\"给你的歌\",\"lyrics\":\""+value+"\"}")),"id");}
 byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
 HttpResponse<byte[]> upload(Session s,String w,byte[] data)throws Exception{
  String boundary="QingfengBoundary";var prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../secret.html\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);var suffix=("\r\n--"+boundary+"--\r\n").getBytes();
  var body=ByteBuffer.allocate(prefix.length+data.length+suffix.length).put(prefix).put(data).put(suffix).array();
  return s.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/app/works/"+w+"/audio")).header("X-XSRF-TOKEN",s.csrf()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
 }
 String share(Session s,String w,String revision,String asset)throws Exception{var response=call(s,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+revision+"\",\"assetId\":\""+asset+"\"}");assertEquals(200,response.statusCode(),text(response));return field(text(response),"token");}
 @Test void lyricVersionsAreImmutableAndOwned()throws Exception{
  var a=session();var b=session();String w=work(a),r=lyrics(a,w,"第一版歌词");
  assertEquals(200,call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}").statusCode());
  lyrics(a,w,"第二版歌词");String all=text(call(a,"GET","/api/app/works/"+w+"/lyrics",null));assertTrue(all.contains("第一版歌词"));assertTrue(all.contains("第二版歌词"));assertTrue(all.contains("\"confirmed\":true"));
  assertEquals(404,call(b,"GET","/api/app/works/"+w+"/lyrics",null).statusCode());
 }
 @Test void uploadedAudioCanBeSharedWithoutLeakingStoryAndRevoked()throws Exception{
  var a=session();var b=session();String w=work(a),r=lyrics(a,w,"我们一起看海");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");
  var upload=upload(a,w,wav());assertEquals(200,upload.statusCode(),text(upload));String asset=field(text(upload),"id");assertTrue(text(upload).contains("IMPORTED"));assertFalse(text(upload).contains("storageKey"));
  assertEquals(404,call(b,"GET","/api/app/works/"+w+"/audio/"+asset,null).statusCode());
  String token=share(a,w,r,asset),publicPath="/api/public/gifts/"+token;
  var gift=call(null,"GET",publicPath,null);assertEquals(200,gift.statusCode());assertTrue(text(gift).contains("原始祝福"));assertFalse(text(gift).contains("secret-only-story"));assertFalse(text(gift).contains("ownerId"));
  var ranged=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+publicPath+"/audio")).header("Range","bytes=0-11").GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(206,ranged.statusCode());assertEquals(12,ranged.body().length);
  assertEquals(404,call(b,"DELETE","/api/app/works/"+w+"/shares",null).statusCode());
  assertEquals(200,call(a,"DELETE","/api/app/works/"+w+"/shares",null).statusCode());
  assertEquals(404,call(null,"GET",publicPath,null).statusCode());assertEquals(404,call(null,"GET",publicPath+"/audio",null).statusCode());
 }
 @Test void unconfirmedLyricsAndInvalidAudioCannotBecomeGift()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"还未确认");
  assertEquals(400,upload(a,w,"<script>not audio</script>".getBytes()).statusCode());assertEquals("[]",text(call(a,"GET","/api/app/works/"+w+"/audio",null)));
  String asset=field(text(upload(a,w,wav())),"id");assertEquals(409,call(a,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+r+"\",\"assetId\":\""+asset+"\"}").statusCode());
 }
 @Test void unavailableModelDoesNotCreateFakeQueuedTask()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"手动歌词");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");
  assertEquals(503,call(a,"POST","/api/app/works/"+w+"/generations","{\"revisionId\":\""+r+"\",\"requestKey\":\""+java.util.UUID.randomUUID()+"\"}").statusCode());
  assertEquals("[]",text(call(a,"GET","/api/app/works/"+w+"/generations",null)));
 }
 @Test void publishedGiftIsSnapshotAndOldLyricsCannotFollowEditedWork()throws Exception{
  var a=session();String w=work(a),r=lyrics(a,w,"原始歌词");call(a,"POST","/api/app/works/"+w+"/lyrics/"+r+"/confirm","{}");String asset=field(text(upload(a,w,wav())),"id");String token=share(a,w,r,asset);
  assertEquals(200,call(a,"PUT","/api/app/works/"+w,draft.replace("原始祝福","改后的祝福").replace("}",",\"expectedVersion\":0}")).statusCode());
  String publicText=text(call(null,"GET","/api/public/gifts/"+token,null));assertTrue(publicText.contains("原始祝福"));assertFalse(publicText.contains("改后的祝福"));
  assertEquals(409,call(a,"POST","/api/app/works/"+w+"/shares","{\"revisionId\":\""+r+"\",\"assetId\":\""+asset+"\"}").statusCode());
 }
}
