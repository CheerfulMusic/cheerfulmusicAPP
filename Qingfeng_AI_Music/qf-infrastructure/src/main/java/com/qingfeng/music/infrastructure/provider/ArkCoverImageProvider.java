package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.cover.application.CoverImageProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class ArkCoverImageProvider implements CoverImageProvider {
    public static final String MODEL = "doubao-seedream-5-5-261028";
    private static final URI ENDPOINT = URI.create("https://ark.cn-beijing.volces.com/api/v3/images/generations");
    private final String key;
    private final String model;
    private final URI endpoint;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public ArkCoverImageProvider(String key, String model) {
        this(key, model, ENDPOINT, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ArkCoverImageProvider(String key, String model, URI endpoint, HttpClient client) {
        this.key = key;
        this.model = model;
        this.endpoint = endpoint;
        this.client = client;
    }

    @Override
    public String generate(String prompt) throws IOException, InterruptedException {
        return submit(prompt, null);
    }

    @Override
    public String edit(byte[] image, String prompt) throws IOException, InterruptedException {
        return submit(prompt, "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image));
    }

    private String submit(String prompt, String image) throws IOException, InterruptedException {
        var body = new LinkedHashMap<String,Object>(Map.of("model", model, "prompt", prompt, "size", "1024x1024",
                "output_format", "jpeg", "response_format", "url", "watermark", image != null));
        if (image != null) body.put("image", image);
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status >= 400 && status < 500 && status != 408 && status != 409) throw new Rejected(status);
        if (status != 200) throw new IOException("Ark cover submission outcome unknown");
        try {
            var data = json.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != 1) throw new IOException("Unexpected Ark image result");
            String url = data.path(0).path("url").asText();
            URI uri = URI.create(url);
            if (url.length() > 4096 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("Invalid Ark image URL");
            return url;
        } catch (JacksonException | IllegalArgumentException badResult) {
            throw new IOException("Invalid Ark image response");
        }
    }
}
