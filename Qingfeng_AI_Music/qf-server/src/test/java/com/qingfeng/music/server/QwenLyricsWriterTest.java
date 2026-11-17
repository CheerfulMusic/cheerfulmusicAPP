package com.qingfeng.music.server;
import com.qingfeng.music.infrastructure.provider.QwenLyricsWriter;
import com.qingfeng.music.lyrics.application.LyricsWriter;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class QwenLyricsWriterTest {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static Map<String,Object> song(String title,String lyrics){return new LinkedHashMap<>(Map.of("title",title,"lyrics",lyrics,
  "plan",Map.of("requirements","","suggestedArrangement","Acoustic guitar","negativeTags","","summaryZh","按所选风格编配","summaryEn","Selected style","vocalGender","AUTO")));}
 private static String completion(Object output){return JSON.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",(output instanceof String ? (String)output : JSON.writeValueAsString(output)))))));}
 @SuppressWarnings("unchecked") private static String responseFor(tools.jackson.databind.JsonNode request,Map<String,Object> first){
  var input=JSON.readTree(request.path("messages").path(1).path("content").asText());
  var output=input.has("candidate")?(Map<String,Object>)JSON.readValue(input.path("candidate").toString(),Map.class):first;
  if(input.has("candidate")){
   var plan=new LinkedHashMap<>((Map<String,Object>)output.get("plan"));plan.remove("photoSummary");output.put("plan",plan);
  }
  return completion(input.has("candidate")?output.get("lyrics"):output);
 }
 @Test void timeoutAndNetworkFailuresHaveDistinctSafeErrorCodes()throws Exception{
  var client=org.mockito.Mockito.mock(HttpClient.class);
  org.mockito.Mockito.doThrow(new java.net.http.HttpTimeoutException("private transport detail")).when(client).send(org.mockito.ArgumentMatchers.any(HttpRequest.class),org.mockito.ArgumentMatchers.any(HttpResponse.BodyHandler.class));
  var writer=new QwenLyricsWriter("test-key","offline",URI.create("https://example.invalid"),client);
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
   var writer=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());
   var output=writer.write(new WorkApi.Work("id","妈妈","生日",story,"想让她少操心","温暖民谣","",0,Instant.now(),Instant.now()),"mixed",requirements,previous);
   var request=received.get();String system=request.path("messages").path(0).path("content").asText();var context=json.readTree(request.path("messages").path(1).path("content").asText());
   assertEquals(story,context.path("story").asText());assertEquals(previous,context.path("previousLyrics").asText());assertEquals(requirements,context.path("revisionInstructions").asText());
   assertTrue(system.contains("资深职业作词人"));assertTrue(system.contains("不得编造人物、事件、时间、经历和关系"));
   assertTrue(system.contains("220～320"));assertTrue(system.contains("160～220"));
   assertTrue(system.contains("明确保留的原句优先遵守"));assertTrue(system.contains("# 十一、JSON输出强制规则"));
   assertFalse(system.contains(story));assertEquals(previous,output.lyrics());assertEquals(1,calls.get());
   assertEquals("QWEN", writer.source());
   assertEquals("qwen3.8-flash", request.path("model").asText());
   assertEquals("json_object", request.path("response_format").path("type").asText());
   assertTrue(request.path("enable_thinking").isBoolean());assertFalse(request.path("enable_thinking").asBoolean());
   assertFalse(request.has("thinking"));assertFalse(request.toString().contains("offline-key"));
  }finally{server.stop(0);}
 }
 @Test void mixedLanguageIsExplicitlyRequestedAndBilingualLyricsArePreserved()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();String mixed="[Verse 1]\n谢谢你陪着我\n[Chorus]\nYou are my home";var calls=new AtomicInteger();
  server.createContext("/",e->{calls.incrementAndGet();var input=json.readTree(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String instruction=input.path("messages").path(0).path("content").asText();assertTrue(instruction.contains("中英混合"));assertTrue(instruction.contains("用户指定的段落语言"));byte[] result=responseFor(input,song("My home",mixed)).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);e.close();});server.start();try{var writer=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());assertEquals(mixed,writer.write(new WorkApi.Work("id","妈妈","生日","回忆","感谢","民谣","",0,Instant.now(),Instant.now()),"mixed").lyrics());assertEquals(2,calls.get());}finally{server.stop(0);}
 }
 @Test void sendsStoryAndParsesStructuredLyricsWithoutCallingSuno()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();var calls=new AtomicInteger();
  server.createContext("/",exchange->{calls.incrementAndGet();var body=json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("qwen3.8-flash",body.path("model").asText());assertFalse(body.path("enable_thinking").asBoolean());assertFalse(body.has("thinking"));assertTrue(body.path("messages").path(1).path("content").asText().contains("海边的故事"));
   byte[] response=responseFor(body,song("海边的约定","[Verse]\n想起海边的你")).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{var writer=new QwenLyricsWriter("test-only-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());var work=new WorkApi.Work("id","小雨","纪念","海边的故事","祝福","民谣","",0,Instant.now(),Instant.now());assertEquals("海边的约定",writer.write(work,"zh-CN").title());assertEquals(2,calls.get());}finally{server.stop(0);}
 }
 @Test void refinementPreservesTitleAndPlan()throws Exception{
  String lyrics="[Verse]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己";
  var output=writeWithRefinement(lyrics);assertEquals(lyrics,output.lyrics());assertEquals("小碗",output.title());
  assertEquals("Acoustic guitar",output.plan().suggestedArrangement());assertEquals("AUTO",output.plan().vocalGender());
 }
 @Test void invalidRefinementNeverReturnsInitialDraft()throws Exception{
  for(String text:List.of(" ","字".repeat(5001),"{}","```lyrics\n正文\n```"))
   assertEquals("LYRICS_QUALITY_UNVERIFIED",assertThrows(DomainException.class,()->writeWithRefinement(text)).code());
 }
 private LyricsWriter.Draft writeWithRefinement(String lyrics)throws Exception{
  var client=org.mockito.Mockito.mock(HttpClient.class);var calls=new AtomicInteger();
  org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
   int n=calls.incrementAndGet();assertTrue(n<=2);HttpRequest request=call.getArgument(0);
   if(n==2){var body=JSON.readTree(readRequestBody(request));assertFalse(body.has("response_format"));
    assertTrue(body.path("messages").path(0).path("content").asText().contains("深度二次创作"));
    var input=JSON.readTree(body.path("messages").path(1).path("content").asText());
    assertEquals("门口的小碗",input.path("originalMaterial").path("story").asText());assertTrue(input.has("candidate"));}
   @SuppressWarnings("unchecked") HttpResponse<String> response=org.mockito.Mockito.mock(HttpResponse.class);
   org.mockito.Mockito.when(response.statusCode()).thenReturn(200);
   org.mockito.Mockito.when(response.body()).thenReturn(completion(n==1?song("小碗","[Verse]\n小碗在门口"):lyrics));return response;
  });
  var writer=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("https://example.invalid"),client);
  try{return writer.write(new WorkApi.Work("id","妈妈","生日","门口的小碗","少操心","民谣","小碗",0,Instant.now(),Instant.now()),"zh-CN");}
  finally{assertEquals(2,calls.get());}
 }
 private static String readRequestBody(HttpRequest request){
  var body=new java.io.ByteArrayOutputStream();request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>(){
   public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}public void onNext(java.nio.ByteBuffer value){byte[] bytes=new byte[value.remaining()];value.get(bytes);body.writeBytes(bytes);}public void onError(Throwable error){throw new AssertionError(error);}public void onComplete(){}
  });return body.toString(StandardCharsets.UTF_8);
 }
 @Test void rejectsIncompleteOutputInsteadOfSavingPartialLyrics()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{byte[] response="{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{var writer=new QwenLyricsWriter("test-only-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());assertThrows(DomainException.class,()->writer.write(new WorkApi.Work("id","朋友","生日","故事","祝福","民谣","",0,Instant.now(),Instant.now()),"en"));}finally{server.stop(0);}
 }
}
