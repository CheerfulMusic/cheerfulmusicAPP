package com.qingfeng.music.infrastructure.provider;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class TextModelTransport {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private TextModelTransport() {}

    static String post(HttpClient client, URI endpoint, String key, String provider, String body, long deadline)
            throws IOException, InterruptedException {
        ProviderHttp.remaining(deadline);
        var tokens = AiModelUsage.before(provider, endpoint, body);
        var sent = new AtomicBoolean();
        var status = new AtomicInteger();
        var publisher = HttpRequest.BodyPublishers.ofString(body);
        var tracked = HttpRequest.BodyPublishers.fromPublisher(subscriber -> {
            // Prevent an implicit transport retry from publishing the Ark POST body again.
            if (sent.compareAndSet(false, true) || !provider.equals("ark")) publisher.subscribe(subscriber);
            else HttpRequest.BodyPublishers.ofInputStream(() -> null).subscribe(subscriber);
        }, publisher.contentLength());
        var request = HttpRequest.newBuilder(endpoint).timeout(ProviderHttp.remaining(deadline))
            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
            .POST(tracked).build();
        HttpResponse<String> response;
        try {
            response = ProviderHttp.read(client, request, deadline, info -> {
                status.set(info.statusCode());
                return boundedBody();
            });
        } catch (IOException error) {
            // A rejection already received in the headers takes precedence over a stalled error body.
            checkStatus(status.get());
            if (!sent.get() && (error instanceof ConnectException || error instanceof HttpConnectTimeoutException
                    || error instanceof UnknownHostException)) throw new NotSent();
            if (error instanceof HttpTimeoutException && !(error instanceof HttpConnectTimeoutException) && sent.get())
                throw new SentTimeout();
            throw error;
        }
        checkStatus(response.statusCode());
        AiModelUsage.reported(tokens, response.body());
        return response.body();
    }

    private static void checkStatus(int status) throws IOException {
        if (status == 408 || status == 504) throw new SentTimeout();
        if (status != 0 && status / 100 != 2) throw new HttpFailure(status);
    }

    static final class SentTimeout extends HttpTimeoutException {
        SentTimeout() { super("Text model response timed out"); }
    }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("Text model rejected request"); this.status = status; }
    }

    static final class NotSent extends IOException {
        NotSent() { super("Text model connection failed before submission"); }
    }

    private static HttpResponse.BodySubscriber<String> boundedBody() {
        return new HttpResponse.BodySubscriber<>() {
            private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            private Flow.Subscription subscription;
            private int received;
            private boolean finished;

            @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
            @Override public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                delegate.onSubscribe(value);
            }
            @Override public void onNext(List<ByteBuffer> buffers) {
                if (finished) return;
                long bytes = 0;
                for (var buffer : buffers) bytes += buffer.remaining();
                if (bytes > MAX_RESPONSE_BYTES - received) {
                    finished = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Text model response exceeds byte limit"));
                    return;
                }
                received += (int) bytes;
                delegate.onNext(buffers);
            }
            @Override public void onError(Throwable error) {
                if (!finished) { finished = true; delegate.onError(error); }
            }
            @Override public void onComplete() {
                if (!finished) { finished = true; delegate.onComplete(); }
            }
        };
    }
}
