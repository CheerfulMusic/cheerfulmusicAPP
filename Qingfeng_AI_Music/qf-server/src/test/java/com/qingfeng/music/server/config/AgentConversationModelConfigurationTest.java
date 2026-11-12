package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.AgentApplicationActions.Capability;
import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.infrastructure.provider.ResilientAgentChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentConversationModelConfigurationTest {
    @Test void defaultPlannerUsesExistingDeepSeekWithReadOnlyOfflineFallback() {
        var model = new AgentConversationChatConfiguration().agentChatModel(new MockEnvironment());
        assertInstanceOf(ResilientAgentChatModel.class, model);
        var caps = List.of(new Capability("get_credits", "积分", "{}", "read"));
        assertEquals("get_credits", model.respond(new AgentChatModel.Context("查看积分", "zh-CN", List.of(), null, null, caps, null)).action());
        assertEquals("chat", model.respond(new AgentChatModel.Context("生成歌曲", "zh-CN", List.of(), null, null, caps, null)).action());
    }

    @Test void invalidCircuitSettingsFailConfigurationBeforeAnyProviderRequest() {
        var config = new AgentConversationChatConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.agentChatModel(new MockEnvironment()
                .withProperty("qingfeng.agent.intent.circuit.failure-threshold", "0")));
        assertThrows(IllegalArgumentException.class, () -> config.agentChatModel(new MockEnvironment()
                .withProperty("qingfeng.agent.intent.circuit.cooldown-seconds", "301")));
    }
}
