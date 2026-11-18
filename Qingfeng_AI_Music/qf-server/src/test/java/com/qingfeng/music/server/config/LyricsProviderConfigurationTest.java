package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.infrastructure.provider.QwenLyricsWriter;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.lyrics.application.FallbackLyricsWriter;
import com.qingfeng.music.server.web.publicapi.SystemInfoController;
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
        assertTrue(assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
        assertTrue(config.inspirationRewriter(env).isConfigured());
        assertEquals("offline-qwen-model", RewriteProviderSettings.settings(env).model());
    }

    @Test void reportsPartialRewriteCapabilityFromTheQwenConfiguration() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.enabled", "true")
            .withProperty("qingfeng.lyrics.qwen-fallback-enabled", "false")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key");
        env.setActiveProfiles("test");
        var system = new SystemInfoController(org.mockito.Mockito.mock(MusicProvider.class), env,
            config.lyricsWriter(env), config.customLyricsWriter(env));
        assertFalse(system.info().capabilities().lyricsGeneration());
        assertTrue(system.info().capabilities().customLyricsGeneration());
        assertTrue(system.info().capabilities().lyricSelectionRewrite());
        env.setProperty("qingfeng.lyrics.enabled", "false");
        assertFalse(system.info().capabilities().lyricSelectionRewrite());
    }

    @Test void openAiEnvironmentKeyDoesNotBorrowQwenCredentials() {
        var env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai").withProperty("OPENAI_API_KEY", "offline-key");
        assertTrue(config.lyricsWriter(env).isConfigured());
        env = new MockEnvironment().withProperty("qingfeng.lyrics.provider", "openai")
            .withProperty("qingfeng.lyrics.qwen-fallback-enabled", "false")
            .withProperty("qingfeng.providers.qwen.api-key", "offline-key");
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env));
        assertFalse(config.lyricsWriter(env).isConfigured());
    }
    @Test void defaultUsesOpenAiAndExplicitQwenRoute() {
        assertEquals("OPENAI", config.lyricsWriter(new MockEnvironment()).source());
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
    @Test void quickAndAgentLyricsUseConfiguredQwenBackupByDefault() {
        var env = new MockEnvironment()
            .withProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key");
        assertTrue(assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
        env.setProperty("qingfeng.providers.openai.api-key", "offline-openai-key");
        assertTrue(assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env)).isConfigured());
    }
    @Test void explicitFallbackOptOutKeepsTheOpenAiRoute() {
        var env = new MockEnvironment()
            .withProperty("qingfeng.providers.openai.api-key", "offline-openai-key");
        assertEquals("OPENAI", config.lyricsWriter(env).source());
        env.setProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key");
        env.setProperty("qingfeng.lyrics.qwen-fallback-enabled", "false");
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env));
    }
    @Test void packagedDefaultsAlsoEnableFallback() throws Exception {
        var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var sources = loader.load("application", new org.springframework.core.io.ClassPathResource("application.yml"));
        var env = new MockEnvironment();
        sources.forEach(source -> env.getPropertySources().addLast(source));
        env.setProperty("qingfeng.providers.qwen.api-key", "offline-qwen-key");
        assertTrue(env.getProperty("qingfeng.lyrics.qwen-fallback-enabled", Boolean.class));
        assertInstanceOf(FallbackLyricsWriter.class, config.lyricsWriter(env));
        env.setProperty("qingfeng.lyrics.qwen-fallback-enabled", "false");
        assertInstanceOf(OpenAiLyricsWriter.class, config.lyricsWriter(env));
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
