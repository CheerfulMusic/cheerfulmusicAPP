package com.qingfeng.music.server.mv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WanVideoProviderTest {
 @TempDir Path temp;
 @SuppressWarnings("unchecked") private HttpResponse<String> response(String body) {
  HttpResponse<String> result=mock(HttpResponse.class);when(result.statusCode()).thenReturn(200);when(result.body()).thenReturn(body);return result;
 }
 private WanVideoProvider provider(HttpClient client){return new WanVideoProvider("https://video.example/api/v1/","test-not-secret","wan3.0-video",client);}
 @SuppressWarnings("unchecked") @Test void submitsNativePayloadAndPollsNestedStatus()throws Exception{
  var client=mock(HttpClient.class);
  var submitted=response("{\"output\":{\"task_id\":\"task-123\"}}");var poll=response("{\"output\":{\"task_status\":\"SUCCEEDED\"}}");
  when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(submitted,poll);
  var p=provider(client);assertEquals("task-123",p.submit("海边旅人",5,"16:9","request"));assertEquals("succeeded",p.status("task-123"));
  var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
  var post=requests.getAllValues().get(0);assertEquals("https://video.example/api/v1/services/aigc/video-generation/video-synthesis",post.uri().toString());assertEquals("enable",post.headers().firstValue("X-DashScope-Async").orElseThrow());
  var subscriber=HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
  post.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<java.nio.ByteBuffer>(){
   public void onSubscribe(Flow.Subscription s){subscriber.onSubscribe(s);}
   public void onNext(java.nio.ByteBuffer b){subscriber.onNext(java.util.List.of(b));}
   public void onError(Throwable t){subscriber.onError(t);}public void onComplete(){subscriber.onComplete();}
  });
  var body=new ObjectMapper().readTree(subscriber.getBody().toCompletableFuture().get());assertEquals("wan3.0-video",body.path("model").asText());assertEquals("海边旅人",body.path("input").path("prompt").asText());assertEquals(5,body.path("parameters").path("duration").asInt());assertFalse(body.path("parameters").path("audio").asBoolean());
  assertEquals("https://video.example/api/v1/tasks/task-123",requests.getAllValues().get(1).uri().toString());
 }
 @SuppressWarnings("unchecked") @Test void downloadsSignedOssUrlWithoutCredential()throws Exception{
  var client=mock(HttpClient.class);HttpResponse<InputStream> content=mock(HttpResponse.class);when(content.statusCode()).thenReturn(200);when(content.body()).thenReturn(new ByteArrayInputStream(new byte[1024]));
  var ready=response("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/video.mp4?signature=test\"}}");
  when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(ready,content);
  Path file=temp.resolve("shot.mp4");provider(client).download("task-123",file);assertEquals(1024,Files.size(file));
  var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(requests.capture(),any(HttpResponse.BodyHandler.class));assertTrue(requests.getAllValues().get(1).headers().firstValue("Authorization").isEmpty());
 }
 @SuppressWarnings("unchecked") @Test void ambiguousSubmitIsNotRetried()throws Exception{
  var client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("network"));
  assertTrue(assertThrows(VideoProvider.Failure.class,()->provider(client).submit("x",5,"16:9","id")).uncertain);verify(client,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
 }
 @Test void rejectsUntrustedUrlsAndTaskPaths(){
  for(String url:java.util.List.of("http://x.oss-cn-beijing.aliyuncs.com/a","https://127.0.0.1/a","https://x.oss-cn-beijing.aliyuncs.com.evil.test/a","https://u@x.oss-cn-beijing.aliyuncs.com/a","https://x.oss-cn-beijing.aliyuncs.com:8080/a"))assertThrows(VideoProvider.Failure.class,()->WanVideoProvider.downloadUri(url));
  var client=mock(HttpClient.class);assertThrows(VideoProvider.Failure.class,()->provider(client).status("../escape"));verifyNoInteractions(client);
 }
 @SuppressWarnings("unchecked") @Test void missingTaskIdIsUncertainAndIncompleteResultCannotDownload()throws Exception{
  var client=mock(HttpClient.class);var missing=response("{}");var running=response("{\"output\":{\"task_status\":\"RUNNING\"}}");when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(missing,running);
  var p=provider(client);assertTrue(assertThrows(VideoProvider.Failure.class,()->p.submit("x",5,"16:9","id")).uncertain);assertThrows(VideoProvider.Failure.class,()->p.download("task-123",temp.resolve("shot.mp4")));assertFalse(Files.exists(temp.resolve("shot.mp4")));
 }
}
