package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.Test;
import java.net.http.*;
import java.io.IOException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class SeedanceVideoProviderTest {
 @SuppressWarnings("unchecked") @Test void submitAndPollUseConfiguredGatewayWithoutRedirects()throws Exception{
  var client=mock(HttpClient.class);HttpResponse<String> submitted=mock(HttpResponse.class),poll=mock(HttpResponse.class);
  when(submitted.statusCode()).thenReturn(200);when(submitted.body()).thenReturn("{\"task_id\":\"task_123\"}");
  when(poll.statusCode()).thenReturn(200);when(poll.body()).thenReturn("{\"status\":\"completed\"}");
  when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(submitted,poll);
  var provider=new SeedanceVideoProvider("https://video.example/v1","test-not-secret","seedance-fast",client);
  assertEquals("task_123",provider.submit("海边旅人",5,"16:9","request"));assertEquals("completed",provider.status("task_123"));
  var requests=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(client,times(2)).send(requests.capture(),any(HttpResponse.BodyHandler.class));
  assertEquals("POST",requests.getAllValues().get(0).method());assertEquals("https://video.example/v1/videos",requests.getAllValues().get(0).uri().toString());assertEquals("https://video.example/v1/videos/task_123",requests.getAllValues().get(1).uri().toString());
 }
 @SuppressWarnings("unchecked") @Test void lostSubmitResponseIsUncertainAndNeverRetried()throws Exception{
  var client=mock(HttpClient.class);when(client.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("network"));
  var provider=new SeedanceVideoProvider("https://video.example/v1","test-not-secret","seedance",client);
  assertTrue(assertThrows(VideoProvider.Failure.class,()->provider.submit("x",5,"16:9","id")).uncertain);verify(client,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
 }
 @Test void taskPathCannotEscapeGateway(){var provider=new SeedanceVideoProvider("https://video.example/v1","test","seedance",mock(HttpClient.class));assertThrows(VideoProvider.Failure.class,()->provider.status("../../evil"));assertThrows(IllegalArgumentException.class,()->new SeedanceVideoProvider("http://video.example","test","seedance"));}
}
