package com.qingfeng.music.server;

import com.qingfeng.music.settings.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:inspiration_presets;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "qingfeng.generation.enabled=true", "qingfeng.generation.scheduler-enabled=false"})
@ActiveProfiles("test")
@Import(InspirationMusicPresetsIntegrationTest.Offline.class)
class InspirationMusicPresetsIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline { @Bean @Primary Provider inspirationProvider() { return new Provider(); } }
    static class Provider implements MusicProvider {
        Request received;
        public String name() { return "kie-suno"; }
        public boolean isConfigured() { return true; }
        public Submission submit(Request request, String key) { received = request; return new Submission("offline-" + key); }
        public Result query(String task) { return new Result(task, GenerationStatus.RUNNING, null); }
    }
    @Autowired MusicSettings settings;
    @Autowired IdentityApi identities;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired GenerationApi generation;
    @Autowired ExecutionRepository execution;
    @Autowired JdbcTemplate jdbc;
    @Autowired Provider provider;
    @BeforeEach void clearPending() { jdbc.update("UPDATE generation_task SET status='FAILED' WHERE status<>'SUCCEEDED'"); jdbc.update("DELETE FROM model_usage_reservation"); provider.received = null; }

    @Test void allFiveBriefsResolveToTheirFullPromptAndSurviveSettingsConfirmation() {
        assertEquals(List.of("流行抒情", "流行摇滚", "蓝调R&B", "华语民谣", "中国风"), InspirationMusicPresets.all().stream().map(InspirationMusicPresets.Preset::label).toList());
        for (var preset : InspirationMusicPresets.all()) {
            String owner = identities.create().creatorId();
            var work = works.create(owner, new WorkApi.Draft("", "", "创作一首关于海风和旅行的歌", "", preset.label(), "海风", preset.voice()));
            var direction = settings.get(owner, work.id()).direction();
            assertNotNull(direction, preset.label()); assertEquals(preset.prompt(), direction.style());
            assertTrue(direction.style().length() < 1000); assertEquals(preset.voice(), direction.preferences().vocalGender());
            assertEquals(0, works.get(owner, work.id()).version());
            var saved = settings.save(owner, work.id(), 0, direction.preferences());
            assertEquals(direction.style(), saved.direction().style());
            assertEquals(direction.style(), settings.get(owner, work.id()).direction().style());
        }
    }
    @Test void taskFreezesThePromptAndWorkerCannotUseALaterStyleChoice() {
        String owner = identities.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("", "", "想念一个安静的雨夜", "", "流行抒情", "雨夜", "FEMALE"));
        var revision = lyrics.save(owner, work.id(), work.version(), "雨夜", "[Verse]\n雨落在窗前\n[Chorus]\n我慢慢向前");
        lyrics.confirm(owner, work.id(), revision.id());
        var task = generation.submit(owner, work.id(), revision.id(), UUID.randomUUID().toString());
        var frozen = settings.task(owner, work.id(), task.id()).orElseThrow();
        String expected = "Mandopop, gentle piano solo and soft cinematic strings, subtle stereo delay on the vocal and piano, slow unhurried tempo, clear gentle female vocal with intimate verses and softly lifted chorus";
        assertEquals(expected, frozen.style());
        works.update(owner, work.id(), 0, new WorkApi.Draft("", "", work.story(), "", "流行摇滚", "雨夜", "MALE"));
        var worker = new GenerationWorker(execution, provider, url -> { throw new AssertionError("No download expected"); }, (id,w,in) -> { throw new AssertionError("No archive expected"); }, Clock.fixed(task.createdAt().plusSeconds(6), ZoneOffset.UTC), Duration.ofMinutes(2), 2).withSettings(settings);
        assertTrue(worker.runOnce()); assertNotNull(provider.received);
        assertTrue(provider.received.preparedStyle()); assertEquals(expected, provider.received.style()); assertEquals("FEMALE", provider.received.vocalGender());
        assertEquals(frozen, settings.task(owner, work.id(), task.id()).orElseThrow());
    }
    @Test void changingTheVoiceRemovesConflictingGenderButKeepsTheArrangement() {
        for (var preset : InspirationMusicPresets.all()) {
            String male = settings.preview(preset.preferences("MALE")).style();
            assertFalse(male.toLowerCase(Locale.ROOT).contains("female vocal"));
            assertTrue(male.matches("(?is).*(?<![a-z])male (?:lead )?vocals?.*"));
            String female = settings.preview(preset.preferences("FEMALE")).style();
            assertTrue(female.matches("(?is).*female (?:lead )?vocals?.*"));
            String auto = settings.preview(preset.preferences("AUTO")).style();
            assertFalse(auto.matches("(?is).*(?<![a-z])(?:female|male) vocals?.*"));
        }
    }
    @Test void changingMusicTagsOverridesThePresetWhileExclusionsRemainIndependent() {
        var preset = InspirationMusicPresets.find("流行抒情").orElseThrow();
        var choices = new HashMap<>(preset.choices()); choices.put("exclude", List.of("no_rap"));
        var excluded = settings.preview(new MusicSettings.Preferences(1, null, choices, "FEMALE", preset.label(), "刺耳的高音"));
        assertEquals(preset.prompt(), excluded.style()); assertTrue(excluded.negativeTags().contains("rap")); assertTrue(excluded.negativeTags().contains("刺耳的高音"));
        choices.put("genre", List.of("rock"));
        var changed = settings.preview(new MusicSettings.Preferences(1, null, choices, "FEMALE", preset.label(), ""));
        assertNotEquals(preset.prompt(), changed.style()); assertTrue(changed.style().contains("pop rock"));
    }
}
