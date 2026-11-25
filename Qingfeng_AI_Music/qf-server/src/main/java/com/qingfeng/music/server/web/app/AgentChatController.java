package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.agent.AgentChatService;
import com.qingfeng.music.server.agent.AgentCapacityHandler;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Job;
import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.async.DeferredResult;
import com.qingfeng.music.server.agent.AgentChatExecutor;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.common.exception.DomainException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
@RequestMapping("/api/app/agent/conversations/{id}")
public class AgentChatController {
    public record Request(String text,String requestKey,String locale,Boolean paidConfirmed,AgentChatService.DisplayedContext displayedContext) {
        public Request(String text,String requestKey,String locale){this(text,requestKey,locale,false,null);}
        public Request(String text,String requestKey,String locale,Boolean paidConfirmed){this(text,requestKey,locale,paidConfirmed,null);}
    }
    private final AgentChatService chat;
    private final AgentChatExecutor executor;
    @Autowired(required=false) private AgentCapacityHandler capacity;
    @Autowired(required=false) private AiLegacyResults legacyResults;
    public AgentChatController(AgentChatService chat,AgentChatExecutor executor){this.chat=chat;this.executor=executor;}
    @PostMapping("/messages")
    public DeferredResult<?> send(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String id,
                                  @RequestBody Request request, @RequestHeader(value="Prefer",required=false) String prefer) {
        boolean paid = Boolean.TRUE.equals(request.paidConfirmed());
        if (capacity == null) return executor.submit(creator.id(), id, request.text(), request.requestKey(), request.locale(),
            paid, request.displayedContext());
        Job job = capacity.submit(creator.id(), id, request.requestKey(),
            new AgentCapacityHandler.Input(request.text(), request.locale(), paid, request.displayedContext()));
        // Older clients poll only their own transport key. Deliver their canonical approval result inline.
        boolean pollable = "respond-async".equalsIgnoreCase(prefer) && job.requestKey().equals(request.requestKey());
        if (!pollable && !job.state().terminal())
            return legacyResults.observe(job, this::response);
        return AiLegacyResults.immediate(response(job));
    }

    public DeferredResult<AgentChatService.Snapshot> send(CreatorPrincipal creator, String id, Request request) {
        return executor.submit(creator.id(), id, request.text(), request.requestKey(), request.locale(),
            Boolean.TRUE.equals(request.paidConfirmed()), request.displayedContext());
    }

    @GetMapping("/messages/{requestKey}/result")
    public ResponseEntity<?> result(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String id,
                                    @PathVariable String requestKey) {
        if (capacity == null) throw new DomainException("AGENT_TURN_NOT_FOUND", "对话请求不存在。");
        return response(capacity.find(creator.id(), id, requestKey));
    }

    private ResponseEntity<?> response(Job job) {
        if (job.state() == State.SUCCEEDED)
            return ResponseEntity.ok(chat.completed(job.owner(), job.scope(), job.requestKey())
                .orElseThrow(() -> new DomainException("AGENT_TURN_NOT_FOUND", "原会话结果已失效。")));
        if (job.state() == State.FAILED || job.state() == State.UNKNOWN)
            throw new DomainException(job.errorCode() == null ? "AGENT_TURN_FAILED" : job.errorCode(),
                "对话请求已结束，请查看现有会话和作品状态。");
        return ResponseEntity.accepted().body(capacity.receipt(job));
    }
    @GetMapping("/state")
    public AgentChatService.Snapshot state(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestParam(required=false)Long afterSeq,@RequestParam(required=false)Long historyEpoch){return chat.state(creator.id(),id,afterSeq,historyEpoch);}
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String,String>> failure(DomainException error){
        int status=error.code().contains("NOT_FOUND")?404:error.code().contains("CONFLICT")||error.code().contains("IN_PROGRESS")?409:error.code().equals("AGENT_BUSY")||error.code().endsWith("GENERATION_BUSY")||error.code().contains("RATE_LIMITED")||error.code().contains("BUDGET_EXCEEDED")?429:error.code().contains("ADMISSION_")||error.code().contains("DEADLINE")?503:400;
        var response=ResponseEntity.status(status);if(status==429||status==503)response.header("Retry-After","60");
        return response.body(Map.of("code",error.code(),"message",error.getMessage()));
    }
}
