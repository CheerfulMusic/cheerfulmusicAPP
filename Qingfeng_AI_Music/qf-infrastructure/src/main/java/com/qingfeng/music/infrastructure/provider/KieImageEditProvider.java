package com.qingfeng.music.infrastructure.provider;
import com.qingfeng.music.cover.application.ImageEditProvider;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.*;

/** 先上传站内已校验的JPEG，再提交一次图片编辑；付费POST不自动重试。 */
public final class KieImageEditProvider implements ImageEditProvider {
 private final String key;private final boolean enabled;private final URI base,upload;private final HttpClient http;private final java.util.function.Consumer<String> approve;private final ObjectMapper json=new ObjectMapper();
 public KieImageEditProvider(String key,boolean enabled,java.util.function.Consumer<String> approve){this(key,enabled,URI.create("https://api.kie.ai"),URI.create("https://kieai.redpandaai.co/api/file-base64-upload"),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),approve);}
 public KieImageEditProvider(String key,boolean enabled,URI base,URI upload,HttpClient http,java.util.function.Consumer<String> approve){this.key=key.trim();this.enabled=enabled;this.base=base;this.upload=upload;this.http=http;this.approve=approve;}
 public boolean configured(){return enabled&&!key.isBlank();}
 public String model(){return "google/nano-banana-edit";}
 public String submit(byte[] jpeg,String prompt,String model){
  if(!model().equals(model)||jpeg.length>5*1024*1024||jpeg.length<3||jpeg[0]!=(byte)0xff||jpeg[1]!=(byte)0xd8||prompt==null||prompt.isBlank()||prompt.length()>1000)throw new IllegalArgumentException("图片编辑参数无效");
  var file=send(upload,json.writeValueAsString(Map.of("base64Data","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(jpeg),"uploadPath","qingfeng/covers","fileName",UUID.randomUUID()+".jpg")),true);
  if(!file.path("success").asBoolean())throw new IllegalStateException("封面上传未完成");
  String url=file.path("data").path("downloadUrl").asText();https(url);
  String direction="Edit this supplied photo into a square music album cover. Preserve the identity of any people. Do not add text, logos or watermarks. User artistic direction: "+prompt;
  var result=send(base.resolve("/api/v1/jobs/createTask"),json.writeValueAsString(Map.of("model",model,"input",Map.of("prompt",direction,"image_urls",List.of(url),"output_format","png","aspect_ratio","1:1"))),true);
  String task=result.path("data").path("taskId").asText();if(task.isBlank()||task.length()>255)throw new IllegalStateException("封面任务编号未知");return task;
 }
 public Result query(String task,String model){
  var data=send(base.resolve("/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(task,java.nio.charset.StandardCharsets.UTF_8)),null,false).path("data");
  if(!task.equals(data.path("taskId").asText())||!model.equals(data.path("model").asText(model)))throw new IllegalStateException("封面任务不匹配");
  String state=data.path("state").asText();if(state.equals("fail"))return new Result("FAILED",null);
  if(Set.of("waiting","queuing","generating").contains(state))return new Result("RUNNING",null);
  if(!state.equals("success"))throw new IllegalStateException("封面状态未知");
  var result=data.path("resultJson");if(result.isTextual())result=json.readTree(result.asText());String url=result.path("resultUrls").path(0).asText();https(url);approve.accept(url);return new Result("SAVING",url);
 }
 private void https(String value){URI uri=URI.create(value);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||value.length()>4096||(uri.getPort()!=-1&&uri.getPort()!=443))throw new IllegalStateException("图片服务地址无效");}
 private JsonNode send(URI uri,String body,boolean submitting){try{
  var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+key).header("Accept","application/json").timeout(Duration.ofSeconds(45));if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
  var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());int status=response.statusCode();if(submitting&&Set.of(400,401,402,403,404,422,429).contains(status))throw new Rejected();if(status!=200)throw new IllegalStateException("图片服务HTTP响应异常");var parsed=json.readTree(response.body());int code=parsed.path("code").asInt(-1);if(submitting&&Set.of(400,401,402,403,404,422,429).contains(code))throw new Rejected();if(code!=200)throw new IllegalStateException("图片服务响应异常");return parsed;
 }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("封面提交中断");}catch(java.io.IOException e){throw new IllegalStateException("封面连接异常，禁止自动重提");}}
}
