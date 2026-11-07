package com.qingfeng.music.server;
import com.qingfeng.music.infrastructure.provider.KieImageEditProvider;
import com.qingfeng.music.cover.application.ImageEditProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class KieImageEditProviderTest{
 HttpServer server;KieImageEditProvider provider;List<String> paths=new ArrayList<>(),bodies=new ArrayList<>(),approved=new ArrayList<>();String create="{\"code\":200,\"data\":{\"taskId\":\"image1\"}}",query="{\"code\":200,\"data\":{\"taskId\":\"image1\",\"model\":\"google/nano-banana-edit\",\"state\":\"success\",\"resultJson\":\"{\\\"resultUrls\\\":[\\\"https://cdn.example/result.png\\\"]}\"}}";int status=200;
 @BeforeEach void start()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",e->{paths.add(e.getRequestURI().getPath());bodies.add(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertEquals("Bearer offline-key",e.getRequestHeaders().getFirst("Authorization"));String answer=e.getRequestURI().getPath().equals("/upload")?"{\"code\":200,\"success\":true,\"data\":{\"downloadUrl\":\"https://tempfile.redpandaai.co/photo.jpg\"}}":e.getRequestMethod().equals("POST")?create:query;byte[] data=answer.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status,data.length);e.getResponseBody().write(data);e.close();});server.start();URI base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());provider=new KieImageEditProvider("offline-key",true,base,base.resolve("/upload"),HttpClient.newHttpClient(),approved::add);}
 @AfterEach void stop(){server.stop(0);}
 @Test void uploadsOnlyTheValidatedPhotoAndSubmitsOneSquareImageEditingTask(){assertEquals("image1",provider.submit(new byte[]{(byte)255,(byte)216,(byte)255},"保留人物，胶片风格",provider.model()));assertEquals(List.of("/upload","/api/v1/jobs/createTask"),paths);var json=new ObjectMapper();assertTrue(json.readTree(bodies.get(0)).path("base64Data").asText().startsWith("data:image/jpeg;base64,"));var body=json.readTree(bodies.get(1));assertEquals("google/nano-banana-edit",body.path("model").asText());assertEquals("1:1",body.path("input").path("aspect_ratio").asText());assertEquals("https://tempfile.redpandaai.co/photo.jpg",body.path("input").path("image_urls").path(0).asText());assertTrue(body.path("input").path("prompt").asText().contains("保留人物，胶片风格"));}
 @Test void queriesCheckTaskAndModelThenApproveOnlyReturnedHttpsResult(){var r=provider.query("image1",provider.model());assertEquals("SAVING",r.status());assertEquals(List.of("https://cdn.example/result.png"),approved);query=query.replace("image1","wrong");assertThrows(IllegalStateException.class,()->provider.query("image1",provider.model()));assertEquals(1,approved.size());}
 @Test void invalidInputsCauseZeroNetworkCallsAndMissingPermissionsAreExplicit(){assertThrows(IllegalArgumentException.class,()->provider.submit(new byte[0],"edit",provider.model()));assertTrue(paths.isEmpty());status=403;assertThrows(ImageEditProvider.Rejected.class,()->provider.submit(new byte[]{(byte)255,(byte)216,(byte)255},"edit",provider.model()));assertEquals(List.of("/upload"),paths);}
 @Test void uncertainPaidSubmissionHasNoAutomaticRetry(){create="{\"code\":500}";assertThrows(IllegalStateException.class,()->provider.submit(new byte[]{(byte)255,(byte)216,(byte)255},"edit",provider.model()));assertEquals(1,paths.stream().filter(p->p.equals("/api/v1/jobs/createTask")).count());}
 @Test void coverCreationSharesTheGenerationGateAfterUploadAndQueriesUseTheReadGate(){
  var creates=new java.util.concurrent.atomic.AtomicInteger();var reads=new java.util.concurrent.atomic.AtomicInteger();
  provider.withRequestLimits(()->{assertEquals(List.of("/upload"),paths);creates.incrementAndGet();},reads::incrementAndGet);
  provider.submit(new byte[]{(byte)255,(byte)216,(byte)255},"edit",provider.model());provider.query("image1",provider.model());
  assertEquals(1,creates.get());assertEquals(1,reads.get());assertEquals(3,paths.size());
 }
 @Test void blockedLocalGateNeverSendsACoverGenerationRequest(){
  provider.withRequestLimits(()->{throw new IllegalStateException("本地限流失败");},()->{});
  assertThrows(ImageEditProvider.Rejected.class,()->provider.submit(new byte[]{(byte)255,(byte)216,(byte)255},"edit",provider.model()));
  assertEquals(List.of("/upload"),paths);
 }
}
