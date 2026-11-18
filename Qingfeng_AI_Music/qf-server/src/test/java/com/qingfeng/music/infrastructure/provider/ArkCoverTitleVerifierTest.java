package com.qingfeng.music.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class ArkCoverTitleVerifierTest {
 @Test void recognizesOnlyVisibleTextAndRejectsIncompleteOrUnavailableResults() throws Exception {
  var json=new ObjectMapper();var text=new AtomicReference<>("偏偏\n很快乐");var reason=new AtomicReference<>("stop");
  var status=new AtomicInteger(200);var readable=new AtomicBoolean(true);var malformed=new AtomicBoolean();var request=new AtomicReference<String>();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat",exchange->{
   assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));
   request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
   String content=malformed.get()?"not json":json.writeValueAsString(Map.of("title",text.get(),"readable",readable.get()));
   byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",reason.get(),"message",Map.of("role","assistant","content",content)))));
   exchange.sendResponseHeaders(status.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  });server.start();
  try {
   var verifier=new ArkCoverTitleVerifier("test-key","test-model",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient(),Duration.ofSeconds(3));
   assertTrue(verifier.matches(new byte[]{1},"偏偏很快乐"));
   var sent=json.readTree(request.get());assertEquals("disabled",sent.path("thinking").path("type").asText());
   assertEquals("json_object",sent.path("response_format").path("type").asText());
   assertTrue(request.get().contains("data:image/jpeg;base64,"));assertFalse(request.get().contains("偏偏很快乐"));
   text.set("偏偏很快东");assertFalse(verifier.matches(new byte[]{1},"偏偏很快乐"));
   text.set("偏偏/很快乐");assertFalse(verifier.matches(new byte[]{1},"偏偏很快乐"));
   readable.set(false);assertFalse(verifier.matches(new byte[]{1},"偏偏很快乐"));
   reason.set("length");assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"歌名"));
   reason.set("stop");malformed.set(true);assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"歌名"));
   status.set(429);assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"歌名"));
   status.set(503);assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"歌名"));
  } finally {server.stop(0);}
 }
 @Test void boundsTheWholeResponseEvenAfterHeadersArrive() throws Exception {
  var release=new CountDownLatch(1);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat",exchange->{exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){out.write('{');out.flush();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}});server.start();
  try {
   var verifier=new ArkCoverTitleVerifier("test","test",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient(),Duration.ofMillis(300));
   long start=System.nanoTime();assertThrows(HttpTimeoutException.class,()->verifier.matches(new byte[]{1},"歌名"));
   assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);
  } finally {release.countDown();server.stop(0);}
 }
 @Test void boundsBytesBeforeJsonParsing() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/chat",exchange->{exchange.getRequestBody().readAllBytes();byte[] bytes=new byte[70*1024];exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});server.start();
  try {var verifier=new ArkCoverTitleVerifier("test","test",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),HttpClient.newHttpClient(),Duration.ofSeconds(3));assertThrows(IOException.class,()->verifier.matches(new byte[]{1},"歌名"));}
  finally{server.stop(0);}
 }
}
