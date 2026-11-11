package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.AgentToolRegistry;
import com.qingfeng.music.agent.application.PendingIntentDispatcher;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.api.ConversationState;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 纯单测：验证 W2 排队的「自动继续」语义——
 * 1. 前序任务进入终态（SUCCEEDED/FAILED/CANCELLED）后，PENDING 占位被自动触发；
 * 2. 触发时复用原 requestKey 重新分发（不重复扣费：requestKey 是既有 generation_task 表的幂等/去重键，
 *    见 TaskMapper.insertOrIgnore 的 ON DUPLICATE KEY UPDATE request_key=request_key）；
 * 3. 触发前先把占位标记为 RUNNING，避免重复触发；
 * 4. 前序任务仍在途时，占位不被触发（不会重复分发）。
 */
class PendingIntentDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String REQUEST_KEY = "rk-original-123";
    private static final String WORK_ID = "W1";
    private static final String CONV_ID = "c1";
    private static final String CREATOR = "creator-a";

    private static final class FakeRepo implements ConversationRepository {
        final List<AgentMessage> all = new ArrayList<>();
        public Conversation findByCreator(String c) {
            return new Conversation(CONV_ID, c, WORK_ID, NOW, NOW);
        }
        public Conversation create(String id, String c, Instant n) { return null; }
        public List<AgentMessage> listMessages(String id) { return List.of(); }
        public long nextSeq(String id) { return 1L; }
        public void insertMessage(AgentMessage m) { all.add(m); }
        public void updateCurrentWork(String id, String w, Instant n) {}
        public ConversationState findState(String id) { return null; }
        public void createState(String id, Instant n) {}
        public int updateState(String id, String j, long r, Instant n) { return 1; }
        public void clear(String id, Instant n) {}
        public List<AgentMessage> findPendingPlaceholders() {
            return all.stream()
                    .filter(m -> AgentMessage.KIND_PLACEHOLDER.equals(m.kind())
                            && AgentMessage.STATE_PENDING.equals(m.state()))
                    .toList();
        }
        public void updateMessageState(String id, String state) {
            for (int i = 0; i < all.size(); i++) {
                AgentMessage m = all.get(i);
                if (m.id().equals(id)) {
                    all.set(i, new AgentMessage(m.id(), m.conversationId(), m.seq(), m.role(),
                            m.kind(), state, m.contentJson(), m.taskId(), m.createdAt()));
                }
            }
        }
    }

    private static final class FakeGenerationApi implements GenerationApi {
        final Map<String, List<Task>> byWork = new HashMap<>();
        public Preflight preflight(String o, String w, String r) { return null; }
        public Task submit(String o, String w, String r, String k) { return null; }
        public List<Task> list(String o, String w) { return byWork.getOrDefault(w, List.of()); }
        public void retryArchive(String o, String w, String t) {}
        public Task cancel(String o, String w, String t) { return null; }
        public Task closeChecking(String o, String w, String t, boolean a) { return null; }
    }

    /** 只读捕获处理器：记录被重新分发时的 tool / args / requestKey，用于断言「复用同一 requestKey」。 */
    private static final class CapturingHandler implements AgentToolHandler {
        boolean invoked;
        AgentTool tool;
        Object args;
        String requestKey;
        public AgentTool tool() { return AgentTool.GET_CURRENT_SONG; }
        public AgentToolResult execute(ToolContext ctx, Object a) {
            invoked = true;
            tool = tool();
            args = a;
            requestKey = ctx.requestKey();
            SongCard card = new SongCard(WORK_ID, "歌", "温暖民谣", "", "", "READY", null, 0L);
            return new AgentToolResult(card, WORK_ID, null, 0L, null, true);
        }
    }

    private static GenerationApi.Task task(String id, String workId, String status) {
        return new GenerationApi.Task(id, workId, "r1", 1L, "k1", "t", "l", "s", "kie",
                GenerationStatus.valueOf(status), NOW);
    }

    private static AgentMessage placeholder(String prevTaskId) {
        String intent = AgentToolDispatcher.serializeIntent(
                AgentTool.GET_CURRENT_SONG, new AgentTool.GetCurrentSongArgs(),
                REQUEST_KEY, prevTaskId, WORK_ID, CONV_ID, CREATOR);
        return new AgentMessage(UUID.randomUUID().toString(), CONV_ID, 1L, "assistant",
                AgentMessage.KIND_PLACEHOLDER, AgentMessage.STATE_PENDING, intent, prevTaskId, NOW);
    }

    @Test
    void terminalPreviousTaskTriggersRedisatchReusingSameRequestKey() {
        FakeRepo repo = new FakeRepo();
        FakeGenerationApi gen = new FakeGenerationApi();
        gen.byWork.put(WORK_ID, List.of(task("T1", WORK_ID, GenerationStatus.SUCCEEDED.name())));
        repo.all.add(placeholder("T1"));

        CapturingHandler handler = new CapturingHandler();
        var registry = new AgentToolRegistry();
        var svc = new ConversationService(repo);
        Map<AgentTool, AgentToolHandler> map = new HashMap<>();
        map.put(AgentTool.GET_CURRENT_SONG, handler);
        var dispatcher = new AgentToolDispatcher(registry, map, svc, gen);
        var scheduler = new PendingIntentDispatcher(repo, dispatcher, gen);

        int fired = scheduler.processOnce();

        assertEquals(1, fired, "前序任务终态后必须触发一次重新分发");
        assertEquals(AgentMessage.STATE_RUNNING, repo.all.get(0).state(),
                "触发后占位应标记为 RUNNING，防止重复触发");
        assertTrue(handler.invoked);
        assertEquals(AgentTool.GET_CURRENT_SONG, handler.tool);
        assertEquals(REQUEST_KEY, handler.requestKey,
                "重新分发必须复用原 requestKey（既有 generation_task 表的幂等/去重键），不重复扣费");
        assertTrue(handler.args instanceof AgentTool.GetCurrentSongArgs);
    }

    @Test
    void inFlightPreviousTaskDoesNotTrigger() {
        FakeRepo repo = new FakeRepo();
        FakeGenerationApi gen = new FakeGenerationApi();
        gen.byWork.put(WORK_ID, List.of(task("T1", WORK_ID, GenerationStatus.RUNNING.name())));
        repo.all.add(placeholder("T1"));

        CapturingHandler handler = new CapturingHandler();
        var registry = new AgentToolRegistry();
        var svc = new ConversationService(repo);
        Map<AgentTool, AgentToolHandler> map = new HashMap<>();
        map.put(AgentTool.GET_CURRENT_SONG, handler);
        var dispatcher = new AgentToolDispatcher(registry, map, svc, gen);
        var scheduler = new PendingIntentDispatcher(repo, dispatcher, gen);

        int fired = scheduler.processOnce();

        assertEquals(0, fired, "前序任务仍在途时不应触发，避免重复分发");
        assertEquals(AgentMessage.STATE_PENDING, repo.all.get(0).state());
        assertFalse(handler.invoked);
    }
}
