package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class LyricsProviderConfigurationTest {
    private final LyricsCompositionConfiguration config = new LyricsCompositionConfiguration();

    @Test void explicitOpenAiUsesItsOwnKeyAndLeavesInspirationOnDeepSeek() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "OpenAI")
            .withProperty("qingfeng.providers.openai.api-key", "offline-openai-key");
        assertTrue(assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
        assertFalse(config.inspirationRewriter(env).isConfigured());
    }
    @Test void supportsEnvironmentKeyWithoutFallingBackToDeepSeek() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai").withProperty("OPENAI_API_KEY", "offline-key");
        assertTrue(config.lyricsWriter(env).isConfigured());
        env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai").withProperty("DEEPSEEK_API_KEY", "offline-key");
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env));
        assertFalse(config.lyricsWriter(env).isConfigured());
    }
    @Test void existingDeploymentsAndExplicitRollbackKeepDeepSeek() {
        assertInstanceOf(DeepSeekLyricsWriter.class, config.lyricsWriter(new MockEnvironment()));
        assertTrue(config.lyricsWriter(new MockEnvironment().withProperty("qingfeng.lyrics.provider", "deepseek")
            .withProperty("DEEPSEEK_API_KEY", "offline-key")).isConfigured());
    }
    @Test void customCreationUsesOpenAiWithDeepSeekBackupIndependentOfSharedConfiguration() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "deepseek")
            .withProperty("qingfeng.lyrics.deepseek-fallback-enabled", "false");
        var custom = assertInstanceOf(FallbackLyricsWriter.class, config.customLyricsWriter(env));
        assertEquals("OPENAI", custom.source());
        assertFalse(custom.isConfigured());
        env.withProperty("DEEPSEEK_API_KEY", "offline-deepseek-key");
        assertTrue(config.customLyricsWriter(env).isConfigured());
        assertTrue(config.customLyricsWriter(new MockEnvironment()
            .withProperty("OPENAI_API_KEY", "offline-openai-key")).isConfigured());
        assertInstanceOf(DeepSeekLyricsWriter.class, config.lyricsWriter(env));
    }
    @Test void enablingFallbackCanUseDeepSeekWhenTheOpenAiKeyIsMissing() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai")
            .withProperty("qingfeng.lyrics.deepseek-fallback-enabled", "true")
            .withProperty("qingfeng.providers.openai.proxy-url", "http://127.0.0.1:7890")
            .withProperty("DEEPSEEK_API_KEY", "offline-key");
        assertTrue(assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
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
