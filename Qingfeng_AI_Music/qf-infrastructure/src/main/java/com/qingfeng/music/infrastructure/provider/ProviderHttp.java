package com.qingfeng.music.infrastructure.provider;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProviderHttp {
    private ProviderHttp() {}

    /** Bound the complete response, including a body that stops after the headers arrive. */
    static HttpResponse<String> read(HttpClient client, HttpRequest request, long deadline)
            throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new HttpTimeoutException("Provider response deadline exceeded");
        var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return response.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            response.cancel(true);
            throw new HttpTimeoutException("Provider response deadline exceeded");
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            while (cause instanceof CompletionException wrapped && wrapped.getCause() != null) cause = wrapped.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Provider transport failed", cause);
        }
    }
}
