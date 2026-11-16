package com.qingfeng.music.server.config;

import com.qingfeng.music.agent.api.AgentApplicationActions.Capability;
import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.infrastructure.provider.QwenAgentChatModel;
import com.qingfeng.music.infrastructure.provider.QwenReferenceAnalyst;
import com.qingfeng.music.infrastructure.provider.QwenIntentClassifier;
import com.qingfeng.music.infrastructure.provider.ResilientAgentChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentConversationModelConfigurationTest {
    @Test void defaultPlannerUsesQwenWithReadOnlyOfflineFallback() {
        var model = new AgentConversationChatConfiguration().agentChatModel(new MockEnvironment());
        assertInstanceOf(ResilientAgentChatModel.class, model);
        var caps = List.of(new Capability("get_credits", "积分", "{}", "read"));
        assertEquals("get_credits", model.respond(new AgentChatModel.Context("查看积分", "zh-CN", List.of(), null, null, caps, null)).action());
        assertEquals("chat", model.respond(new AgentChatModel.Context("生成歌曲", "zh-CN", List.of(), null, null, caps, null)).action());
    }

    @Test void agentAndReferenceAnalysisUseSharedQwenSettings() {
        var env = new MockEnvironment()
            .withProperty("qingfeng.providers.deepseek.api-key", "obsolete-key")
            .withProperty("qingfeng.providers.qwen.api-key", "qwen-only-key")
            .withProperty("qingfeng.providers.qwen.model", "qwen3.8-max")
            .withProperty("qingfeng.providers.qwen.base-url", "https://maas.qianwenaiapi.com/compatible-mode/v1");
        var wrapped = assertInstanceOf(ResilientAgentChatModel.class,
            new AgentConversationChatConfiguration().agentChatModel(env));
        var planner = assertInstanceOf(QwenAgentChatModel.class, ReflectionTestUtils.getField(wrapped, "delegate"));
        var analyst = assertInstanceOf(QwenReferenceAnalyst.class,
            new AgentConfiguration().referenceAnalyst(env));
        var classifier = assertInstanceOf(QwenIntentClassifier.class,
            new AgentChatConfiguration().intentClassifier(env));
        var endpoint = URI.create("https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions");
        for (var provider : List.of(planner, analyst, classifier)) {
            assertEquals("qwen-only-key", ReflectionTestUtils.getField(provider, "key"));
            assertEquals("qwen3.8-max", ReflectionTestUtils.getField(provider, "model"));
            assertEquals(endpoint, ReflectionTestUtils.getField(provider, "endpoint"));
        }
    }

    @Test void invalidCircuitSettingsFailConfigurationBeforeAnyProviderRequest() {
        var config = new AgentConversationChatConfiguration();
        assertThrows(IllegalArgumentException.class, () -> config.agentChatModel(new MockEnvironment()
                .withProperty("qingfeng.agent.intent.circuit.failure-threshold", "0")));
        assertThrows(IllegalArgumentException.class, () -> config.agentChatModel(new MockEnvironment()
                .withProperty("qingfeng.agent.intent.circuit.cooldown-seconds", "301")));
    }
}
