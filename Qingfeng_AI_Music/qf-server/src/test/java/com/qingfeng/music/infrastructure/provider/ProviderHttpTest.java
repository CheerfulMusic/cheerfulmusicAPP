package com.qingfeng.music.infrastructure.provider;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderHttpTest {
    @Test void stalledBodyCannotHoldAnExecutionSlotIndefinitely() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var wroteHeaders = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        server.createContext("/mock", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 20);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                wroteHeaders.countDown();
                finish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mock"))
                .timeout(Duration.ofSeconds(5)).build();
            long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
            assertThatThrownBy(() -> ProviderHttp.read(HttpClient.newHttpClient(), request, deadline))
                .isInstanceOf(HttpTimeoutException.class);
            assertThat(wroteHeaders.getCount()).isZero();
        } finally {
            finish.countDown();
            server.stop(0);
        }
    }
}
