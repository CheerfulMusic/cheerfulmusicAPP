package com.qingfeng.music.server.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ModelFallbackTestServer implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String LYRICS = "[Verse]\n橘子放进门口的小碗\n[Chorus]\n也给自己留一瓣";
    static final Map<String, Object> SONG = Map.of("title", "门口的小碗", "lyrics", LYRICS, "plan", Map.of(
        "requirements", "", "suggestedArrangement", "Acoustic guitar", "negativeTags", "",
        "summaryZh", "温暖民谣", "summaryEn", "Warm folk", "vocalGender", "AUTO"));
    static final String QWEN_LYRICS = "/qwen/chat/completions";
    static final String QWEN_AGENT = "/agent/chat/completions";
    static final String ARK = "/ark/chat/completions";
    static final String GPT = "/gpt";
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, ConcurrentLinkedQueue<Reply>> queued = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    record Call(String path, String authorization, JsonNode body) {}
    private record Reply(int status, byte[] body, Delayed delay) {}
    static final class Delayed implements AutoCloseable {
        final CountDownLatch headers = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public void close() { release.countDown(); }
    }

    ModelFallbackTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", this::respond);
        server.start();
    }

    URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    List<Call> calls(String path) { return calls.stream().filter(call -> call.path().equals(path)).toList(); }
    void reset() { calls.clear(); queued.clear(); }
    void reply(String path, int status, Object body) { enqueue(path, status, body, null); }
    Delayed stall(String path, int status, Object body) {
        var delay = new Delayed();
        enqueue(path, status, body, delay);
        return delay;
    }

    private void enqueue(String path, int status, Object body, Delayed delay) {
        queued.computeIfAbsent(path, ignored -> new ConcurrentLinkedQueue<>())
            .add(new Reply(status, JSON.writeValueAsBytes(body), delay));
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        var queue = queued.get(path);
        var reply = queue == null ? null : queue.poll();
        try (exchange) {
            calls.add(new Call(path, exchange.getRequestHeaders().getFirst("Authorization"),
                JSON.readTree(exchange.getRequestBody().readAllBytes())));
            if (reply == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            if (reply.delay() != null) {
                exchange.getResponseBody().write(reply.body(), 0, 1);
                exchange.getResponseBody().flush();
                reply.delay().headers.countDown();
                if (!reply.delay().release.await(8, TimeUnit.SECONDS)) return;
            }
            int offset = reply.delay() == null ? 0 : 1;
            exchange.getResponseBody().write(reply.body(), offset, reply.body().length - offset);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            if (reply == null || reply.delay() == null) throw disconnected;
        } finally {
            if (reply != null && reply.delay() != null) reply.delay().finished.countDown();
        }
    }

    static Map<String, Object> gpt(Object content) {
        return Map.of("status", "completed", "output", List.of(Map.of("type", "message", "role", "assistant",
            "status", "completed", "content", List.of(Map.of("type", "output_text", "text", text(content))))));
    }

    static Map<String, Object> chat(Object content) {
        return message("stop", Map.of("content", text(content)));
    }

    static Map<String, Object> function(String name, Map<String, Object> args) {
        return message("tool_calls", Map.of("tool_calls", List.of(Map.of("id", "call_mock", "type", "function",
            "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args))))));
    }

    private static Map<String, Object> message(String reason, Map<String, Object> message) {
        return Map.of("choices", List.of(Map.of("finish_reason", reason, "message", message)));
    }

    private static String text(Object value) {
        return value instanceof String str ? str : JSON.writeValueAsString(value);
    }

    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
