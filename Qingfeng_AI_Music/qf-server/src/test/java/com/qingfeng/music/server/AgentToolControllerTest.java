package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.api.ToolResult;
import com.qingfeng.music.agent.application.AgentToolDispatcher;
import com.qingfeng.music.agent.application.AgentToolRegistry;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.app.AgentToolController;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W2 同步工具端点（纯单测，不依赖 Spring/Mockito）：
 * - GET current → 分发 get_current_song；
 * - POST set_style → 参数被反序列化为 SetStyleArgs 且 requestKey 透传；
 * - 缺 requestKey → 服务端生成；
 * - 未知工具 → 抛 UNKNOWN_TOOL（不进入分发器）；
 * - 会话冲突 DomainException 不被控制器捕获，交由 GlobalExceptionHandler 映射为 HTTP 409
 *   （映射逻辑见 GlobalExceptionHandler：CONFLICT → HttpStatus.CONFLICT）。
 */
class AgentToolControllerTest {

    private static final class RecordingDispatcher extends AgentToolDispatcher {
        AgentTool tool;
        Object args;
        String creatorId;
        String requestKey;
        int calls;
        boolean throwConflict;

        RecordingDispatcher() {
            super(new AgentToolRegistry(), Map.of(), null, null);
        }

        @Override
        public ToolResult dispatch(AgentTool tool, Object args, String creatorId, String requestKey) {
            if (throwConflict) {
                throw new DomainException(ConversationService.CONFLICT, "会话状态已更新，请重新读取后再试。");
            }
            this.tool = tool;
            this.args = args;
            this.creatorId = creatorId;
            this.requestKey = requestKey;
            this.calls++;
            return ToolResult.ok("m1", sampleCard());
        }
    }

    private static SongCard sampleCard() {
        return new SongCard("W1", "歌", "温暖民谣", "", "", "READY", null, 0L);
    }

    private static CreatorPrincipal creator() { return new CreatorPrincipal("creator-a"); }

    @Test
    void currentDispatchesGetCurrentSong() {
        var d = new RecordingDispatcher();
        var controller = new AgentToolController(d);
        ToolResult result = controller.current(creator());
        assertEquals(ToolResult.OK, result.status());
        assertEquals(AgentTool.GET_CURRENT_SONG, d.tool);
        assertEquals("creator-a", d.creatorId);
        assertNotNull(d.requestKey);
    }

    @Test
    void setStyleParsesArgsAndPassesRequestKey() {
        var d = new RecordingDispatcher();
        var controller = new AgentToolController(d);
        ToolResult result = controller.call(creator(), "set_style",
                "{\"style\":\"温暖民谣\",\"mood\":\"开心\",\"bpm\":120,\"requestKey\":\"rk-1\"}");
        assertEquals(ToolResult.OK, result.status());
        assertEquals(AgentTool.SET_STYLE, d.tool);
        assertTrue(d.args instanceof AgentTool.SetStyleArgs);
        assertEquals("温暖民谣", ((AgentTool.SetStyleArgs) d.args).style());
        assertEquals("rk-1", d.requestKey, "请求体里的 requestKey 必须透传，保证幂等语义");
    }

    @Test
    void missingRequestKeyIsGeneratedServerSide() {
        var d = new RecordingDispatcher();
        var controller = new AgentToolController(d);
        controller.call(creator(), "set_style", "{\"style\":\"摇滚\"}");
        assertNotNull(d.requestKey);
        assertFalse(d.requestKey.isBlank(), "缺 requestKey 时服务端必须生成，否则幂等键丢失");
    }

    @Test
    void unknownToolThrowsUnknownToolWithoutDispatching() {
        var d = new RecordingDispatcher();
        var controller = new AgentToolController(d);
        DomainException ex = assertThrows(DomainException.class,
                () -> controller.call(creator(), "does_not_exist", "{}"));
        assertEquals("UNKNOWN_TOOL", ex.code());
        assertEquals(0, d.calls, "未知工具不应进入分发器");
    }

    @Test
    void conflictPropagatesForHttp409Mapping() {
        // 控制器不捕获 DomainException；stale revision 由全局处理器映射为 409。
        var d = new RecordingDispatcher();
        d.throwConflict = true;
        var controller = new AgentToolController(d);
        DomainException ex = assertThrows(DomainException.class,
                () -> controller.call(creator(), "set_style", "{\"style\":\"民谣\",\"requestKey\":\"rk-9\"}"));
        assertEquals(ConversationService.CONFLICT, ex.code());
    }
}
