package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP, persistence and business services; only external model providers are offline doubles. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:agent_chat_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qingfeng.agent.chat.enabled=true", "qingfeng.lyrics.enabled=true", "qingfeng.generation.enabled=true",
        "qingfeng.generation.scheduler-enabled=false", "qingfeng.cover.ai.scheduler-enabled=false"})
@ActiveProfiles("test")
@Import(AgentChatFlowIntegrationTest.Offline.class)
class AgentChatFlowIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Offline {
        @Bean @Primary AgentChatModel conversationModel() {
            return context -> {
                String text = context.text();
                if (text.equals("你好")) return new AgentChatModel.Plan("chat", Map.of(), "你好，今天想聊些什么？");
                if (text.contains("先写歌词")) return new AgentChatModel.Plan("generate_lyrics", Map.of("text", text, "mode", "new", "language", "yue"), "我来写歌词。");
                if (text.contains("副歌")) return new AgentChatModel.Plan("generate_lyrics", Map.of("text", text, "mode", "rewrite", "revisionId", context.song().revisionId()), "我来改副歌。");
                if (text.contains("女声")) return new AgentChatModel.Plan("set_style", Map.of("vocal", "FEMALE", "bpm", 90), "调整演唱设置。");
                if (text.contains("生成音乐")) return new AgentChatModel.Plan("generate_song", Map.of("text", text, "mode", "regenerate", "revisionId", context.song().revisionId()), "开始生成。");
                return new AgentChatModel.Plan("chat", Map.of(), "我们继续聊。");
            };
        }
        @Bean @Primary LyricsWriter offlineLyrics() {
            return new LyricsWriter() {
                public boolean isConfigured() { return true; }
                public String source() { return "TEST"; }
                public Draft write(WorkApi.Work work, String language) { return new Draft("毕业歌", "[Verse]\n一起走过校园\n[Chorus]\n明天继续向前"); }
                public Draft write(WorkApi.Work work, String language, String instructions, String previous) {
                    return previous == null || previous.isBlank() ? write(work, language) : new Draft("毕业歌", previous.replace("明天继续向前", "愿你勇敢向前"));
                }
            };
        }
        @Bean @Primary MusicProvider noNetworkMusic() {
            return new MusicProvider() {
                public String name() { return "offline-agent-test"; }
                public boolean isConfigured() { return true; }
                public Submission submit(Request request, String key) { throw new AssertionError("No external generation is permitted in this test"); }
                public Result query(String id) { throw new AssertionError("No external polling is permitted in this test"); }
            };
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate db;
    @Autowired MediaService media;
    @Autowired com.qingfeng.music.settings.MusicSettings settings;
    @Autowired com.qingfeng.music.agent.api.AgentTurnStore turns;
    private final ObjectMapper json = new ObjectMapper();
    private record Browser(HttpClient http, String csrf, String creator, String conversation) {}
    private HttpResponse<String> request(HttpClient client, String method, String path, Object body, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }
    private Browser browser() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = ok(request(client,"GET","/api/public/csrf",null,null)).path("token").asText();
        String owner = ok(request(client,"POST","/api/public/session",Map.of(),csrf)).path("creatorId").asText();
        String conversation = ok(request(client,"GET","/api/app/agent/conversations/current",null,null)).path("id").asText();
        return new Browser(client, csrf, owner, conversation);
    }
    private JsonNode say(Browser browser, String text, String key) throws Exception {
        return ok(request(browser.http(),"POST","/api/app/agent/conversations/" + browser.conversation() + "/messages", Map.of("text",text,"requestKey",key),browser.csrf()));
    }
    private JsonNode say(Browser browser, String text) throws Exception { return say(browser,text,UUID.randomUUID().toString()); }
    private JsonNode state(Browser browser) throws Exception { return ok(request(browser.http(),"GET","/api/app/agent/conversations/"+browser.conversation()+"/state",null,null)); }

    @Test void conversationCreatesRevisesGeneratesAndSharesTheSameRealWork() throws Exception {
        Browser browser = browser();
        db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "a".repeat(64),browser.creator());
        JsonNode greeting = say(browser,"你好");
        assertTrue(greeting.path("songCard").isNull(),greeting.toString());
        assertTrue(greeting.path("messages").toString().contains("今天想聊些什么"));

        JsonNode first = say(browser,"写一首关于毕业的粤语歌，先写歌词，不生成音乐");
        String work = first.path("songCard").path("songId").asText();
        String firstRevision = first.path("songCard").path("revisionId").asText();
        assertFalse(work.isBlank(),first.toString());
        assertEquals("LYRICS_READY", first.path("songCard").path("status").asText());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));

        JsonNode revised = say(browser,"把副歌改得更有力量，其他不变");
        assertEquals(work,revised.path("songCard").path("songId").asText());
        assertNotEquals(firstRevision,revised.path("songCard").path("revisionId").asText());
        assertTrue(revised.path("songCard").path("lyrics").asText().contains("一起走过校园"));
        assertTrue(revised.path("songCard").path("lyrics").asText().contains("愿你勇敢向前"));
        say(browser,"改成女声，90 BPM，其他不变");
        String musicKey=UUID.randomUUID().toString();
        JsonNode queued=say(browser,"用当前歌词生成音乐",musicKey);
        String task=queued.path("taskId").asText();
        assertFalse(task.isBlank(),queued.toString());
        assertEquals("QUEUED",queued.path("songCard").path("status").asText());
        assertEquals("FEMALE",db.queryForObject("SELECT vocal_gender FROM generation_task WHERE id=?",String.class,task));
        var frozen = settings.frozen(work, task).orElseThrow();
        assertTrue(frozen.style().contains("90"), frozen.style());
        assertTrue(frozen.style().contains("Cantonese"), frozen.style());
        assertEquals(task,say(browser,"用当前歌词生成音乐",musicKey).path("taskId").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));
        assertEquals(task,state(browser).path("taskId").asText());

        var wav=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
        String asset=media.store(task,work,new ByteArrayInputStream(wav.array()));
        db.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?",asset,task);
        JsonNode ready=state(browser);
        assertEquals(asset,ready.path("songCard").path("assetId").asText());
        assertEquals("/api/app/works/"+work+"/audio/"+asset,ready.path("songCard").path("audioUrl").asText());
        assertTrue(say(browser,"给当前作品点赞").path("liked").asBoolean());
        assertTrue(state(browser).path("liked").asBoolean());
        assertFalse(say(browser,"取消点赞当前作品").path("liked").asBoolean());
        JsonNode shared=say(browser,"分享当前作品");
        assertTrue(shared.path("shareUrl").asText().startsWith("/gift/"),shared.toString());
        assertEquals(shared.path("shareUrl").asText(),state(browser).path("shareUrl").asText());
        assertTrue(say(browser,"撤销分享").path("shareUrl").isNull());

        Browser other=browser();
        assertEquals(404,request(other.http(),"GET","/api/app/agent/conversations/"+browser.conversation()+"/state",null,null).statusCode());
        assertEquals(404,request(other.http(),"POST","/api/app/agent/conversations/"+browser.conversation()+"/messages",Map.of("text","分享当前作品","requestKey",UUID.randomUUID().toString()),other.csrf()).statusCode());
        ok(request(browser.http(),"POST","/api/app/agent/conversations/"+browser.conversation()+"/reset",Map.of(),browser.csrf()));
        assertEquals(0,state(browser).path("messages").size());
        // Reset hides chat history without removing the durable request receipt.
        say(browser,"用当前歌词生成音乐",musicKey);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));
        assertTrue(state(browser).path("songCard").isNull());
    }

    @Test void anotherTabCannotResetAnActivelyExecutingMessage() throws Exception {
        Browser browser=browser();
        turns.claim(browser.creator(),browser.conversation(),UUID.randomUUID().toString(),"用当前歌词生成音乐");
        assertEquals(409,request(browser.http(),"POST","/api/app/agent/conversations/"+browser.conversation()+"/reset",Map.of(),browser.csrf()).statusCode());
        assertEquals("RUNNING",state(browser).path("messages").get(0).path("state").asText());
    }
}
