package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.common.concurrent.AgentRequestScope;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProviderHttp {
    private ProviderHttp() {}

    /** Bound the complete response, including a body that stops after the headers arrive. */
    static HttpResponse<String> read(HttpClient client, HttpRequest request, long deadline)
            throws IOException, InterruptedException {
        return read(client, request, deadline, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> read(HttpClient client, HttpRequest request, long deadline,
                                    HttpResponse.BodyHandler<String> handler)
            throws IOException, InterruptedException {
        remaining(deadline);
        var response = client.sendAsync(request, handler);
        try {
            while (true) {
                long wait = Math.min(remaining(deadline).toNanos(), TimeUnit.MILLISECONDS.toNanos(100));
                try {
                    var result = response.get(wait, TimeUnit.NANOSECONDS);
                    remaining(deadline);
                    return result;
                } catch (TimeoutException pending) {
                    // Recheck cooperative cancellation while waiting for body delivery.
                }
            }
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            while (cause instanceof CompletionException wrapped && wrapped.getCause() != null) cause = wrapped.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Provider transport failed", cause);
        } finally {
            // Cancel the original transport future so a late response cannot reach the caller.
            if (!response.isDone()) response.cancel(true);
        }
    }

    static Duration remaining(long deadline) throws HttpTimeoutException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new HttpTimeoutException("Provider response deadline exceeded");
        return AgentRequestScope.timeout(Duration.ofNanos(remaining));
    }
}
