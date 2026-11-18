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
    private static final System.Logger LOG = System.getLogger(LyricsHttpTransport.class.getName());
    private LyricsHttpTransport() {}

    static HttpResponse<String> post(HttpClient client, URI endpoint, String key, String json)
            throws IOException, InterruptedException {
        return post(client, endpoint, key, json, Duration.ofSeconds(90));
    }

    static HttpResponse<String> post(HttpClient client, URI endpoint, String key, String json, Duration maximum)
            throws IOException, InterruptedException {
        if (maximum.isZero() || maximum.isNegative()) throw new java.net.http.HttpTimeoutException("Lyrics transport budget exhausted");
        long deadline = com.qingfeng.music.common.concurrent.LyricsRequestBudget.deadline(
            com.qingfeng.music.common.concurrent.AgentRequestScope.timeout(maximum));
        for (int attempt = 0; ; attempt++) {
            com.qingfeng.music.common.concurrent.AgentRequestScope.check();
            if (System.nanoTime() >= deadline) throw new java.net.http.HttpTimeoutException("Lyrics transport budget exhausted");
            var bodyStarted = new AtomicBoolean();
            var delegate = HttpRequest.BodyPublishers.ofString(json);
            var body = new HttpRequest.BodyPublisher() {
                @Override public long contentLength() { return delegate.contentLength(); }
                @Override public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                    bodyStarted.set(true);
                    delegate.subscribe(subscriber);
                }
            };
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(body).build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException | HttpConnectTimeoutException | UnknownHostException connectionFailure) {
                logFailure(endpoint, attempt, bodyStarted.get(), connectionFailure);
                // Once the transport has requested the body, acceptance is uncertain even if reconnecting failed.
                if (bodyStarted.get()) throw connectionFailure;
                if (attempt != 0) throw new DomainException("LYRICS_PROVIDER_CONNECT_FAILED",
                    "暂时连不上歌词服务，创作描述已保留，可以稍后重试。");
                Thread.sleep(ThreadLocalRandom.current().nextInt(150, 351));
            } catch (IOException transportFailure) {
                logFailure(endpoint, attempt, bodyStarted.get(), transportFailure);
                throw transportFailure;
            }
        }
    }

    private static void logFailure(URI endpoint, int attempt, boolean bodyStarted, IOException failure) {
        Throwable cause = failure;
        // Log exception types only: messages and stack traces can contain keys, URLs or user material.
        for (int depth = 0; depth < 8 && cause.getCause() != null && cause.getCause() != cause; depth++)
            cause = cause.getCause();
        LOG.log(System.Logger.Level.WARNING,
            "Lyrics transport failure: host={0}, attempt={1}, bodyStarted={2}, type={3}, causeType={4}",
            endpoint.getHost(), attempt + 1, bodyStarted, failure.getClass().getSimpleName(), cause.getClass().getSimpleName());
    }
}
