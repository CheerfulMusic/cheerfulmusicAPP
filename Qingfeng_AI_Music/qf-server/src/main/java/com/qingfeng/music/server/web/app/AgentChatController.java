package com.qingfeng.music.server.web.app;

import com.qingfeng.music.server.agent.AgentChatService;
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
    public record Request(String text,String requestKey,String locale) {}
    private final AgentChatService chat;
    public AgentChatController(AgentChatService chat){this.chat=chat;}
    @PostMapping("/messages")
    public AgentChatService.Snapshot send(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,@RequestBody Request request){
        return chat.send(creator.id(),id,request.text(),request.requestKey(),request.locale());
    }
    @GetMapping("/state")
    public AgentChatService.Snapshot state(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id){return chat.state(creator.id(),id);}
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String,String>> failure(DomainException error){
        int status=error.code().contains("NOT_FOUND")?404:error.code().contains("CONFLICT")||error.code().contains("IN_PROGRESS")?409:400;
        return ResponseEntity.status(status).body(Map.of("code",error.code(),"message",error.getMessage()));
    }
}
