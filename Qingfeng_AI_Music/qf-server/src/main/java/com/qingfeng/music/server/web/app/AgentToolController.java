package com.qingfeng.music.server.web.app;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.ToolResult;
import com.qingfeng.music.agent.application.AgentToolArgs;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.security.CreatorPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * W2 同步工具端点。与 W1 的 AgentConversationController 共用开关 qingfeng.agent.chat.enabled：
 * 默认 false 时不注册 → 404（满足「新增端点默认不可达」）。
 * 请求体为工具参数的扁平 JSON（与 AgentToolArgs.serialize 输出同源，列表字段以 \u0001 拼接）。
 * requestKey 优先取请求体字段；缺失时服务端生成，保证幂等语义仍由 dispatcher 维护。
 */
@RestController
@RequestMapping("/api/app/agent/tools")
@ConditionalOnProperty(name = "qingfeng.agent.chat.enabled", havingValue = "true")
public class AgentToolController {

    private final AgentToolDispatcher dispatcher;

    @Autowired
    public AgentToolController(AgentToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @GetMapping("/current")
    public ToolResult current(@AuthenticationPrincipal CreatorPrincipal creator) {
        return dispatcher.dispatch(AgentTool.GET_CURRENT_SONG, new com.qingfeng.music.agent.api.AgentTool.GetCurrentSongArgs(),
                creator.id(), UUID.randomUUID().toString());
    }

    @PostMapping("/{tool}")
    public ToolResult call(@AuthenticationPrincipal CreatorPrincipal creator,
                           @PathVariable String tool, @RequestBody String body) {
        AgentTool target = AgentTool.fromId(tool);
        if (target == null) {
            throw new DomainException("UNKNOWN_TOOL", "未知工具：" + tool);
        }
        Map<String, Object> parsed = AgentJson.read(body == null || body.isBlank() ? "{}" : body);
        String requestKey = asString(parsed.get("requestKey"));
        if (requestKey == null || requestKey.isBlank()) {
            requestKey = UUID.randomUUID().toString();
        }
        Map<String, Object> argsMap = new LinkedHashMap<>(parsed);
        argsMap.remove("requestKey");
        Object args = AgentToolArgs.deserialize(target, AgentJson.write(argsMap));
        return dispatcher.dispatch(target, args, creator.id(), requestKey);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
