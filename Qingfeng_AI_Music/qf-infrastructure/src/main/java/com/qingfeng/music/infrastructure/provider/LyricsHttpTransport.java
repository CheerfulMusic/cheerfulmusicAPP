package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

final class LyricsHttpTransport {
    private LyricsHttpTransport() {}

    static HttpResponse<String> post(HttpClient client, URI endpoint, String key, String json)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            var bodyStarted = new AtomicBoolean();
            var delegate = HttpRequest.BodyPublishers.ofString(json);
            var body = new HttpRequest.BodyPublisher() {
                @Override public long contentLength() { return delegate.contentLength(); }
                @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                    bodyStarted.set(true);
                    delegate.subscribe(subscriber);
                }
            };
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(body).build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException connectionFailure) {
                // Once the transport has requested the body, acceptance is uncertain even if reconnecting failed.
                if (bodyStarted.get()) throw connectionFailure;
                if (attempt != 0) throw new DomainException("LYRICS_PROVIDER_CONNECT_FAILED",
                    "暂时连不上歌词服务，创作描述已保留，可以稍后重试。");
                Thread.sleep(ThreadLocalRandom.current().nextInt(150, 351));
            }
        }
    }
}
