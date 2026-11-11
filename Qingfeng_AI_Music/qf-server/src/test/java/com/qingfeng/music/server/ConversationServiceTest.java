package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.api.ConversationRepository;
import com.qingfeng.music.conversation.api.ConversationState;
import com.qingfeng.music.conversation.application.ConversationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 会话应用层纯单测：不依赖 Spring/H2，验证事务方法的三处更新、revision 条件与回放语义。 */
class ConversationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    private static final class FakeRepo implements ConversationRepository {
        final Map<String, Conversation> byCreator = new HashMap<>();
        final Map<String, ConversationState> states = new HashMap<>();
        final Map<String, List<AgentMessage>> messages = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        public Conversation findByCreator(String creatorId) { return byCreator.get(creatorId); }

        public Conversation create(String id, String creatorId, Instant now) {
            Conversation value = new Conversation(id, creatorId, null, now, now);
            byCreator.put(creatorId, value);
            messages.computeIfAbsent(id, key -> new ArrayList<>());
            return value;
        }

        public List<AgentMessage> listMessages(String conversationId) {
            return List.copyOf(messages.getOrDefault(conversationId, List.of()));
        }

        public long nextSeq(String conversationId) {
            return messages.getOrDefault(conversationId, List.of()).size() + 1L;
        }

        public void insertMessage(AgentMessage message) {
            messages.computeIfAbsent(message.conversationId(), key -> new ArrayList<>()).add(message);
        }

        public void updateCurrentWork(String conversationId, String workId, Instant now) {
            calls.add("work");
            Conversation previous = byCreator.values().stream()
                    .filter(item -> item.id().equals(conversationId)).findFirst().orElseThrow();
            byCreator.put(previous.creatorId(), new Conversation(previous.id(), previous.creatorId(),
                    workId, previous.createdAt(), now));
        }

        public ConversationState findState(String conversationId) { return states.get(conversationId); }

        public void createState(String conversationId, Instant now) {
            states.put(conversationId, new ConversationState(conversationId, null, 0));
        }

        public int updateState(String conversationId, String json, long expectedRevision, Instant now) {
            calls.add("state");
            ConversationState current = states.get(conversationId);
            if (current == null || current.revision() != expectedRevision) return 0;
            states.put(conversationId, new ConversationState(conversationId, json, expectedRevision + 1));
            return 1;
        }

        public void clear(String conversationId, Instant now) {
            messages.put(conversationId, new ArrayList<>());
            Conversation previous = byCreator.values().stream()
                    .filter(item -> item.id().equals(conversationId)).findFirst().orElseThrow();
            byCreator.put(previous.creatorId(), new Conversation(previous.id(), previous.creatorId(),
                    null, previous.createdAt(), now));
            states.put(conversationId, new ConversationState(conversationId, null,
                    states.get(conversationId).revision() + 1));
        }

        public List<AgentMessage> findPendingPlaceholders() { return List.of(); }

        public void updateMessageState(String messageId, String state) { calls.add("messageState"); }
    }

    private static ConversationService service(FakeRepo repo) {
        return new ConversationService(repo, () -> NOW);
    }

    @Test
    void currentIsStableForSameCreatorAndIsolatedAcrossCreators() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        Conversation first = service.current("creator-a");
        assertEquals(first.id(), service.current("creator-a").id());
        assertNotEquals(first.id(), service.current("creator-b").id());
        assertNotNull(repo.states.get(first.id()));
    }

    @Test
    void appendMessageCanBeReplayedInSeqOrder() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        String id = service.current("creator-a").id();
        service.appendMessage("creator-a", id, "user", AgentMessage.KIND_TEXT,
                AgentMessage.STATE_DONE, "{\"text\":\"第一句\"}", null);
        service.appendMessage("creator-a", id, "assistant", AgentMessage.KIND_TOOL_CARD,
                AgentMessage.STATE_DONE, "{}", "task-1");
        List<AgentMessage> replay = service.listMessages("creator-a", id);
        assertEquals(2, replay.size());
        assertEquals(1, replay.get(0).seq());
        assertEquals(2, replay.get(1).seq());
        assertEquals("task-1", replay.get(1).taskId());
    }

    @Test
    void resetKeepsConversationIdButClearsMessagesAndPointer() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        String id = service.current("creator-a").id();
        service.appendMessage("creator-a", id, "user", AgentMessage.KIND_TEXT,
                AgentMessage.STATE_DONE, "{}", null);
        service.reset("creator-a", id);
        assertEquals(id, service.current("creator-a").id());
        assertTrue(service.listMessages("creator-a", id).isEmpty());
        assertNull(service.current("creator-a").currentWorkId());
    }

    @Test
    void applyToolResultUpdatesAllThreePlacesInOneCall() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        String id = service.current("creator-a").id();
        AgentMessage message = service.applyToolResult("creator-a", id, "m1", "{\"card\":1}",
                "work-1", "{\"title\":\"回家的风\"}", "task-1", 0);
        assertEquals("m1", message.id());
        assertEquals("work-1", service.current("creator-a").currentWorkId());
        assertEquals("{\"title\":\"回家的风\"}", repo.states.get(id).currentSongJson());
        assertEquals(1, repo.states.get(id).revision());
        assertEquals(List.of("work", "state"), repo.calls);
        assertEquals(1, service.listMessages("creator-a", id).size());
    }

    @Test
    void staleRevisionThrowsConflictAndIsMappedTo409ByW2() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        String id = service.current("creator-a").id();
        service.applyToolResult("creator-a", id, "m1", "{}", "work-1", "{\"title\":\"a\"}", null, 0);
        DomainException error = assertThrows(DomainException.class,
                () -> service.applyToolResult("creator-a", id, "m2", "{}", "work-2", "{\"title\":\"b\"}", null, 0));
        assertEquals(ConversationService.CONFLICT, error.code());
        assertEquals(1, repo.states.get(id).revision());
    }

    @Test
    void foreignConversationIsRejected() {
        FakeRepo repo = new FakeRepo();
        ConversationService service = service(repo);
        String id = service.current("creator-a").id();
        service.current("creator-b");
        assertThrows(DomainException.class, () -> service.listMessages("creator-b", id));
        assertThrows(DomainException.class, () -> service.reset("creator-b", id));
    }
}
