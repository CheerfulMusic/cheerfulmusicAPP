package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.QwenLyricsWriter;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LyricsProviderConfigurationTest {
    private final LyricsCompositionConfiguration config = new LyricsCompositionConfiguration();

    @Test void explicitOpenAiUsesItsOwnKeyAndInspirationKeepsItsQwenConfiguration() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "OpenAI")
            .withProperty("qingfeng.providers.openai.api-key", "offline-openai-key")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key")
            .withProperty("qingfeng.providers.qwen.model", "offline-qwen-model");
        assertTrue(assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
        assertTrue(config.inspirationRewriter(env).isConfigured());
        assertEquals("offline-qwen-model", RewriteProviderSettings.settings(env).model());
    }
    @Test void openAiEnvironmentKeyDoesNotBorrowQwenCredentials() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai").withProperty("OPENAI_API_KEY", "offline-key");
        assertTrue(config.lyricsWriter(env).isConfigured());
        env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-key");
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env));
        assertFalse(config.lyricsWriter(env).isConfigured());
    }
    @Test void defaultUsesOpenAiAndExplicitQwenRoute() {
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(new MockEnvironment()));
        assertTrue(config.lyricsWriter(new MockEnvironment().withProperty("qingfeng.lyrics.provider", "qwen")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-key")).isConfigured());
    }
    @Test void customCreationUsesOpenAiWithQwenBackupIndependentOfSharedConfiguration() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "qwen")
            .withProperty("qingfeng.lyrics.qwen-fallback-enabled", "false");
        var custom = assertInstanceOf(FallbackLyricsWriter.class, config.customLyricsWriter(env));
        assertEquals("OPENAI", custom.source());
        env.withProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key");
        assertTrue(config.customLyricsWriter(env).isConfigured());
        assertTrue(config.customLyricsWriter(new MockEnvironment()
            .withProperty("OPENAI_API_KEY", "offline-openai-key")).isConfigured());
        assertInstanceOf(QwenLyricsWriter.class, config.lyricsWriter(env));
    }
    @Test void enablingFallbackCanUseQwenWhenTheOpenAiKeyIsMissing() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai")
            .withProperty("qingfeng.lyrics.qwen-fallback-enabled", "true")
            .withProperty("qingfeng.providers.openai.proxy-url", "http://127.0.0.1:7890")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-key");
        assertTrue(assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
    }
    @Test void legacyProviderNameCannotRouteToOldEndpoint() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "deepseek")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-key");
        assertThrows(IllegalArgumentException.class, () -> config.lyricsWriter(env));
    }
    @Test void invalidProxyConfigurationDoesNotEchoCredentials() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai")
            .withProperty("qingfeng.providers.openai.proxy-url", "http://user:secret@localhost:7890");
        var error = assertThrows(IllegalArgumentException.class, () -> config.lyricsWriter(env));
        assertFalse(error.getMessage().contains("secret"));
    }
    @Test void misspelledProviderFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> config.lyricsWriter(
            new MockEnvironment().withProperty("qingfeng.lyrics.provider", "gpt-misspelled")));
    }
}
