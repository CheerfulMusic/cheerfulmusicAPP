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
 private static Map<String,Object> reviewed(Map<String,Object> draft,String decision,List<Map<String,Object>> issues){
  var output=new LinkedHashMap<>(draft);var lines=((String)draft.get("lyrics")).lines().filter(s->!s.isBlank()&&!s.startsWith("[")).toList();
  output.put("quality",Map.of("decision",decision,"issues",issues,"hookQuote",lines.get(lines.size()-1),"progressionQuote",lines.get(0),"rhythmQuote",lines.get(lines.size()-1),"reason","按原素材核对表达、推进和自然韵律"));return output;
 }
 private static String completion(Object output){return JSON.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",JSON.writeValueAsString(output))))));}
 @SuppressWarnings("unchecked") private static String responseFor(tools.jackson.databind.JsonNode request,Map<String,Object> first){
  var input=JSON.readTree(request.path("messages").path(1).path("content").asText());
  var output=input.has("candidate")?(Map<String,Object>)JSON.readValue(input.path("candidate").toString(),Map.class):first;
  if(input.has("candidate")){
   var plan=new LinkedHashMap<>((Map<String,Object>)output.get("plan"));plan.remove("photoSummary");output.put("plan",plan);
  }
  return completion(input.has("candidate")?reviewed(output,"KEEP",List.of()):output);
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
   assertTrue(system.contains("保留使情绪成立的经历与变化"));
   assertTrue(system.contains("不得擅自编造相识地点"));assertTrue(system.contains("称呼和姓名不能用于推断性别或恋爱关系"));
   assertTrue(system.contains("儿歌：简单、明亮、易懂、容易跟唱"));assertTrue(system.contains("不得为了押韵倒装或凑词"));
   assertTrue(system.contains("素材较少时，不要强行扩写故事"));assertTrue(system.contains("用户指定保留的段落必须原样保留"));
   assertTrue(system.contains("用户指定保留的中英文句子不得删除或翻译"));assertTrue(system.contains("# 八、创作审稿"));
   assertTrue(system.contains("最终只返回一个合法 JSON 对象"));assertTrue(system.contains("篇幅按素材和段落数量分配"));
   assertTrue(system.contains("必须遵循完整标签顺序，不得自行增删段落"));assertTrue(system.contains("全部歌词不超过2500字符"));
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
  server.createContext("/",e->{calls.incrementAndGet();var input=json.readTree(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));String instruction=input.path("messages").path(0).path("content").asText();assertTrue(instruction.contains("中英混合"));assertTrue(instruction.contains("英文副歌"));byte[] result=responseFor(input,song("My home",mixed)).getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,result.length);e.getResponseBody().write(result);e.close();});server.start();try{var writer=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());assertEquals(mixed,writer.write(new WorkApi.Work("id","妈妈","生日","回忆","感谢","民谣","",0,Instant.now(),Instant.now()),"mixed").lyrics());assertEquals(2,calls.get());}finally{server.stop(0);}
 }
 @Test void sendsStoryAndParsesStructuredLyricsWithoutCallingSuno()throws Exception{
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();var calls=new AtomicInteger();
  server.createContext("/",exchange->{calls.incrementAndGet();var body=json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("qwen3.8-flash",body.path("model").asText());assertFalse(body.path("enable_thinking").asBoolean());assertFalse(body.has("thinking"));assertTrue(body.path("messages").path(1).path("content").asText().contains("海边的故事"));
   byte[] response=responseFor(body,song("海边的约定","[Verse]\n想起海边的你")).getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
  try{var writer=new QwenLyricsWriter("test-only-key","qwen3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()),HttpClient.newHttpClient());var work=new WorkApi.Work("id","小雨","纪念","海边的故事","祝福","民谣","",0,Instant.now(),Instant.now());assertEquals("海边的约定",writer.write(work,"zh-CN").title());assertEquals(2,calls.get());}finally{server.stop(0);}
 }
 @Test void freshQwenLyricsMustPassTheSameEditorialContractAndKeepTheOriginalPlan()throws Exception{
  var initial=song("小碗","[Verse 1]\n小碗在门口\n[Chorus]\n谢谢你陪着我");
  var edited=song("小碗","[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
  edited.put("plan",Map.of("requirements","changed","suggestedArrangement","changed","negativeTags","changed","summaryZh","changed","summaryEn","changed","vocalGender","FEMALE"));
  var accepted=writeWithComparison(initial,reviewed(edited,"REWRITE",List.of(Map.of("code","HOOK","quote","谢谢你陪着我","reason","副歌是通用感谢，缺少小碗的关联"))),input->comparison(input,(String)edited.get("lyrics")));
  assertEquals(edited.get("lyrics"),accepted.lyrics());assertEquals("Acoustic guitar",accepted.plan().suggestedArrangement());assertEquals("AUTO",accepted.plan().vocalGender());
  var rejected=reviewed(initial,"REJECT",List.of(Map.of("code","HOOK","quote","谢谢你陪着我","reason","无法形成有效副歌")));
  assertEquals("LYRICS_QUALITY_NOT_MET",assertThrows(DomainException.class,()->writeWithReview(initial,rejected)).code());
  assertEquals("LYRICS_QUALITY_UNVERIFIED",assertThrows(DomainException.class,()->writeWithReview(initial,initial)).code());
  var invented=reviewed(edited,"REWRITE",List.of(Map.of("code","HOOK","quote","原稿没有的句子","reason","不可信的修改依据")));
  assertEquals("LYRICS_QUALITY_UNVERIFIED",assertThrows(DomainException.class,()->writeWithReview(initial,invented)).code());
 }
 @Test void qwenBlindSelectionMayRetainOriginalAndNeverAppliesEditedMusicSettings()throws Exception{
  var initial=song("小碗","[Verse 1]\n小碗在门口\n[Chorus]\n谢谢你陪着我");
  var edited=song("小碗","[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
  var review=reviewed(edited,"REWRITE",List.of(Map.of("code","HOOK","quote","谢谢你陪着我","reason","副歌缺少独特表达")));
  var output=writeWithComparison(initial,review,input->comparison(input,(String)initial.get("lyrics")));
  assertEquals(initial.get("lyrics"),output.lyrics());assertNull(output.source(),"Blind selection must preserve the raw provider draft source; the shared wrapper supplies QWEN attribution");assertEquals("AUTO",output.plan().vocalGender());
 }
 @Test void qwenCannotBypassIndependentRejectionOrInventedComparisonEvidence()throws Exception{
  var initial=song("小碗","[Verse 1]\n小碗在门口\n[Chorus]\n谢谢你陪着我");
  var edited=song("小碗","[Verse 1]\n门口的小碗还在\n[Chorus]\n妈，也留一瓣给自己");
  var review=reviewed(edited,"REWRITE",List.of(Map.of("code","HOOK","quote","谢谢你陪着我","reason","副歌缺少独特表达")));
  assertEquals("LYRICS_QUALITY_NOT_MET",assertThrows(DomainException.class,()->writeWithComparison(initial,review,input->{
   var verdict=comparison(input,(String)edited.get("lyrics"));verdict.put("choice","NEITHER");verdict.put("hookQuote","");verdict.put("progressionQuote","");verdict.put("rhythmQuote","");
   for(String label:List.of("a","b")){@SuppressWarnings("unchecked") var side=(Map<String,Object>)verdict.get(label);side.put("acceptable",false);}return verdict;
  })).code());
  assertEquals("LYRICS_QUALITY_UNVERIFIED",assertThrows(DomainException.class,()->writeWithComparison(initial,review,input->{
   var verdict=comparison(input,(String)edited.get("lyrics"));verdict.put("hookQuote","不存在的核心句");return verdict;
  })).code());
 }
 private static Map<String,Object> comparison(tools.jackson.databind.JsonNode input,String chosen){
  String a=input.path("candidates").path("A").path("lyrics").asText(),b=input.path("candidates").path("B").path("lyrics").asText();
  var lines=chosen.lines().filter(line->!line.isBlank()&&!line.startsWith("[")).toList();
  return new LinkedHashMap<>(Map.of("choice",a.equals(chosen)?"A":"B","a",side(a),"b",side(b),"hookQuote",lines.get(lines.size()-1),"progressionQuote",lines.get(0),"rhythmQuote",lines.get(lines.size()-1),"reason","独立比较原素材与两稿表达"));
 }
 private static Map<String,Object> side(String lyrics){return new LinkedHashMap<>(Map.of("acceptable",true,"quote",lyrics.lines().filter(line->!line.isBlank()&&!line.startsWith("[")).findFirst().orElseThrow(),"reason","对照原素材检查具体观察"));}
 private LyricsWriter.Draft writeWithReview(Map<String,Object> initial,Object edited)throws Exception{
  return writeWithComparison(initial,edited,null);
 }
 private LyricsWriter.Draft writeWithComparison(Map<String,Object> initial,Object edited,java.util.function.Function<tools.jackson.databind.JsonNode,Object> select)throws Exception{
  var client=org.mockito.Mockito.mock(HttpClient.class);var sent=new java.util.ArrayList<HttpRequest>();
  org.mockito.Mockito.when(client.<String>send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
   HttpRequest request=call.getArgument(0);sent.add(request);
   Object output=sent.size()==1?initial:edited;
   if(sent.size()==3){
    assertNotNull(select,"Invalid or rejected editorial results must stop before blind comparison");
    var body=JSON.readTree(readRequestBody(request));var input=JSON.readTree(body.path("messages").path(1).path("content").asText());
    assertEquals(java.util.Set.of("originalMaterial","candidates"),java.util.Set.copyOf(input.propertyNames()));
    assertEquals(java.util.Set.of("A","B"),java.util.Set.copyOf(input.path("candidates").propertyNames()));
    for(String label:List.of("A","B"))assertEquals(java.util.Set.of("title","lyrics"),java.util.Set.copyOf(input.path("candidates").path(label).propertyNames()));
    assertEquals("zh-CN",input.path("originalMaterial").path("lyricsLanguage").asText());
    assertEquals(2400,body.path("max_tokens").asInt());assertFalse(body.path("enable_thinking").asBoolean());
    output=select.apply(input);
   }
   assertTrue(sent.size()<=3,"No fourth quality generation is permitted");
   @SuppressWarnings("unchecked") HttpResponse<String> response=org.mockito.Mockito.mock(HttpResponse.class);
   org.mockito.Mockito.when(response.statusCode()).thenReturn(200);org.mockito.Mockito.when(response.body()).thenReturn(completion(output));return response;
  });
  var writer=new QwenLyricsWriter("offline-key","qwen3.8-flash",URI.create("https://example.invalid"),client);
  try{return writer.write(new WorkApi.Work("id","妈妈","生日","门口的小碗","少操心","民谣","小碗",0,Instant.now(),Instant.now()),"zh-CN");}
  finally{int expected=select==null?2:3;org.mockito.Mockito.verify(client,org.mockito.Mockito.times(expected)).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());assertEquals(expected,sent.size());}
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
