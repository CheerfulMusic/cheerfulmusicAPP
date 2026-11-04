package com.qingfeng.music.server;
import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class DeepSeekLyricsWriterTest {
 @Test void timeoutAndNetworkFailuresHaveDistinctSafeErrorCodes()throws Exception{
  var client=org.mockito.Mockito.mock(HttpClient.class);
  org.mockito.Mockito.doThrow(new java.net.http.HttpTimeoutException("private transport detail")).when(client).send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
  var writer=new DeepSeekLyricsWriter("test-key","offline",URI.create("https://example.invalid"),client);
  var work=new WorkApi.Work("id","朋友","生日","故事","祝福","民谣","",0,Instant.now(),Instant.now());
  var timeout=assertThrows(DomainException.class,()->writer.write(work,"zh-CN"));assertEquals("LYRICS_PROVIDER_TIMEOUT",timeout.code());assertFalse(timeout.getMessage().contains("private"));
  org.mockito.Mockito.doThrow(new java.io.IOException("private network detail")).when(client).send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
  assertEquals("LYRICS_PROVIDER_NETWORK_ERROR",assertThrows(DomainException.class,()->writer.write(work,"zh-CN")).code());
 }
 @Test void sendsConcreteWritingBriefAndPreservesFactsAndProtectedRevisionTextInOneRequest()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();var received=new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode>();var calls=new AtomicInteger();
  String story="妈妈总把橘子剥好放在门口的小碗里，电话里只说路上慢点。",previous="[Verse 1]\n旧的第一段\n[Verse 2]\n门口的小碗还在\nTake your time, my child",requirements="保留第二段原文，其他部分更含蓄";
  server.createContext("/",e->{calls.incrementAndGet();received.set(json.readTree(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));String content=json.writeValueAsString(Map.of("title","门口的小碗","lyrics",previous));byte[] response=json.writeValueAsBytes(Map.of("choices",java.util.List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();});server.start();
  try{
   var writer=new DeepSeekLyricsWriter("offline-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());
   var output=writer.write(new WorkApi.Work("id","妈妈","生日",story,"想让她少操心","温暖民谣","",0,Instant.now(),Instant.now()),"mixed",requirements,previous);
   var request=received.get();String system=request.path("messages").path(0).path("content").asText();var context=json.readTree(request.path("messages").path(1).path("content").asText());
   assertEquals(story,context.path("story").asText());assertEquals(previous,context.path("previousLyrics").asText());assertEquals(requirements,context.path("revisionInstructions").asText());
   assertTrue(system.contains("具体细节、情绪的变化和言外之意"));assertTrue(system.contains("写父母"));assertTrue(system.contains("不强加遗憾、离别或悲伤"));assertTrue(system.contains("儿歌保持简单"));assertTrue(system.contains("不要为" )||system.contains("不为凑韵"));
   assertTrue(system.contains("素材少时收住叙事"));assertTrue(system.contains("该段应原样保留"));assertTrue(system.contains("用户指定保留的中英文句子"));assertTrue(system.contains("输出前自检"));assertTrue(system.contains("只返回一个JSON对象"));
   assertFalse(system.contains(story));assertEquals(previous,output.lyrics());assertEquals(1,calls.get());assertEquals("disabled",request.path("thinking").path("type").asText());
  }finally{server.stop(0);}
 }
 @Test void mixedLanguageIsExplicitlyRequestedAndBilingualLyricsArePreserved()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();String mixed="[Verse 1]\n谢谢你陪着我\n[Chorus]\nYou are my home";
  server.createContext("/",e->{var input=json.readTree(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String instruction=input.path("messages").path(0).path("content").asText();assertTrue(instruction.contains("中英混合"));assertTrue(instruction.contains("英文副歌"));byte[] result=json.writeValueAsBytes(Map.of("choices",java.util.List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(Map.of("title","My home","lyrics",mixed)))))));e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);e.close();});server.start();try{var writer=new DeepSeekLyricsWriter("offline-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());assertEquals(mixed,writer.write(new WorkApi.Work("id","妈妈","生日","回忆","感谢","民谣","",0,Instant.now(),Instant.now()),"mixed").lyrics());}finally{server.stop(0);}
 }
 @Test void sendsStoryAndParsesStructuredLyricsWithoutCallingSuno()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();var calls=new AtomicInteger();
  server.createContext("/",exchange->{calls.incrementAndGet();var body=json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("deepseek-flash",body.path("model").asText());assertEquals("disabled",body.path("thinking").path("type").asText());assertTrue(body.path("messages").path(1).path("content").asText().contains("海边的故事"));
   String content=json.writeValueAsString(Map.of("title","海边的约定","lyrics","[Verse]\n想起海边的你"));byte[] response=json.writeValueAsBytes(Map.of("choices",java.util.List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{var writer=new DeepSeekLyricsWriter("test-only-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());var work=new WorkApi.Work("id","小雨","纪念","海边的故事","祝福","民谣","",0,Instant.now(),Instant.now());assertEquals("海边的约定",writer.write(work,"zh-CN").title());assertEquals(1,calls.get());}finally{server.stop(0);}
 }
 @Test void rejectsIncompleteOutputInsteadOfSavingPartialLyrics()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] response="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{var writer=new DeepSeekLyricsWriter("test-only-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());assertThrows(DomainException.class,()->writer.write(new WorkApi.Work("id","朋友","生日","故事","祝福","民谣","",0,Instant.now(),Instant.now()),"en"));}finally{server.stop(0);}
 }
}
