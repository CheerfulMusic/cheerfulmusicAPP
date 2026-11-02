package com.qingfeng.music.server;
import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.common.exception.DomainException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class DeepSeekLyricsWriterTest {
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
