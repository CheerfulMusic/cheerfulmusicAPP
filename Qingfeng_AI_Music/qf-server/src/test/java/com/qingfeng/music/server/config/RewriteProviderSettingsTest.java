package com.qingfeng.music.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI 改写模型配置回归：默认 qwen3.8-flash，密钥不写入仓库、也不暴露在配置对象里。 */
class RewriteProviderSettingsTest {

    @Test
    void defaultsToQwenFlashAndDashscopeCompatibleEndpoint() {
        var s = RewriteProviderSettings.settings(new MockEnvironment());
        assertEquals("qwen3.8-flash", s.model());
        assertEquals("https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions", s.endpoint());
    }

    @Test
    void baseUrlWithoutChatCompletionsIsNormalized() {
        var env = new MockEnvironment().withProperty(
            "qingfeng.providers.qwen.base-url", "https://maas.qianwenaiapi.com/compatible-mode/v1/");
        assertEquals("https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions",
            RewriteProviderSettings.settings(env).endpoint());
    }

    @Test
    void modelCanBeOverriddenByPrivateConfig() {
        var env = new MockEnvironment().withProperty("qingfeng.providers.qwen.model", "qwen3.8-max");
        assertEquals("qwen3.8-max", RewriteProviderSettings.settings(env).model());
    }

    @Test
    void privateConfigKeyIsDetectedWithoutBeingCarriedBySettings() {
        var env = new MockEnvironment().withProperty("qingfeng.providers.qwen.api-key", "private-value");
        var s = RewriteProviderSettings.settings(env);
        assertTrue(s.keyConfigured());
        // Settings 只有 model/endpoint/boolean，任何字段都不可能带出密钥值。
        assertEquals(3, s.getClass().getRecordComponents().length);
        for (var component : s.getClass().getRecordComponents()) {
            assertTrue(component.getType() == String.class || component.getType() == boolean.class);
        }
    }
}
