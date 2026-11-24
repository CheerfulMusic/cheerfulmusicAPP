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
@org.springframework.test.context.jdbc.Sql(scripts={"classpath:db/migration/V46__agent_chat_allowance.sql",
        "classpath:db/migration/V47__music_video_projects.sql","classpath:db/migration/V53__agent_admission_version.sql","classpath:db/migration/V54__song_share_statistics.sql"}, executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:agent_chat_flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "qingfeng.agent.chat.enabled=true", "qingfeng.lyrics.enabled=true", "qingfeng.generation.enabled=true",
        "qingfeng.generation.scheduler-enabled=false", "qingfeng.cover.ai.scheduler-enabled=false",
        "qingfeng.agent.admission.global-rpm=600", "qingfeng.agent.admission.user-rpm=60", "qingfeng.agent.admission.user-daily-tokens=10000000"})
@ActiveProfiles("test")
@Import(AgentChatFlowIntegrationTest.Offline.class)
class AgentChatFlowIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Offline {
        @Bean @Primary com.qingfeng.music.server.mv.AudioDuration previewDuration() {
            return (owner,work,asset) -> 61_000L;
        }
        @Bean @Primary AgentChatModel conversationModel() {
            var unavailable = new com.qingfeng.music.infrastructure.provider.ResilientAgentChatModel(context -> {
                throw new com.qingfeng.music.common.exception.DomainException("AGENT_CHAT_UNAVAILABLE", "offline planner");
            }, 1, java.time.Duration.ofSeconds(10));
            return context -> {
                String text = context.text();
                if (text.startsWith("帮我设计当前歌曲的 MV")) return new AgentChatModel.Plan("generate_song", Map.of("mode","regenerate"), "");
                if (text.equals("歌曲呢")) return new AgentChatModel.Plan("chat",Map.of(),"歌曲已提交。");
                if (text.equals("你先打磨一版歌词")) return unavailable.respond(context);
                if (text.equals("方向定好了")) return new AgentChatModel.Plan("chat",
                        Map.of("choices",List.of("generate_lyrics","create_work","discuss")), "中文、空灵、梦想与星光");
                if (text.equals("查看我的作品")) return new AgentChatModel.Plan("list_works",Map.of(),"查看作品。");
                if (text.equals("哪首歌有“海风替我说晚安”这句歌词？")) return new AgentChatModel.Plan("list_works",Map.of("query","海风替我说晚安"),"");
                if (text.equals("选择第二首") || text.equals("打开第二首")) return new AgentChatModel.Plan("select_work",Map.of("index",2),"选择第二首。");
                if (text.equals("把当前作品改名为夏天")) return new AgentChatModel.Plan("rename_work",Map.of("title","夏天"),"修改标题。");
                if (text.equals("删除当前作品")) return new AgentChatModel.Plan("delete_work",Map.of(),"删除选定作品。");
                if (text.equals("查看我的积分")) return new AgentChatModel.Plan("get_credits",Map.of(),"查询积分。");
                if (text.equals("用当前歌词生成音乐然后分享")) return new AgentChatModel.Plan(null,Map.of(),"生成后分享。",List.of(
                    new AgentChatModel.Step("generate_song",Map.of("mode","regenerate")),new AgentChatModel.Step("share",Map.of())));
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
    @Autowired WorkApi works;
    @Autowired com.qingfeng.music.credits.CreditsLedger credits;
    @Autowired com.qingfeng.music.lyrics.api.LyricsApi lyrics;
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
    private JsonNode edit(Browser user,String text,String key,JsonNode displayed) throws Exception {
        var target=json.createObjectNode();
        target.set("workId",displayed.path("songId"));
        target.set("revisionId",displayed.path("revisionId"));
        return ok(request(user.http(),"POST","/api/app/agent/conversations/"+user.conversation()+"/messages",
            Map.of("text",text,"requestKey",key,"displayedContext",target),user.csrf()));
    }
    private JsonNode state(Browser browser) throws Exception { return ok(request(browser.http(),"GET","/api/app/agent/conversations/"+browser.conversation()+"/state",null,null)); }

    @Autowired com.qingfeng.music.server.agent.AgentAdmission admission;

    private HttpResponse<String> fromTab(Browser user,String text,String key,Object displayed) throws Exception {
        return request(user.http(),"POST","/api/app/agent/conversations/"+user.conversation()+"/messages",
            Map.of("text",text,"requestKey",key,"displayedContext",displayed),user.csrf());
    }

    private String lastMessageId(JsonNode snapshot){
        var rows=snapshot.path("messages");
        return rows.get(rows.size()-1).path("id").asText();
    }

    @Test void onlyTheDisplayedPromptCanBeConfirmedAndRejectingAnOldCardPreservesTheNewOne() throws Exception {
        var user=browser();
        var first=say(user,"[Verse]\n春日第一稿");
        var second=say(user,"[Verse]\n冬日第二稿");
        assertEquals("confirmation",lastText(second).path("kind").asText());
        var wrong=fromTab(user,"确认执行",UUID.randomUUID().toString(),Map.of("promptId",lastMessageId(first)));
        assertEquals(409,wrong.statusCode(),wrong.body());
        assertEquals("AGENT_PROMPT_CONFLICT",json.readTree(wrong.body()).path("code").asText());
        assertEquals(lastMessageId(second),lastMessageId(state(user)));
        var visible=Map.of("promptId",lastMessageId(second));
        assertEquals(409,fromTab(user,"确认删除",UUID.randomUUID().toString(),visible).statusCode());
        assertTrue(works.list(user.creator(),0).isEmpty());
        String key=UUID.randomUUID().toString();
        var saved=ok(fromTab(user,"确认执行",key,visible));
        assertEquals("[Verse]\n冬日第二稿",saved.path("songCard").path("lyrics").asText());
        ok(fromTab(user,"确认执行",key,visible));
        assertEquals(1,works.list(user.creator(),0).size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_request_admission WHERE creator_id=? AND state IN ('RESERVED','RUNNING')",Integer.class,user.creator()));
    }

    @Test void aRequestNeverDeliveredBeforeResetCannotRepopulateTheClearedConversation() throws Exception {
        var user=browser();
        long epoch=state(user).path("historyEpoch").asLong();
        ok(request(user.http(),"POST","/api/app/agent/conversations/"+user.conversation()+"/reset",Map.of(),user.csrf()));
        var rejected=fromTab(user,"列出我的作品。",UUID.randomUUID().toString(),Map.of("historyEpoch",epoch));
        assertEquals(409,rejected.statusCode());
        assertEquals("AGENT_HISTORY_CONFLICT",json.readTree(rejected.body()).path("code").asText());
        assertTrue(state(user).path("messages").isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM agent_request_admission WHERE creator_id=? AND state='RESERVED'",Integer.class,user.creator()));
    }

    @Test void stalePaidConfirmationReturnsTheCreditAndLeavesNoPurchasedTurnsOrWork() throws Exception {
        var user=browser();
        var older=say(user,"[Verse]\n春天");
        String second=UUID.randomUUID().toString();
        var newer=say(user,"[Verse]\n秋天",second);
        db.update("UPDATE agent_request_admission SET reserved_tokens=10000000 WHERE creator_id=? AND request_key=?",user.creator(),second);
        credits.credit(user.creator(),3,"audit-"+second);
        long before=credits.balance(user.creator());
        var body=Map.of("text","确认执行","requestKey",UUID.randomUUID().toString(),"paidConfirmed",true,"displayedContext",Map.of("promptId",lastMessageId(older)));
        var path="/api/app/agent/conversations/"+user.conversation()+"/messages";
        assertEquals("AGENT_PROMPT_CONFLICT",json.readTree(request(user.http(),"POST",path,body,user.csrf()).body()).path("code").asText());
        assertEquals(before,credits.balance(user.creator()));
        assertEquals(0,db.queryForObject("SELECT remaining_turns FROM agent_chat_allowance WHERE creator_id=?",Integer.class,user.creator()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_request_admission WHERE creator_id=? AND paid_turn=1 AND state='ABANDONED' AND reserved_tokens=0",Integer.class,user.creator()));
        assertEquals("AGENT_REQUEST_ABANDONED",json.readTree(request(user.http(),"POST",path,body,user.csrf()).body()).path("code").asText());
        assertEquals(before,credits.balance(user.creator()));
        assertTrue(works.list(user.creator(),0).isEmpty());
        assertEquals(lastMessageId(newer),lastMessageId(state(user)));
    }

    @Test void typingANumberCanOnlySelectFromTheMenuTheUserSaw() throws Exception {
        var user=browser();
        var first=say(user,"方向定好了");
        var second=say(user,"方向定好了");
        var wrong=fromTab(user,"2",UUID.randomUUID().toString(),Map.of("promptId",lastMessageId(first)));
        assertEquals(409,wrong.statusCode());
        assertEquals("AGENT_PROMPT_CONFLICT",json.readTree(wrong.body()).path("code").asText());
        assertEquals(lastMessageId(second),lastMessageId(state(user)));
        assertTrue(works.list(user.creator(),0).isEmpty());
        var result=ok(fromTab(user,"2",UUID.randomUUID().toString(),Map.of("promptId",lastMessageId(second))));
        assertEquals("create_work",result.path("action").asText());
        assertEquals(1,works.list(user.creator(),0).size());
    }

    @Test void aStaleTabCannotStartMusicOnNewerLyrics() throws Exception {
        var user=browser();
        var work=works.create(user.creator(),new WorkApi.Draft("","","","","","两版歌词"));
        var a=lyrics.save(user.creator(),work.id(),0,"第一版","等风来");
        var b=lyrics.save(user.creator(),work.id(),0,"第二版","看云走");
        String open="打开作品 "+work.id()+" 的歌词版本 ";
        var original=say(user,open+a.id()).path("songCard");
        say(user,open+b.id());
        var rejected=edit(user,"用当前歌词生成音乐",UUID.randomUUID().toString(),original);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work.id()));
        assertEquals("AGENT_LYRIC_TARGET_CHANGED",lastText(rejected).path("errorCode").asText());
        assertEquals(b.id(),rejected.path("songCard").path("revisionId").asText());
    }

    @Test void chineseSectionedLyricsAreOfferedForVerbatimSaveBeforeAnyMusicStarts() throws Exception {
        var user=browser();String body="【主歌一】\n街灯亮起来\n[副歌]\n把回忆留给海";
        var offered=say(user,"歌词如下：\n"+body);
        assertEquals("confirmation",lastText(offered).path("kind").asText());
        assertTrue(works.list(user.creator(),0).isEmpty());
        var saved=say(user,"确认执行").path("songCard");
        assertEquals(body,saved.path("lyrics").asText());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,saved.path("songId").asText()));
    }

    @Test void polishAfterBrainstormWritesTheUsersTopicOnceWhileThePlannerIsUnavailable() throws Exception {
        var user = browser();
        say(user, "帮我构思一首歌，先聊聊这个灵感。 平凡的一天");
        String key = UUID.randomUUID().toString(), command = "你先打磨一版歌词";
        var result = say(user, command, key);
        assertEquals("generate_lyrics", result.path("action").asText(), lastText(result).path("text").asText());
        var card = result.path("songCard");
        String work = card.path("songId").asText();
        assertEquals("平凡的一天", works.get(user.creator(), work).story());
        assertFalse(card.path("lyrics").asText().isBlank());
        assertEquals(card, say(user, command, key).path("songCard"));
        assertEquals(1, works.list(user.creator(), 0).size());
        assertEquals(1, lyrics.list(user.creator(), work).size());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?", Integer.class, work));
    }

    @Test void polishingUsesTheDisplayedRevisionAndRejectsAStaleTab() throws Exception {
        var user = browser();
        var work = works.create(user.creator(), new WorkApi.Draft("", "", "", "", "", "校园"));
        var first = lyrics.save(user.creator(), work.id(), 0, "旧版", "旧版的校园\n明天继续向前");
        var latest = lyrics.save(user.creator(), work.id(), 0, "新版", "新版的校园\n明天继续向前");
        String open = "打开作品 " + work.id() + " 的歌词版本 ";
        var tabA = say(user, open + first.id()).path("songCard");
        var tabB = say(user, open + latest.id()).path("songCard");
        String command = "你先打磨一版歌词";
        var stale = edit(user, command, UUID.randomUUID().toString(), tabA);
        assertEquals("AGENT_LYRIC_TARGET_CHANGED", lastText(stale).path("errorCode").asText());
        assertEquals(tabB, stale.path("songCard"));
        var selected = say(user, open + first.id()).path("songCard");
        String key = UUID.randomUUID().toString();
        var result = edit(user, command, key, selected);
        assertEquals("generate_lyrics", result.path("action").asText(), lastText(result).path("text").asText());
        var edited = result.path("songCard");
        assertEquals("旧版的校园\n愿你勇敢向前", edited.path("lyrics").asText());
        assertEquals(edited, edit(user, command, key, selected).path("songCard"));
        assertEquals(3, lyrics.list(user.creator(), work.id()).size());
        assertEquals(first.lyrics(), lyrics.get(user.creator(), work.id(), first.id()).lyrics());
        assertEquals(latest.lyrics(), lyrics.get(user.creator(), work.id(), latest.id()).lyrics());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?", Integer.class, work.id()));
    }

    @Test void startingLyricsFromTheStudioDoesNotRewriteAnAlreadyOpenedSong() throws Exception {
        var user=browser();
        var work=works.create(user.creator(),new WorkApi.Draft("","","","","","原作品"));
        var old=lyrics.save(user.creator(),work.id(),0,"旧歌词","原来的词");
        say(user,"打开作品 "+work.id()+" 的歌词版本 "+old.id());
        var fresh=say(user,"帮我写一首歌词，主题是 欢快的歌曲").path("songCard");
        assertNotEquals(work.id(),fresh.path("songId").asText());
        assertFalse(fresh.path("revisionId").asText("").isBlank());
        assertEquals(1,lyrics.list(user.creator(),work.id()).size());
        assertTrue(fresh.path("taskId").isNull());
    }

    @Test void lastLineUsesTheDisplayedWorksPreviousLyricsAfterRefreshAndRetry() throws Exception {
        var user=browser();
        var draft=new WorkApi.Draft("朋友","旅行","旧日海岸","","民谣","同名歌");
        var work=works.create(user.creator(),draft);
        String original="[副歌]\n旧版的第一句\n旧版的最后一句";
        var old=lyrics.save(user.creator(),work.id(),work.version(),"海边初稿",original);
        lyrics.confirm(user.creator(),work.id(),old.id());
        var updated=works.update(user.creator(),work.id(),work.version(),draft);
        var latest=lyrics.save(user.creator(),work.id(),updated.version(),"海边新稿","新版第一句\n新版末句");
        lyrics.confirm(user.creator(),work.id(),latest.id());
        works.create(user.creator(),draft);
        db.update("UPDATE music_work SET updated_at='2025-01-01' WHERE id=?",work.id());
        db.update("UPDATE lyric_revision SET created_at='2025-01-01' WHERE id=?",old.id());
        var list=lastText(say(user,"列出我的作品。"));
        assertEquals(work.id(),list.path("items").get(1).path("id").asText());
        assertEquals(latest.id(),say(user,"打开第二首").path("songCard").path("revisionId").asText());
        assertEquals(old.id(),say(user,"看上一版歌词").path("songCard").path("revisionId").asText());
        assertEquals(old.id(),state(user).path("songCard").path("revisionId").asText());
        assertTrue(lastText(say(user,"只改最后一句，先别生成。")).path("text").asText().contains("新句"));
        String key=UUID.randomUUID().toString(),command="把最后一句改成“海风替我说晚安”，先别生成。";
        var edited=say(user,command,key).path("songCard");
        assertEquals(work.id(),edited.path("songId").asText());
        assertEquals("[副歌]\n旧版的第一句\n海风替我说晚安",edited.path("lyrics").asText());
        assertEquals(edited,say(user,command,key).path("songCard"));
        assertEquals(3,lyrics.list(user.creator(),work.id()).size());
        assertEquals(original,lyrics.get(user.creator(),work.id(),old.id()).lyrics());
        assertEquals(latest.lyrics(),lyrics.get(user.creator(),work.id(),latest.id()).lyrics());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work.id()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='DEBIT'",Integer.class,user.creator()));
    }

    @Test void staleTabCannotEditAnotherRevisionAndRetriesKeepTheOriginalTarget() throws Exception {
        var user=browser();
        var work=works.create(user.creator(),new WorkApi.Draft("","","","","","两版歌词"));
        var first=lyrics.save(user.creator(),work.id(),0,"旧版","旧版开头\n旧版结尾");
        var second=lyrics.save(user.creator(),work.id(),0,"新版","新版开头\n新版结尾");
        String open="打开作品 "+work.id()+" 的歌词版本 ";
        var tabA=say(user,open+first.id()).path("songCard");
        var tabB=say(user,open+second.id()).path("songCard");
        String edit="把最后一句改成“明天再见”，先别生成。", staleKey=UUID.randomUUID().toString();
        var rejected=edit(user,edit,staleKey,tabA);
        assertEquals(2,lyrics.list(user.creator(),work.id()).size(),"a stale tab must never write against the other tab's revision");
        assertEquals("AGENT_LYRIC_TARGET_CHANGED",lastText(rejected).path("errorCode").asText());
        assertEquals(second.id(),rejected.path("songCard").path("revisionId").asText());
        var stalePrevious=edit(user,"看上一版歌词",UUID.randomUUID().toString(),tabA);
        assertEquals("AGENT_LYRIC_TARGET_CHANGED",lastText(stalePrevious).path("errorCode").asText());
        assertEquals(second.id(),stalePrevious.path("songCard").path("revisionId").asText());
        String key=UUID.randomUUID().toString();
        var saved=edit(user,edit,key,tabB).path("songCard");
        assertEquals("新版开头\n明天再见",saved.path("lyrics").asText());
        edit(user,edit,key,tabB);
        say(user,open+first.id());
        edit(user,edit,staleKey,tabA);
        assertEquals(3,lyrics.list(user.creator(),work.id()).size());
        assertEquals(first.lyrics(),lyrics.get(user.creator(),work.id(),first.id()).lyrics());
        assertEquals(second.lyrics(),lyrics.get(user.creator(),work.id(),second.id()).lyrics());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work.id()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE owner_id=? AND entry_type='DEBIT'",Integer.class,user.creator()));
    }

    @Test void anExactHistoricalSongTitleWinsOverAnIncidentalLyricInANewerRevision() throws Exception {
        var user=browser();
        var work=works.create(user.creator(),new WorkApi.Draft("","","","","","智能创作"));
        String title="海边来信";
        var old=lyrics.save(user.creator(),work.id(),0,title,"今天收到海边来信\n落款是你");
        db.update("UPDATE lyric_revision SET created_at='2024-01-01' WHERE id=?",old.id());
        lyrics.save(user.creator(),work.id(),0,"给夏天的歌","那封海边来信\n夹着一片叶子");
        var found=lastText(say(user,"查询我的作品，关键词："+title));
        var hit=found.path("items").get(0);
        assertEquals(old.id(),hit.path("data").path("revisionId").asText());
        assertEquals(title,hit.path("title").asText());
        assertEquals("lyricTitle",hit.path("data").path("matchSource").asText());
        assertEquals(title,hit.path("data").path("excerpt").asText());
        assertEquals(old.id(),say(user,found.path("commands").get(0).path("text").asText()).path("songCard").path("revisionId").asText());
    }

    @Test void nextPageOfFilteredWorksKeepsItsFilterAndItsDisplayedOrder() throws Exception {
        var user=browser();
        var draft=new WorkApi.Draft("","","","","","毕业那天");
        for(int i=1;i<=22;i++){
            var work=works.create(user.creator(),draft);
            db.update("UPDATE music_work SET updated_at=? WHERE id=?",String.format("2025-01-%02d",i),work.id());
        }
        works.create(user.creator(),new WorkApi.Draft("","","","","","另一个故事"));
        var unfiltered=lastText(say(user,"列出我的作品。"));
        assertEquals(20,unfiltered.path("items").size());
        for(int i=0;i<20;i++)
            assertEquals("打开作品 "+unfiltered.path("items").get(i).path("id").asText(),unfiltered.path("commands").get(i).path("text").asText());
        var first=lastText(say(user,"查询我的作品，关键词：毕业"));
        var commands=first.path("commands");
        var second=lastText(say(user,commands.get(commands.size()-1).path("text").asText()));
        assertEquals(2,second.path("items").size());
        var selected=second.path("items").get(1).path("id").asText();
        assertEquals(selected,say(user,"打开第二首").path("songCard").path("songId").asText());
    }

    @Test void ordinalUsesTheListSeenByThatTabEvenAfterAnotherTabSearches() throws Exception {
        var user=browser();
        for(String title:List.of("给春天一","给春天二","给秋天一","给秋天二"))
            works.create(user.creator(),new WorkApi.Draft("","","","","",title));
        var firstTab=say(user,"查询我的作品，关键词：春天");
        var list=lastText(firstTab);
        String expected=list.path("items").get(1).path("id").asText();
        var messages=firstTab.path("messages");
        var row=messages.get(messages.size()-1);
        assertEquals(list,json.readTree(row.path("contentJson").asText()));
        var visible=Map.of("workList",Map.of("messageId",row.path("id").asText(),"seq",row.path("seq").asLong()));
        say(user,"查询我的作品，关键词：秋天");
        String path="/api/app/agent/conversations/"+user.conversation()+"/messages";
        var opened=ok(request(user.http(),"POST",path,Map.of("text","打开第二首","requestKey",UUID.randomUUID().toString(),"displayedContext",visible),user.csrf()));
        assertEquals(expected,opened.path("songCard").path("songId").asText());
        ok(request(user.http(),"POST","/api/app/agent/conversations/"+user.conversation()+"/reset",Map.of(),user.csrf()));
        say(user,"查询我的作品，关键词：秋天");
        var expired=ok(request(user.http(),"POST",path,Map.of("text","打开第二首","requestKey",UUID.randomUUID().toString(),"displayedContext",visible),user.csrf()));
        assertEquals("AGENT_WORK_LIST_CHANGED",lastText(expired).path("errorCode").asText());
        assertTrue(expired.path("songCard").isNull());
    }

    @Test void lyricHitOpensTheSameOwnedRevisionByClickAndOrdinal() throws Exception {
        var owner=browser();var other=browser();
        var draft=new WorkApi.Draft("","","旧日故事。".repeat(120),"","","同名歌");
        var work=works.create(owner.creator(),draft);
        var sameName=works.create(owner.creator(),draft);
        var foreign=works.create(other.creator(),draft);
        var removed=works.create(owner.creator(),draft);
        String text="海风替我说晚安";
        String oldLyrics="[主歌]\n"+"打开窗看云走过\n".repeat(60)+"握住最后一束光\n"+text;
        var old=lyrics.save(owner.creator(),work.id(),0,"旧版歌词",oldLyrics);
        lyrics.confirm(owner.creator(),work.id(),old.id());
        db.update("UPDATE lyric_revision SET created_at='2024-01-01' WHERE id=?",old.id());
        var current=lyrics.save(owner.creator(),work.id(),0,"新版歌词","海风吹过树梢");
        lyrics.confirm(owner.creator(),work.id(),current.id());
        lyrics.save(owner.creator(),sameName.id(),0,"草稿","另一句");
        lyrics.save(other.creator(),foreign.id(),0,"他人的版本",text);
        lyrics.save(owner.creator(),removed.id(),0,"已删版本",text);
        works.delete(owner.creator(),removed.id(),true);
        var searched=lastText(say(owner,"哪首歌有“海风替我说晚安”这句歌词？"));
        assertEquals(1,searched.path("items").size());
        var evidence=searched.path("items").get(0).path("data");
        assertEquals("握住最后一束光\n"+text,evidence.path("excerpt").asText());
        assertEquals("lyrics",evidence.path("matchSource").asText());
        assertEquals(1,evidence.path("lyricVersion").asInt());
        assertEquals(old.id(),evidence.path("revisionId").asText());
        assertEquals(old.id(),say(owner,"打开第一首").path("songCard").path("revisionId").asText());
        String open=searched.path("commands").get(0).path("text").asText();
        var opened=say(owner,open).path("songCard");
        assertEquals(work.id(),opened.path("songId").asText());assertEquals(oldLyrics,opened.path("lyrics").asText());
        var denied=say(other,open);
        assertFalse(denied.path("messages").toString().contains("握住最后一束光"));
        works.delete(owner.creator(),work.id(),true);
        assertTrue(lastText(say(owner,"查询我的作品，关键词："+text)).path("items").isEmpty());
        assertFalse(old.id().equals(say(owner,open).path("songCard").path("revisionId").asText()));
    }
    @Test void archivedInterruptedRequestReturnsCurrentStateWithoutRestarting() throws Exception {
        Browser user = browser();
        String key = UUID.randomUUID().toString(), text = "查看我的作品";
        var ticket = admission.acquire(user.creator(),user.conversation(),key,text,null);
        admission.startTurn(ticket,user.conversation(),() -> turns.claim(user.creator(),user.conversation(),key,text));
        admission.finish(ticket,false);
        db.update("UPDATE agent_message SET created_at=? WHERE conversation_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(601)),user.conversation());
        ok(request(user.http(),"POST","/api/app/agent/conversations/"+user.conversation()+"/reset",Map.of(),user.csrf()));
        assertEquals(0,say(user,text,key).path("messages").size());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE conversation_id=? AND role='system' AND state='FAILED'",
                Integer.class,user.conversation()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM agent_request_admission WHERE conversation_id=?",Integer.class,user.conversation()));
    }

    @Test void waitingForMvApprovalCannotGenerateOrChargeForASongWithoutAudio() throws Exception {
        var user=browser();
        var card=say(user,"帮我写一首歌词，主题是平凡的一天").path("songCard");
        String work=card.path("songId").asText(), key=UUID.randomUUID().toString();
        long before=credits.balance(user.creator());
        String request="帮我设计当前歌曲的 MV，先给我分镜方案和费用，等我确认后再生成。 剧情短片";
        var preview=edit(user,request,key,card);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));
        assertEquals("video_capabilities",preview.path("action").asText());
        assertTrue(lastText(preview).path("text").asText().contains("音频"),lastText(preview).toString());
        assertEquals(3,lastText(preview).path("items").size());
        assertTrue(lastText(preview).path("text").asText().contains("720P 2 积分"));
        assertEquals(card,edit(user,request,key,card).path("songCard"));
        assertEquals("video_capabilities",say(user,"确认生成").path("action").asText());
        var status=say(user,"歌曲呢");
        assertEquals("get_current_song",status.path("action").asText());
        assertTrue(lastText(status).path("text").asText().contains("歌词"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE work_id=?",Integer.class,work));
        assertEquals(before,credits.balance(user.creator()));
    }

    @Test void creativeMenuSelectionProducesRealLyricsOverHttp() throws Exception {
        Browser user=browser();
        var offered=say(user,"方向定好了");
        assertTrue(offered.path("messages").toString().contains("choice_menu"));
        var selected=say(user,"1");
        assertEquals("generate_lyrics",selected.path("action").asText());
        assertTrue(selected.path("songCard").path("lyrics").asText().contains("一起走过校园"));
    }

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
        var waiting=say(browser,"歌曲呢");
        assertEquals("get_current_song",waiting.path("action").asText());
        assertTrue(lastText(waiting).path("text").asText().contains("排队"));

        var wav=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
        String asset=media.store(task,work,new ByteArrayInputStream(wav.array()));
        db.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?",asset,task);
        JsonNode ready=state(browser);
        assertEquals(asset,ready.path("songCard").path("assetId").asText());
        var earlierAudio=Map.of("workId",work,"revisionId",ready.path("songCard").path("revisionId").asText(),
            "media",Map.of("assetId",UUID.randomUUID().toString(),"taskId",task));
        for(String command:List.of("播放当前作品","分享当前作品")){
            var rejected=ok(fromTab(browser,command,UUID.randomUUID().toString(),earlierAudio));
            assertEquals("AGENT_MEDIA_TARGET_CHANGED",lastText(rejected).path("errorCode").asText());
            assertTrue(rejected.path("action").isNull());
            assertTrue(rejected.path("shareUrl").isNull());
        }
        assertEquals("/api/app/works/"+work+"/audio/"+asset,ready.path("songCard").path("audioUrl").asText());
        var playable=say(browser,"歌曲呢");
        assertEquals(ready.path("songCard").path("audioUrl"),lastText(playable).path("items").path(0).path("data").path("audioUrl"));
        long beforePreview=credits.balance(browser.creator());
        String previewRequest="帮我设计当前歌曲的 MV，先给我分镜方案和费用，等我确认后再生成。 剧情短片";
        String previewKey=UUID.randomUUID().toString();
        var preview=lastText(say(browser,previewRequest,previewKey));
        assertEquals("video_plan",preview.path("kind").asText(),preview.toString());
        for(String part:List.of("基础分镜","0–30","30–60","60–61","720P 122 积分"))
            assertTrue(preview.path("text").asText().contains(part),preview::toString);
        var prepared=preview.path("items").path(0).path("data");
        assertEquals(work,prepared.path("workId").asText());
        assertEquals(asset,prepared.path("assetId").asText());
        assertTrue(preview.path("commands").isEmpty());
        assertEquals(preview,lastText(say(browser,previewRequest,previewKey)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM music_video_project WHERE work_id=?",Integer.class,work));
        assertEquals(beforePreview,credits.balance(browser.creator()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work));
        assertTrue(say(browser,"给当前作品点赞").path("liked").asBoolean());
        assertTrue(state(browser).path("liked").asBoolean());
        assertFalse(say(browser,"取消点赞当前作品").path("liked").asBoolean());
        JsonNode shared=say(browser,"分享当前作品");
        assertTrue(shared.path("shareUrl").asText().startsWith("/song/"),shared.toString());
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

    @Test void applicationCatalogAndOwnedLibraryAreOperableInsideChat() throws Exception {
        Browser browser=browser(),other=browser();
        works.create(browser.creator(),new WorkApi.Draft("朋友","友情","一起旅行","","温暖民谣","旅行"));
        works.create(browser.creator(),new WorkApi.Draft("朋友","友情","海边的故事","","温暖民谣","海边"));
        works.create(other.creator(),new WorkApi.Draft("朋友","友情","他人的私人故事","","温暖民谣","不可见的作品"));
        var catalog=say(browser,"查看所有功能");
        assertTrue(catalog.path("messages").toString().contains("update_music_settings"));
        assertTrue(catalog.path("messages").toString().contains("payment_catalog"));
        var listed=say(browser,"查看我的作品");
        var contents=lastText(listed);
        assertEquals("works",contents.path("kind").asText());assertEquals(2,contents.path("items").size());
        assertFalse(contents.toString().contains("不可见的作品"));
        String selected=contents.path("items").get(1).path("id").asText();
        assertEquals(selected,say(browser,"选择第二首").path("songCard").path("songId").asText());
        var renamed=say(browser,"把当前作品改名为夏天");
        assertEquals("夏天",renamed.path("songCard").path("title").asText());assertEquals("夏天",works.get(browser.creator(),selected).title());
        var prompt=say(browser,"删除当前作品");assertEquals("confirmation",lastText(prompt).path("kind").asText());
        assertNotNull(works.get(browser.creator(),selected));
        var deleted=say(browser,"确认执行");assertTrue(deleted.path("songCard").isNull());
        assertEquals(1,works.list(browser.creator(),0).size());assertEquals(1,works.list(other.creator(),0).size());
        var credit=say(browser,"查看我的积分");assertEquals("credits",lastText(credit).path("kind").asText());
        assertEquals(4,lastText(credit).path("items").size());
    }
    private JsonNode lastText(JsonNode snapshot) {
        var messages=snapshot.path("messages");
        for(int i=messages.size()-1;i>=0;i--)if("assistant".equals(messages.get(i).path("role").asText())&&"TEXT".equals(messages.get(i).path("kind").asText()))return json.readTree(messages.get(i).path("contentJson").asText());
        fail("No assistant text result");return null;
    }

    @Test void successfulTaskResumesOnlyThePersistedAuthorizedShareOnce() throws Exception {
        Browser browser=browser();
        say(browser,"先写歌词，不生成音乐");
        var started=say(browser,"用当前歌词生成音乐然后分享");
        String work=started.path("songCard").path("songId").asText(),task=started.path("taskId").asText();
        assertFalse(task.isBlank(),started.toString());assertEquals("waiting",lastText(started).path("kind").asText());
        assertTrue(started.path("continuation").isNull());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM share_link WHERE work_id=?",Integer.class,work));
        var wav=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
        String asset=media.store(task,work,new ByteArrayInputStream(wav.array()));
        db.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?",asset,task);
        var ready=state(browser);var next=ready.path("continuation");
        assertEquals("继续已安排的操作",next.path("text").asText(),ready.toString());
        String key=next.path("requestKey").asText();UUID.fromString(key);
        var shared=say(browser,next.path("text").asText(),key);
        assertTrue(shared.path("shareUrl").asText().startsWith("/song/"),shared.toString());
        assertTrue(shared.path("continuation").isNull());
        assertEquals(shared.path("shareUrl").asText(),say(browser,next.path("text").asText(),key).path("shareUrl").asText());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM share_link WHERE work_id=?",Integer.class,work));
    }
}
