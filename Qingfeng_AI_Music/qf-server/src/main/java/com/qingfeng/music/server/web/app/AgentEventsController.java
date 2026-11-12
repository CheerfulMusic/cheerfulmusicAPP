package com.qingfeng.music.server.web.app;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.agent.AgentStateEventStream;
import com.qingfeng.music.server.security.CreatorPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.agent.chat.enabled",havingValue="true")
@RequestMapping("/api/app/agent/conversations/{id}")
public class AgentEventsController {
    private final AgentStateEventStream events;
    public AgentEventsController(AgentStateEventStream events){this.events=events;}
    @GetMapping(value="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String id,
            @RequestParam(required=false)Long afterSeq,@RequestParam(required=false)Long historyEpoch,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
        return events.open(creator.id(),id,afterSeq,historyEpoch);
    }
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String,String>> failure(DomainException error){
        int status=error.code().contains("NOT_FOUND")?404:error.code().equals("AGENT_BUSY")?429:503;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("code",error.code(),"message",error.getMessage()));
    }
}
