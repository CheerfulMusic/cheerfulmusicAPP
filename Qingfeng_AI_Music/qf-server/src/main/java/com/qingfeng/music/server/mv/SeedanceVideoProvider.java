package com.qingfeng.music.server.mv;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Never follows redirects with the API key, logs provider bodies, or retries a POST. */
public final class SeedanceVideoProvider implements VideoProvider {
    private static final java.util.concurrent.ScheduledExecutorService DOWNLOAD_TIMEOUT = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"video-download-timeout");t.setDaemon(true);return t;});
    private final URI base; private final String key,model; private final HttpClient http;
    private final ObjectMapper json=new ObjectMapper();
    public SeedanceVideoProvider(String url,String key,String model){this(url,key,model,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build());}
    SeedanceVideoProvider(String url,String key,String model,HttpClient client){
        this.key=key;this.model=model;this.base=URI.create(url.endsWith("/")?url:url+"/");
        if(!"https".equals(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null)throw new IllegalArgumentException("Video endpoint must be an HTTPS API base URL");
        http=client;
    }
    public boolean available(){return key!=null&&!key.isBlank()&&model!=null&&!model.isBlank();}
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+key);}
    private String task(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,200}"))throw new Failure("VIDEO_INVALID_TASK_ID",true);return "videos/"+id;}
    public String submit(String prompt,int seconds,String ratio,String requestId){
        if(!available())throw new Failure("VIDEO_NOT_CONFIGURED",false);
        var body=Map.of("model",model,"prompt",prompt,"seconds",seconds,"user",requestId,"metadata",Map.of("ratio",ratio,"resolution","720p","generate_audio",false,"watermark",false));
        try{
            var response=http.send(request("videos").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200||response.statusCode()>=300)throw new Failure("VIDEO_SUBMIT_HTTP_"+response.statusCode(),response.statusCode()>=500||response.statusCode()==408);
            var root=json.readTree(response.body());String id=root.path("task_id").asText("");if(id.isBlank())id=root.path("id").asText("");task(id);return id;
        }catch(Failure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Failure("VIDEO_SUBMIT_UNCERTAIN",true);}
    }
    public String status(String id){
        try{
            var response=http.send(request(task(id)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new Failure("VIDEO_POLL_HTTP_"+response.statusCode(),false);
            return json.readTree(response.body()).path("status").asText("unknown").toLowerCase(Locale.ROOT);
        }catch(Failure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Failure("VIDEO_POLL_UNAVAILABLE",false);}
    }
    public void download(String id,Path destination){
        try{
            var response=http.send(request(task(id)+"/content").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            var deadline=DOWNLOAD_TIMEOUT.schedule(()->{try{response.body().close();}catch(java.io.IOException ignored){}},120,java.util.concurrent.TimeUnit.SECONDS);
            try(var input=response.body()){
                if(response.statusCode()!=200)throw new Failure("VIDEO_DOWNLOAD_HTTP_"+response.statusCode(),false);
                long max=300L*1024*1024,total=0;byte[] buffer=new byte[65536];
                try(var output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)){for(int n;(n=input.read(buffer))!=-1;){total+=n;if(total>max)throw new Failure("VIDEO_TOO_LARGE",false);output.write(buffer,0,n);}}
                if(total<100)throw new Failure("VIDEO_EMPTY_CONTENT",false);
            } finally { deadline.cancel(false); }
        }catch(Failure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Failure("VIDEO_DOWNLOAD_UNAVAILABLE",false);}
    }
}
