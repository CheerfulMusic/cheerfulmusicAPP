package com.qingfeng.music.server;

import static org.junit.jupiter.api.Assertions.*;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.creation.QuickCreationRepository;
import com.qingfeng.music.creation.QuickCreationService;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.server.config.LocalQuickCreation;
import com.qingfeng.music.work.api.WorkApi;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:local_quick;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "qingfeng.lyrics.enabled=true", "qingfeng.quick.accept-enabled=true",
    "qingfeng.quick.local-execution-enabled=true", "qingfeng.generation.enabled=true",
    "qingfeng.quick.scheduler-enabled=false", "qingfeng.generation.scheduler-enabled=false"
})
@ActiveProfiles("test")
@Import(LocalQuickCreationIntegrationTest.Offline.class)
class LocalQuickCreationIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class Offline {
        @Bean @Primary BlockingWriter writer() { return new BlockingWriter(); }
        @Bean @Primary QuickCreationIntegrationTest.FakeGeneration music() {
            return new QuickCreationIntegrationTest.FakeGeneration();
        }
        @Bean @Primary MusicProvider provider() {
            var provider = org.mockito.Mockito.mock(MusicProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
            org.mockito.Mockito.when(provider.isConfigured()).thenReturn(true);
            return provider;
        }
    }

    static class BlockingWriter extends QuickCreationIntegrationTest.FakeWriter {
        CountDownLatch entered;
        CountDownLatch proceed;
        @Override public LyricsWriter.Draft write(WorkApi.Work work, String language) {
            entered.countDown();
            try {
                if (!proceed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return super.write(work, language);
        }
    }

    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired QuickCreationRepository repository;
    @Autowired BlockingWriter writer;
    @Autowired QuickCreationIntegrationTest.FakeGeneration music;
    @Autowired JdbcTemplate jdbc;
    @Autowired LocalQuickCreation local;
    @Autowired QuickCreationService service;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    String owner;
    String session;
    WorkApi.Work work;

    @BeforeAll static void schema(@Autowired JdbcTemplate jdbc) throws Exception {
        QuickCreationIntegrationTest.ensureVideoProjectionFixture(jdbc);
    }

    @BeforeEach void setup() {
        writer.entered = new CountDownLatch(1);
        writer.proceed = new CountDownLatch(1);
        writer.failureCode = null;
        var browser = identity.create();
        owner = browser.creatorId();
        session = browser.token();
        work = works.create(owner, new WorkApi.Draft("朋友", "旅行", "夏日海风", "", "民谣", ""));
    }

    @AfterEach void close() {
        writer.proceed.countDown();
    }

    @Test void localRequestReservesBeforeGlobalWorkerAndReplayDoesNotPurchaseAgain() throws Exception {
        int songs = music.purchases.get();
        String key = UUID.randomUUID().toString();
        var http = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/app/works/" + work.id() + "/quick-creations"))
                .header("Content-Type", "application/json")
                .header("Cookie", "QF_SESSION=" + session + "; XSRF-TOKEN=local-quick-fixture")
                .header("X-XSRF-TOKEN", "local-quick-fixture")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                        "workVersion", work.version(), "requestKey", key, "language", "zh-CN"))))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        String id = json.readTree(response.body()).get("id").asText();
        assertTrue(writer.entered.await(5, TimeUnit.SECONDS));
        assertTrue(repository.claim("other-worker").isEmpty());
        assertFalse(service.runClaimed(work.id(), id, "wrong-token"));
        assertEquals(id, local.start(owner, work.id(), work.version(), key, "zh-CN").id());
        writer.proceed.countDown();
        awaitStatus(id, "MUSIC_QUEUED");
        assertEquals(songs + 1, music.purchases.get());
        assertNotNull(service.get(owner, work.id(), id).taskId());
        assertNull(repository.find(work.id(), id).orElseThrow().leaseToken());
    }

    @Test void retryUsesLocalWriterAndRefusesAnotherOwner() throws Exception {
        writer.failureCode = "LYRICS_PROVIDER_CONNECT_FAILED";
        writer.proceed.countDown();
        var flow = local.start(owner, work.id(), work.version(), UUID.randomUUID().toString(), "zh-CN");
        awaitStatus(flow.id(), "FAILED");
        String other = identity.create().creatorId();
        assertThrows(DomainException.class, () -> local.retry(other, work.id(), flow.id()));
        writer.failureCode = null;
        int songs = music.purchases.get();
        assertEquals(flow.id(), local.retry(owner, work.id(), flow.id()).id());
        awaitStatus(flow.id(), "MUSIC_QUEUED");
        assertEquals(songs + 1, music.purchases.get());
    }

    @Test void creatingAnUnrelatedQueuedTaskDoesNotMakeTheLocalRunnerScanIt() throws Exception {
        String other = identity.create().creatorId();
        var unrelated = works.create(other, new WorkApi.Draft("朋友", "旅行", "另一个故事", "", "民谣", ""));
        var queued = service.start(other, unrelated.id(), unrelated.version(), UUID.randomUUID().toString(), "zh-CN");
        writer.proceed.countDown();
        var flow = local.start(owner, work.id(), work.version(), UUID.randomUUID().toString(), "zh-CN");
        awaitStatus(flow.id(), "MUSIC_QUEUED");
        assertEquals("QUEUED", repository.find(unrelated.id(), queued.id()).orElseThrow().status());
        jdbc.update("UPDATE quick_creation SET status='FAILED' WHERE id=?", queued.id());
    }

    private void awaitStatus(String id, String status) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            var entry = repository.find(work.id(), id).orElseThrow();
            if (status.equals(entry.status()) && entry.leaseToken() == null) return;
            Thread.sleep(20);
        }
        fail("Expected completed local transition to " + status);
    }
}
