package com.qingfeng.music.server.avatar;

import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Kie text-to-image API; no automatic retry of a chargeable POST. */
public final class KieAvatarImageProvider implements AvatarImageProvider {
    private static final String MODEL = "google/nano-banana";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Set<Integer> REJECTED_CODES = Set.of(400,401,402,403,404,422,429);
    private final String key;
    private final boolean enabled;
    private final Runnable createGate, readGate;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final URI jobsEndpoint;
    private final Duration responseTimeout;
    private final ProviderAudioUrls approved = new ProviderAudioUrls();
    private final AllowlistedAudioDownloader downloader = new AllowlistedAudioDownloader(Set.of(), approved);
    public KieAvatarImageProvider(String key, boolean enabled, Runnable createGate, Runnable readGate) {
        this(key,enabled,createGate,readGate,URI.create("https://api.kie.ai/api/v1/jobs/"),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),Duration.ofSeconds(45));
    }
    /** Offline transport tests may inject a loopback endpoint and shorter deadline; production stays fixed. */
    KieAvatarImageProvider(String key,boolean enabled,Runnable createGate,Runnable readGate,URI jobsEndpoint,HttpClient http,Duration responseTimeout) {
        if(responseTimeout==null || responseTimeout.isNegative() || responseTimeout.isZero() || responseTimeout.compareTo(Duration.ofSeconds(45))>0)
            throw new IllegalArgumentException("Invalid avatar response deadline");
        this.key=key == null ? "" : key.trim(); this.enabled=enabled; this.createGate=Objects.requireNonNull(createGate); this.readGate=Objects.requireNonNull(readGate);
        this.jobsEndpoint=Objects.requireNonNull(jobsEndpoint);this.http=Objects.requireNonNull(http);this.responseTimeout=responseTimeout;
        if(http.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("Avatar provider redirects must be disabled");
    }
    public boolean configured() { return enabled && !key.isBlank(); }
    public String submit(String prompt) {
        if (!configured()) throw new IllegalStateException("Avatar generation disabled");
        try { createGate.run(); } catch(RuntimeException refused) { throw new Rejected(); }
        var body = json.writeValueAsString(Map.of("model",MODEL,"input",Map.of("prompt",prompt,"output_format","png","aspect_ratio","1:1")));
        JsonNode response = request(jobsEndpoint.resolve("createTask"),body);
        String id=response.path("data").path("taskId").asText();
        if(id.isBlank() || id.length()>255) throw new IllegalStateException("Unknown provider task");
        return id;
    }
    public Result query(String taskId) {
        readGate.run();
        JsonNode data=request(jobsEndpoint.resolve("recordInfo?taskId="+URLEncoder.encode(taskId,StandardCharsets.UTF_8)),null).path("data");
        if(!taskId.equals(data.path("taskId").asText()) || !MODEL.equals(data.path("model").asText(MODEL))) throw new IllegalStateException("Provider task mismatch");
        String state=data.path("state").asText();
        if(Set.of("waiting","queuing","generating").contains(state)) return new Result("RUNNING",null);
        if("fail".equals(state)) return new Result("FAILED",null);
        if(!"success".equals(state)) throw new IllegalStateException("Provider status unknown");
        JsonNode result=data.path("resultJson"); if(result.isTextual()) result=json.readTree(result.asText());
        String url=result.path("resultUrls").path(0).asText();
        approved.approve(url); // Only a URL from an authenticated provider response can be downloaded.
        return new Result("SUCCEEDED",url);
    }
    public InputStream download(String imageUrl) throws IOException { return downloader.open(imageUrl); }
    private JsonNode request(URI uri,String body) {
        try {
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+key).header("Accept","application/json").timeout(responseTimeout);
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=readResponse(request.build(),body!=null);
            if(response.statusCode()!=200)throw new IllegalStateException("Provider request failed");
            JsonNode parsed=json.readTree(response.body());
            if(body!=null && REJECTED_CODES.contains(parsed.path("code").asInt(-1)))throw new Rejected();
            if(parsed.path("code").asInt(-1)!=200)throw new IllegalStateException("Provider response failed");
            return parsed;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Provider request interrupted"); }
        catch(ExecutionException e) {
            Throwable cause=e.getCause();
            while(cause instanceof CompletionException && cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof Rejected refused)throw refused;
            throw new IllegalStateException("Provider response incomplete");
        }
        catch(IOException e) { throw new IllegalStateException("Provider response incomplete"); }
    }

    /** Bound the complete response, including a body that stalls after otherwise valid headers. */
    private HttpResponse<String> readResponse(HttpRequest request,boolean submission) throws InterruptedException,ExecutionException,IOException {
        long deadline=System.nanoTime()+responseTimeout.toNanos();
        var response=http.sendAsync(request,info->{
            // A definitive HTTP refusal does not require trusting or waiting for its error body.
            if(submission && REJECTED_CODES.contains(info.statusCode()))throw new Rejected();
            return boundedBody();
        });
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new java.net.http.HttpTimeoutException("Avatar response deadline exceeded");
            try{return response.get(remaining,TimeUnit.NANOSECONDS);}
            catch(TimeoutException expired){throw new java.net.http.HttpTimeoutException("Avatar response deadline exceeded");}
        } finally {
            // Cancel the original exchange, so a partial response cannot occupy a scheduler thread/socket.
            if(!response.isDone())response.cancel(true);
        }
    }

    private static HttpResponse.BodySubscriber<String> boundedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<String> delegate=HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            private java.util.concurrent.Flow.Subscription subscription;
            private int received;
            private boolean finished;
            public java.util.concurrent.CompletionStage<String> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
            public void onNext(List<java.nio.ByteBuffer> buffers){
                if(finished)return;
                long bytes=0;for(var buffer:buffers)bytes+=buffer.remaining();
                if(bytes>MAX_RESPONSE_BYTES-received){
                    finished=true;subscription.cancel();delegate.onError(new IOException("Avatar response exceeds byte limit"));return;
                }
                received+=(int)bytes;delegate.onNext(buffers);
            }
            public void onError(Throwable error){if(!finished){finished=true;delegate.onError(error);}}
            public void onComplete(){if(!finished){finished=true;delegate.onComplete();}}
        };
    }
}
