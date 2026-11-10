package com.qingfeng.music.server;

import com.qingfeng.music.creation.QuickCreationService;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.lyrics.application.LyricsWriter;
import com.qingfeng.music.server.config.QuickCreationConfiguration;
import com.qingfeng.music.server.web.publicapi.SystemInfoController;
import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuickSchedulerConfigurationTest {
    private final MusicProvider music = mock(MusicProvider.class);
    private final LyricsWriter writer = mock(LyricsWriter.class);
    private ApplicationContextRunner context(String... properties) {
        when(music.isConfigured()).thenReturn(true); when(writer.isConfigured()).thenReturn(true);
        return new ApplicationContextRunner().withUserConfiguration(QuickCreationConfiguration.class)
                .withPropertyValues("spring.profiles.active=test", "qingfeng.quick.enabled=true",
                        "qingfeng.generation.enabled=true", "qingfeng.lyrics.enabled=true")
                .withPropertyValues(properties)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(WorkApi.class, () -> mock(WorkApi.class))
                .withBean(LyricsApi.class, () -> mock(LyricsApi.class))
                .withBean(LyricsCompositionService.class, () -> mock(LyricsCompositionService.class))
                .withBean(GenerationApi.class, () -> mock(GenerationApi.class))
                .withBean(LyricsWriter.class, () -> writer).withBean(MusicProvider.class, () -> music);
    }
    @Test void dailyMainModeDoesNotStartQuickWorkersByDefault() {
        context("qingfeng.generation.scheduler-enabled=false").run(c -> {
            assertNull(c.getStartupFailure()); assertFalse(c.containsBean("quickRunner"));
            assertFalse(new SystemInfoController(music,c.getEnvironment(),writer).info().capabilities().quickCreation());
        });
    }
    @Test void localQuickWorkerCanRunWithoutTakingOverMusicQueues() {
        context("qingfeng.generation.scheduler-enabled=false", "qingfeng.quick.scheduler-enabled=true").run(c -> {
            assertNull(c.getStartupFailure()); assertTrue(c.containsBean("quickRunner"));
            assertFalse(c.getEnvironment().getProperty("qingfeng.generation.scheduler-enabled",Boolean.class,true));
            assertTrue(new SystemInfoController(music,c.getEnvironment(),writer).info().capabilities().quickCreation());
            assertNotNull(c.getBean(QuickCreationService.class));
        });
    }
    @Test void explicitQuickOffAlsoWorksWithMusicWorkerOn() {
        context("qingfeng.generation.scheduler-enabled=true", "qingfeng.quick.scheduler-enabled=false").run(c -> {
            assertNull(c.getStartupFailure()); assertFalse(c.containsBean("quickRunner"));
            assertFalse(new SystemInfoController(music,c.getEnvironment(),writer).info().capabilities().quickCreation());
        });
    }
    @Test void productionDefaultsStillStartQuickRunnerAlongsideMusic() {
        context("qingfeng.generation.scheduler-enabled=true").run(c -> {
            assertNull(c.getStartupFailure()); assertTrue(c.containsBean("quickRunner"));
            assertTrue(new SystemInfoController(music,c.getEnvironment(),writer).info().capabilities().quickCreation());
        });
    }
}
