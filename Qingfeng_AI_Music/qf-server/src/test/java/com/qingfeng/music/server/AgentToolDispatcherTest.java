package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.api.ToolResult;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.AgentToolRegistry;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.api.ConversationState;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.infrastructure.persistence.generation.TaskRow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 纯单测：路由正确、busy→占位不抛 500、其余异常向上抛（→ 对应 HTTP 状态码）。 */
class AgentToolDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private static final class FakeRepo implements ConversationRepository {
        final Map<String, Conversation> byCreator = new HashMap<>();
        final Map<String, ConversationState> states = new HashMap<>();
        final Map<String, List<AgentMessage>> messages = new HashMap<>();
        final List<AgentMessage> all = new ArrayList<>();
        String lastPlaceholderCreator;
        String lastPlaceholderConv;

        public Conversation findByCreator(String creatorId) { return byCreator.get(creatorId); }

        public Conversation create(String id, String creatorId, Instant now) {
            Conversation value = new Conversation(id, creatorId, null, now, now);
            byCreator.put(creatorId, value);
            messages.computeIfAbsent(id, k -> new ArrayList<>());
            return value;
        }

        public List<AgentMessage> listMessages(String conversationId) {
            return List.copyOf(messages.getOrDefault(conversationId, List.of()));
        }

        public long nextSeq(String conversationId) {
            return messages.getOrDefault(conversationId, List.of()).size() + 1L;
        }

        public void insertMessage(AgentMessage message) {
            messages.computeIfAbsent(message.conversationId(), k -> new ArrayList<>()).add(message);
            all.add(message);
        }

        public void updateCurrentWork(String conversationId, String workId, Instant now) {
            Conversation previous = byCreator.values().stream()
                    .filter(i -> i.id().equals(conversationId)).findFirst().orElseThrow();
            byCreator.put(previous.creatorId(), new Conversation(previous.id(), previous.creatorId(),
                    workId, previous.createdAt(), now));
        }

        public ConversationState findState(String conversationId) { return states.get(conversationId); }

        public void createState(String conversationId, Instant now) {
            states.put(conversationId, new ConversationState(conversationId, null, 0));
        }

        public int updateState(String conversationId, String json, long expectedRevision, Instant now) {
            ConversationState current = states.get(conversationId);
            if (current == null || current.revision() != expectedRevision) return 0;
            states.put(conversationId, new ConversationState(conversationId, json, expectedRevision + 1));
            return 1;
        }

        public void clear(String conversationId, Instant now) {
            messages.put(conversationId, new ArrayList<>());
        }

        public List<AgentMessage> findPendingPlaceholders() {
            return all.stream()
                    .filter(m -> AgentMessage.KIND_PLACEHOLDER.equals(m.kind())
                            && AgentMessage.STATE_PENDING.equals(m.state()))
                    .toList();
        }

        public void updateMessageState(String messageId, String state) {
            for (AgentMessage m : all) {
                if (m.id().equals(messageId)) {
                    List<AgentMessage> list = messages.get(m.conversationId());
                    int idx = list.indexOf(m);
                    list.set(idx, new AgentMessage(m.id(), m.conversationId(), m.seq(), m.role(),
                            m.kind(), state, m.contentJson(), m.taskId(), m.createdAt()));
                }
            }
        }
    }

    private static final class OkHandler implements AgentToolHandler {
        boolean invoked;
        public AgentTool tool() { return AgentTool.GENERATE_SONG; }
        public AgentToolResult execute(ToolContext ctx, Object args) {
            invoked = true;
            SongCard card = new SongCard(ctx.current().currentWorkId(), "歌", "温暖民谣", "", "", "READY", null, 0L);
            return new AgentToolResult(card, ctx.current().currentWorkId(),
                    ctx.state() == null ? null : ctx.state().currentSongJson(),
                    ctx.state() == null ? 0L : ctx.state().revision(), "task-1", false);
        }
    }

    private static final class BusyHandler implements AgentToolHandler {
        public AgentTool tool() { return AgentTool.GENERATE_SONG; }
        public AgentToolResult execute(ToolContext ctx, Object args) {
            throw new DomainException("PERSONAL_GENERATION_BUSY", "并发占用");
        }
    }

    private static final class ErrorHandler implements AgentToolHandler {
        public AgentTool tool() { return AgentTool.GENERATE_SONG; }
        public AgentToolResult execute(ToolContext ctx, Object args) {
            throw new DomainException("INVALID_SECTION", "段落缺失");
        }
    }

    private static final class FakeGenerationApi implements GenerationApi {
        final Map<String, List<TaskRow>> byWork = new HashMap<>();
        public Preflight preflight(String o, String w, String r) { return null; }
        public Task submit(String o, String w, String r, String k) { return null; }
        public List<Task> list(String o, String w) { return byWork.getOrDefault(w, List.of()).stream()
                .map(this::toTask).toList(); }
        public void retryArchive(String o, String w, String t) {}
        public Task cancel(String o, String w, String t) { return null; }
        public Task closeChecking(String o, String w, String t, boolean a) { return null; }
        private Task toTask(TaskRow row) {
            return new Task(row.id, row.workId, row.revisionId, row.workVersion, row.requestKey,
                    row.title, row.lyrics, row.style, row.provider,
                    GenerationStatus.valueOf(row.status), row.createdAt);
        }
    }

    private record Fixture(AgentToolDispatcher dispatcher, FakeRepo repo, FakeGenerationApi gen) {}

    private Fixture fixture(FakeRepo repo, AgentToolHandler... handlers) {
        var registry = new AgentToolRegistry();
        var svc = new ConversationService(repo, () -> NOW);
        var gen = new FakeGenerationApi();
        Map<AgentTool, AgentToolHandler> map = new HashMap<>();
        for (var h : handlers) map.put(h.tool(), h);
        return new Fixture(new AgentToolDispatcher(registry, map, svc, gen), repo, gen);
    }

    private static TaskRow runningTask(String id, String workId, String status) {
        var row = new TaskRow();
        row.id = id; row.workId = workId; row.status = status; row.workVersion = 1;
        row.revisionId = "r1"; row.requestKey = "k1"; row.title = "t"; row.lyrics = "l";
        row.style = "s"; row.provider = "kie"; row.createdAt = NOW; row.vocalGender = "AUTO";
        return row;
    }

    @Test
    void routesToTheRegisteredHandler() {
        var ok = new OkHandler();
        var f = fixture(new FakeRepo(), ok);
        ToolResult result = f.dispatcher().dispatch(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("歌", "温暖民谣", "zh", 120, null, null),
                "creator-a", "rk-1");
        assertTrue(ok.invoked);
        assertEquals(ToolResult.OK, result.status());
    }

    @Test
    void unknownToolReturnsErrorWithoutThrowing() {
        var f = fixture(new FakeRepo());
        ToolResult result = f.dispatcher().dispatch(AgentTool.GET_CURRENT_SONG, new AgentTool.GetCurrentSongArgs(),
                "creator-a", "rk-1");
        assertEquals(ToolResult.ERROR, result.status());
        assertEquals("UNKNOWN_TOOL", result.errorCode());
    }

    @Test
    void busyIsCaughtAndWritesPlaceholderInsteadOf500() {
        var f = fixture(new FakeRepo(), new BusyHandler());
        ToolResult result = f.dispatcher().dispatch(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("歌", "温暖民谣", "zh", 120, null, null),
                "creator-a", "rk-1");
        assertEquals(ToolResult.BUSY, result.status());
        List<AgentMessage> placeholders = f.repo().findPendingPlaceholders();
        assertEquals(1, placeholders.size());
        assertEquals(AgentMessage.KIND_PLACEHOLDER, placeholders.get(0).kind());
        assertEquals(AgentMessage.STATE_PENDING, placeholders.get(0).state());
    }

    @Test
    void nonBusyDomainExceptionPropagatesToHttpStatusMapping() {
        var f = fixture(new FakeRepo(), new ErrorHandler());
        DomainException error = assertThrows(DomainException.class, () -> f.dispatcher().dispatch(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("歌", "温暖民谣", "zh", 120, null, null),
                "creator-a", "rk-1"));
        assertEquals("INVALID_SECTION", error.code());
    }

    @Test
    void validationFailurePropagatesBeforeHandler() {
        var f = fixture(new FakeRepo(), new OkHandler());
        // 直接读取 OkHandler 实例以验证未被调用
        var ok = new OkHandler();
        var f2 = fixture(new FakeRepo(), ok);
        DomainException error = assertThrows(DomainException.class, () -> f2.dispatcher().dispatch(AgentTool.GENERATE_SONG,
                new AgentTool.GenerateSongArgs("歌", "不存在风格", "zh", 120, null, null),
                "creator-a", "rk-1"));
        assertEquals("INVALID_STYLE", error.code());
        assertFalse(ok.invoked);
    }

    @Test
    void findInFlightTaskReturnsOnlyNonTerminalTask() {
        var f = fixture(new FakeRepo(), new OkHandler());
        f.repo().create("c1", "creator-a", NOW);
        f.repo().updateCurrentWork("c1", "W1", NOW);
        f.gen().byWork.put("W1", List.of(runningTask("T1", "W1", "RUNNING")));
        assertEquals("T1", f.dispatcher().findInFlightTask("creator-a", "W1"));
        f.gen().byWork.put("W1", List.of(runningTask("T1", "W1", "SUCCEEDED")));
        assertNull(f.dispatcher().findInFlightTask("creator-a", "W1"),
                "终态任务不应作为在途哨兵");
    }
}
