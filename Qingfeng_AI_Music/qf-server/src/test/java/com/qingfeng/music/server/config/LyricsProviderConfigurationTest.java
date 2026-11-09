package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
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
    @Test void misspelledProviderFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> config.lyricsWriter(
            new MockEnvironment().withProperty("qingfeng.lyrics.provider", "gpt-misspelled")));
    }
}
