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
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real Kie text-to-image API; no automatic retry of a chargeable POST. */
public final class KieAvatarImageProvider implements AvatarImageProvider {
    private static final String MODEL = "google/nano-banana";
    private final String key;
    private final boolean enabled;
    private final Runnable createGate, readGate;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ProviderAudioUrls approved = new ProviderAudioUrls();
    private final AllowlistedAudioDownloader downloader = new AllowlistedAudioDownloader(Set.of(), approved);
    public KieAvatarImageProvider(String key, boolean enabled, Runnable createGate, Runnable readGate) { this.key=key == null ? "" : key.trim(); this.enabled=enabled; this.createGate=createGate; this.readGate=readGate; }
    public boolean configured() { return enabled && !key.isBlank(); }
    public String submit(String prompt) {
        if (!configured()) throw new IllegalStateException("Avatar generation disabled");
        try { createGate.run(); } catch(RuntimeException refused) { throw new Rejected(); }
        var body = json.writeValueAsString(Map.of("model",MODEL,"input",Map.of("prompt",prompt,"output_format","png","aspect_ratio","1:1")));
        JsonNode response = request(URI.create("https://api.kie.ai/api/v1/jobs/createTask"),body);
        String id=response.path("data").path("taskId").asText();
        if(id.isBlank() || id.length()>255) throw new IllegalStateException("Unknown provider task");
        return id;
    }
    public Result query(String taskId) {
        readGate.run();
        JsonNode data=request(URI.create("https://api.kie.ai/api/v1/jobs/recordInfo?taskId="+URLEncoder.encode(taskId,StandardCharsets.UTF_8)),null).path("data");
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
            var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+key).header("Accept","application/json").timeout(Duration.ofSeconds(45));
            if(body!=null)request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(body!=null && Set.of(400,401,402,403,404,422,429).contains(response.statusCode()))throw new Rejected();
            if(response.statusCode()!=200)throw new IllegalStateException("Provider request failed");
            JsonNode parsed=json.readTree(response.body());
            if(body!=null && Set.of(400,401,402,403,404,422,429).contains(parsed.path("code").asInt(-1)))throw new Rejected();
            if(parsed.path("code").asInt(-1)!=200)throw new IllegalStateException("Provider response failed");
            return parsed;
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Provider request interrupted"); }
        catch(IOException e) { throw new IllegalStateException("Provider request interrupted"); }
    }
}
