package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.cover.application.ImageEditProvider;
import com.qingfeng.music.cover.application.ImageResultDownloader;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.lyrics.application.PhotoLyricsWriter;
import com.qingfeng.music.work.api.WorkApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.url=jdbc:h2:mem:ai_capacity_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    // The test profile runs the H2 financial bridge at V44; extensions here are isolated fixtures.
    "spring.sql.init.schema-locations=classpath:db/test/audio_version_annotations.sql,"
        + "classpath:db/migration/V46__agent_chat_allowance.sql,classpath:db/migration/V53__agent_admission_version.sql,"
        + "classpath:db/migration/V54__song_share_statistics.sql,classpath:db/migration/V57__ai_capacity_queue.sql",
    "qingfeng.ai.capacity.enabled=true",
    "qingfeng.ai.capacity.worker-lanes=lyrics,cover,agent",
    "qingfeng.ai.capacity.cover.callback-base=https://example.test",
    "qingfeng.ai.capacity.cover.callback-secret=offline-test-only-no-external-provider",
    "qingfeng.ai.capacity.photo-directory=./target/test-ai-capacity-inputs",
    "qingfeng.agent.chat.enabled=true",
    "qingfeng.lyrics.enabled=true",
    "qingfeng.credits.enabled=true"
})
@Import(AiCapacityHttpTest.Offline.class)
@ActiveProfiles("test")
class AiCapacityHttpTest {
    @LocalServerPort int port;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired CoverService covers;
    @Autowired CreditsLedger credits;
    @Autowired Stubs stubs;
    @Autowired JdbcAiWorkQueue queue;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private record Creator(String id, String work, String cookie, String csrf) {}

    @Test void asyncTextPhotoCoverAndChatUseTheNormalHttpEntryPoints() throws Exception {
        int priorTexts = stubs.textCalls.get(), priorChats = stubs.chatCalls.get();
        Creator writer = creator(), photographer = creator(), artist = creator(), chatter = creator();
        String textKey = key(), photoKey = key(), coverKey = key(), messageKey = key();
        String textRoute = lyrics(writer) + "/generate";
        String photoRoute = lyrics(photographer) + "/generate-photo";
        String coverRoute = "/api/app/works/" + artist.work() + "/covers";
        String source = covers.upload(artist.id(), artist.work(), new ByteArrayInputStream(image("png"))).id();
        String conversation = send(chatter, "/api/app/agent/conversations/current", null).path("id").asText();
        String messages = "/api/app/agent/conversations/" + conversation + "/messages";

        var textInput = Map.of("workVersion", 0, "requestKey", textKey, "language", "zh-CN");
        var agentInput = Map.of("requestKey", messageKey, "text", "你为什么认为音乐很重要？", "locale", "zh-CN", "paidConfirmed", true);
        assertThat(raw(writer, textRoute, textInput, true).statusCode()).isEqualTo(202);
        assertThat(rawPhoto(photographer, photoRoute, photoKey).statusCode()).isEqualTo(202);
        assertThat(raw(artist, coverRoute + "/edits",
            Map.of("sourceId", source, "requestKey", coverKey, "prompt", "换成蓝色背景"), false).statusCode()).isEqualTo(200);
        var agent = raw(chatter, messages, agentInput, true);
        assertThat(agent.statusCode()).isIn(200, 202);
        assertThat(raw(writer, textRoute, textInput, true).statusCode()).isEqualTo(202);
        assertThat(raw(writer, messages + "/" + messageKey + "/result", null, false).statusCode()).isEqualTo(404);

        eventually(() -> send(writer, lyrics(writer) + "/requests/" + textKey, null).path("status").asText().equals("SUCCEEDED"));
        assertThat(raw(writer, textRoute, textInput, false).statusCode()).isEqualTo(200);
        eventually(() -> send(photographer, lyrics(photographer) + "/requests/" + photoKey, null).path("status").asText().equals("SUCCEEDED"));
        eventually(() -> queue.find(Lane.COVER, artist.id(), artist.work(), coverKey).orElseThrow().state()
            == JdbcAiWorkQueue.State.WAITING);
        URI notice = stubs.notification.get();
        assertThat(notice).isNotNull();
        var invalidNotice = HttpRequest.newBuilder(loopback(notice.getPath() + "?signature=invalid")).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(invalidNotice, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        var validNotice = HttpRequest.newBuilder(loopback(notice.getRawPath() + "?" + notice.getRawQuery()))
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(validNotice, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        eventually(() -> queue.find(Lane.COVER, artist.id(), artist.work(), coverKey).orElseThrow().state()
            == JdbcAiWorkQueue.State.SUCCEEDED);
        eventually(() -> raw(chatter, messages + "/" + messageKey + "/result", null, false).statusCode() == 200);
        var replay = raw(chatter, messages, agentInput, false);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.body()).contains("离线助手模型回答");
        assertThat(stubs.textCalls.get()).isEqualTo(priorTexts + 1);
        assertThat(stubs.photoCalls.get()).isEqualTo(1);
        assertThat(stubs.coverCalls.get()).isEqualTo(1);
        assertThat(stubs.chatCalls.get()).isEqualTo(priorChats + 1);
    }

    @Test void agentWaitingForLyricsGivesBackItsExecutionPermit() throws Exception {
        Creator chatter = creator();
        String conversation = send(chatter, "/api/app/agent/conversations/current", null).path("id").asText();
        String key = key(), messages = "/api/app/agent/conversations/" + conversation + "/messages";
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        stubs.delay.set(new Stubs.Delay(started, release));
        try {
            assertThat(raw(chatter, messages,
                Map.of("requestKey", key, "text", "先写歌词，主题是冬天的河流", "locale", "zh-CN", "paidConfirmed", true), true)
                .statusCode()).isEqualTo(202);
            assertThat(started.await(8, TimeUnit.SECONDS)).isTrue();
            eventually(() -> queue.find(Lane.AGENT, chatter.id(), conversation, key).orElseThrow().state()
                == JdbcAiWorkQueue.State.SUSPENDED);
            assertThat(queue.counts(Lane.AGENT).running()).isZero();
            assertThat(queue.counts(Lane.LYRICS).running()).isEqualTo(1);
        } finally {
            release.countDown();
            stubs.delay.set(null);
        }
        eventually(() -> raw(chatter, messages + "/" + key + "/result", null, false).statusCode() == 200);
        assertThat(send(chatter, messages + "/" + key + "/result", null).path("action").asText()).isEqualTo("generate_lyrics");
    }

    @Autowired com.qingfeng.music.lyrics.api.LyricsApi lyricRevisions;

    @Test void queuedStudioTurnsKeepTheirActionVersionAndQuoteAcrossPolling() throws Exception {
        var user=creator();
        var revision=lyricRevisions.save(user.id(),user.work(),0,"合成歌词","[副歌]\n离线的合成样本");
        String conversation=send(user,"/api/app/agent/conversations/current",null).path("id").asText();
        String path="/api/app/agent/conversations/"+conversation+"/messages";
        send(user,path,Map.of("requestKey",key(),"text","打开作品 "+user.work()+" 的歌词版本 "+revision.id()));
        var target=Map.of("workId",user.work(),"revisionId",revision.id(),"creationAction","POLISH_LYRICS");
        String request=key();
        var input=Map.of("requestKey",request,"text","加强副歌","displayedContext",target);
        assertThat(raw(user,path,input,true).statusCode()).isIn(200,202);
        eventually(()->raw(user,path+"/"+request+"/result",null,false).statusCode()==200);
        var messages=send(user,path+"/"+request+"/result",null).path("messages");
        var quote=json.readTree(messages.path(messages.size()-1).path("contentJson").asText()).path("creationQuote");
        assertThat(quote.path("action").asText()).isEqualTo("generate_lyrics");
        assertThat(quote.path("revisionId").asText()).isEqualTo(revision.id());
        assertThat(lyricRevisions.list(user.id(),user.work())).hasSize(1);
        String id=quote.path("id").asText();
        var confirm=Map.of("requestKey",id,"text","#approve:"+id);
        var oldClient=Map.of("requestKey",key(),"text","#approve:"+id);
        assertThat(raw(user,path,oldClient,true).statusCode()).isEqualTo(200);
        assertThat(raw(user,path,confirm,true).statusCode()).isIn(200,202);
        eventually(()->raw(user,path+"/"+id+"/result",null,false).statusCode()==200);
        assertThat(send(user,path,confirm).path("action").asText()).isEqualTo("generate_lyrics");
        assertThat(lyricRevisions.list(user.id(),user.work())).hasSize(2);
        assertThat(credits.balance(user.id())).isEqualTo(100);
    }

    private Creator creator() {
        var session = identity.create();
        String owner = session.creatorId();
        credits.credit(owner, 100, "ai-capacity-test-" + owner);
        var work = works.create(owner, new WorkApi.Draft("朋友", "旅行", "旅行故事", "旅途记忆", "民谣", "容量验证"));
        String csrf = key();
        return new Creator(owner, work.id(), "QF_SESSION=" + session.token() + "; XSRF-TOKEN=" + csrf, csrf);
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String lyrics(Creator owner) { return "/api/app/works/" + owner.work() + "/lyrics"; }
    private URI loopback(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private HttpRequest.Builder request(Creator owner, String path) {
        return HttpRequest.newBuilder(loopback(path)).timeout(Duration.ofSeconds(15)).header("Cookie", owner.cookie());
    }

    private JsonNode send(Creator owner, String path, Object body) throws Exception {
        var response = raw(owner, path, body, false);
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return json.readTree(response.body());
    }

    private HttpResponse<String> raw(Creator owner, String path, Object body, boolean async) throws Exception {
        var request = request(owner, path);
        if (async) request.header("Prefer", "respond-async");
        if (body != null) {
            request.header("X-XSRF-TOKEN", owner.csrf()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> rawPhoto(Creator owner, String path, String key) throws Exception {
        var body = new ByteArrayOutputStream();
        var fields = Map.of("workVersion", "0", "requestKey", key, "language", "zh-CN");
        for (var field : fields.entrySet()) {
            body.write(("--offline\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue()
                + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--offline\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
        body.write(image("jpg"));
        body.write("\r\n--offline--\r\n".getBytes(StandardCharsets.UTF_8));
        return http.send(request(owner, path).header("Prefer", "respond-async").header("X-XSRF-TOKEN", owner.csrf())
            .header("Content-Type", "multipart/form-data; boundary=offline").POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void eventually(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(14).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.ready()) return;
            Thread.sleep(150);
        }
        assertThat(condition.ready()).as("Durable task reached the requested phase").isTrue();
    }

    @FunctionalInterface private interface CheckedCondition { boolean ready() throws Exception; }

    private static byte[] image(String format) {
        try {
            var contents = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB), format, contents);
            return contents.toByteArray();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }

    static final class Stubs {
        record Delay(CountDownLatch started, CountDownLatch release) {}
        final AtomicInteger textCalls = new AtomicInteger(), photoCalls = new AtomicInteger();
        final AtomicInteger coverCalls = new AtomicInteger(), chatCalls = new AtomicInteger();
        final AtomicReference<Delay> delay = new AtomicReference<>();
        final AtomicReference<URI> notification = new AtomicReference<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Offline {
        @Bean Stubs stubs() { return new Stubs(); }

        @Bean @Primary @Qualifier("customLyricsWriter") LyricsWriter capacityWriter(Stubs stubs) {
            return new LyricsWriter() {
                public boolean isConfigured() { return true; }
                public String source() { return "TEST"; }
                public Draft write(WorkApi.Work work, String language) {
                    stubs.textCalls.incrementAndGet();
                    var delay = stubs.delay.get();
                    if (delay != null) {
                        delay.started().countDown();
                        try {
                            if (!delay.release().await(20, TimeUnit.SECONDS)) throw new AssertionError("Lyric fixture timed out");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }
                    return new Draft("河流", "[Verse]\n冬天的河流流向远方\n[Chorus]\n带着温柔的光");
                }
            };
        }

        @Bean @Primary PhotoLyricsWriter capacityPhotoWriter(Stubs stubs) {
            return (work, language, photos, direction) -> {
                assertThat(photos).hasSize(1);
                stubs.photoCalls.incrementAndGet();
                return new LyricsWriter.Draft("照片", "[Verse]\n照片里留下一束光\n[Pre-Chorus]\n清风穿过了窗\n[Chorus]\n让欢乐继续流淌\n[Interlude]\n[Pre-Chorus]\n记忆轻轻回响\n[Chorus]\n让欢乐继续流淌\n[Bridge]\n我们都在路上\n[Chorus]\n让欢乐继续流淌");
            };
        }

        @Bean @Primary AgentChatModel capacityModel(Stubs stubs) {
            return context -> {
                stubs.chatCalls.incrementAndGet();
                if (context.text().startsWith("先写歌词"))
                    return new AgentChatModel.Plan("generate_lyrics", Map.of("text", context.text(), "mode", "new"), "");
                return new AgentChatModel.Plan("chat", Map.of(), "离线助手模型回答");
            };
        }

        @Bean @Primary ImageEditProvider capacityImageProvider(Stubs stubs) {
            return new ImageEditProvider() {
                public boolean configured() { return true; }
                public String model() { return "test-cover"; }
                public String submit(byte[] jpeg, String prompt, String model) { throw new AssertionError("Expected callback URL"); }
                @Override public String submit(byte[] jpeg, String prompt, String model, URI notification) {
                    stubs.notification.set(notification);
                    stubs.coverCalls.incrementAndGet();
                    return "offline-task";
                }
                public Result query(String task, String model) { return new Result("SAVING", "https://example.test/cover.png"); }
            };
        }

        @Bean @Primary ImageResultDownloader capacityImageDownloader() {
            return url -> new ByteArrayInputStream(image("png"));
        }
    }
}
