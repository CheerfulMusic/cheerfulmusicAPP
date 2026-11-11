package com.qingfeng.music.server.web.app;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.api.AgentMessage;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * W1 会话 REST。开关 qingfeng.agent.chat.enabled=false 时本 Controller 不注册，
 * 新端点一律 404，既有 /agent 页面行为完全不变。
 */
@RestController
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.agent.chat.enabled", havingValue = "true")
@RequestMapping("/api/app/agent/conversations")
public class AgentConversationController {

    public record ConversationView(String id, String currentWorkId) {}

    public record MessageView(String id, String conversationId, long seq, String role,
                              String kind, String state, String contentJson, String taskId,
                              String createdAt) {}

    public record ClearedView(boolean cleared) {}

    private final ConversationService service;

    public AgentConversationController(ConversationService service) { this.service = service; }

    @GetMapping("/current")
    public ConversationView current(@AuthenticationPrincipal CreatorPrincipal creator) {
        Conversation conversation = service.current(creator.id());
        return new ConversationView(conversation.id(), conversation.currentWorkId());
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@AuthenticationPrincipal CreatorPrincipal creator,
                                      @PathVariable String id) {
        return service.listMessages(creator.id(), id).stream().map(AgentConversationController::view).toList();
    }

    /** 清空会话但保留 conversation 行，会话 ID 稳定，便于前端回放。 */
    @PostMapping("/{id}/reset")
    public ClearedView reset(@AuthenticationPrincipal CreatorPrincipal creator,
                             @PathVariable String id) {
        service.reset(creator.id(), id);
        return new ClearedView(true);
    }

    @DeleteMapping("/{id}")
    public ClearedView clear(@AuthenticationPrincipal CreatorPrincipal creator,
                             @PathVariable String id) {
        service.reset(creator.id(), id);
        return new ClearedView(true);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, String>> onDomain(DomainException error) {
        int status = ConversationService.CONFLICT.equals(error.code()) ? 409 : 404;
        return ResponseEntity.status(status).body(Map.of("code", error.code(), "message", error.getMessage()));
    }

    private static MessageView view(AgentMessage message) {
        return new MessageView(message.id(), message.conversationId(), message.seq(),
                message.role(), message.kind(), message.state(), message.contentJson(),
                message.taskId(), message.createdAt() == null ? "" : message.createdAt().toString());
    }
}
