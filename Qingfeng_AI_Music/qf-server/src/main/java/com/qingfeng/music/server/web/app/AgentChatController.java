package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.agent.AgentChatService;
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
    public record Request(String text,String requestKey,String locale,Boolean paidConfirmed) { public Request(String text,String requestKey,String locale){this(text,requestKey,locale,false);} }
    private final AgentChatService chat;
    private final AgentChatExecutor executor;
    public AgentChatController(AgentChatService chat,AgentChatExecutor executor){this.chat=chat;this.executor=executor;}
    @PostMapping("/messages")
    public org.springframework.web.context.request.async.DeferredResult<AgentChatService.Snapshot> send(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestBody Request request){
        return Boolean.TRUE.equals(request.paidConfirmed())?executor.submit(creator.id(),id,request.text(),request.requestKey(),request.locale(),true):executor.submit(creator.id(),id,request.text(),request.requestKey(),request.locale());
    }
    @GetMapping("/state")
    public AgentChatService.Snapshot state(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestParam(required=false)Long afterSeq,@RequestParam(required=false)Long historyEpoch){return chat.state(creator.id(),id,afterSeq,historyEpoch);}
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String,String>> failure(DomainException error){
        int status=error.code().contains("NOT_FOUND")?404:error.code().contains("CONFLICT")||error.code().contains("IN_PROGRESS")?409:error.code().equals("AGENT_BUSY")||error.code().contains("RATE_LIMITED")||error.code().contains("BUDGET_EXCEEDED")?429:error.code().contains("ADMISSION_")||error.code().contains("DEADLINE")?503:400;
        var response=ResponseEntity.status(status);if(status==429||status==503)response.header("Retry-After","60");
        return response.body(Map.of("code",error.code(),"message",error.getMessage()));
    }
}
