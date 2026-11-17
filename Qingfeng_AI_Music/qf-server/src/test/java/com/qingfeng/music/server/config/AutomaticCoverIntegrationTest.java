package com.qingfeng.music.server.config;

import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.generation.application.ExecutionRepository;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.work.api.WorkApi;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "qingfeng.cover.automatic.enabled=true", "qingfeng.cover.automatic.scheduler-enabled=false",
    "qingfeng.providers.ark.api-key=unused-test-key", "qingfeng.media.storage=local",
    "qingfeng.generation.enabled=true",
    "qingfeng.cover.directory=./target/automatic-cover-test",
    "spring.datasource.url=jdbc:h2:mem:auto-cover;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(AutomaticCoverIntegrationTest.Offline.class)
class AutomaticCoverIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Offline {
        @Bean @Primary MusicProvider offlineMusic() {
            return new MusicProvider() {
                public String name() { return "offline-cover-test"; }
                public boolean isConfigured() { return true; }
                public Submission submit(Request request,String key) { throw new AssertionError("Unexpected music request"); }
                public Result query(String id) { throw new AssertionError("Unexpected music query"); }
            };
        }
    }
    @Autowired JdbcTemplate db;
    @Autowired JdbcAutomaticCoverJobs jobs;
    @Autowired ExecutionRepository executions;
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired CoverService covers;
    @Autowired MediaService media;
    @Autowired GenerationApi generation;

    record Fixture(String owner, String work, String task, String token, String asset) {}

    @org.junit.jupiter.api.BeforeEach void isolateCoverJobs() {
        db.update("""
                UPDATE task_event_outbox SET state='COVER_SKIPPED',last_error='TEST_CLEANUP',
                    lease_token=NULL,lease_until=NULL WHERE topic='music.cover'
                """);
    }

    @Test void submissionStartsCoverBeforeAudioSavedByAnotherDeployment() {
        String owner = identity.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("朋友", "生日", "故事", "祝福", "民谣", "歌曲"));
        var revision = lyrics.save(owner, work.id(), 0, "歌名", "雨夜的末班车");
        lyrics.confirm(owner, work.id(), revision.id());
        String key = uuid();
        var task = generation.submit(owner, work.id(), revision.id(), key);
        assertEquals(task.id(), generation.submit(owner, work.id(), revision.id(), key).id());
        assertEquals("COVER_QUEUED", eventState(task.id()));
        assertTrue(covers.state(owner, work.id()).automaticPending());
        var claim = jobs.claim(10000, Instant.now()).orElseThrow();
        assertEquals(task.id(), claim.task());
        assertEquals("COVER_QUEUED", claim.state());
        assertNull(db.queryForObject("SELECT asset_id FROM generation_task WHERE id=?", String.class, task.id()));
        String asset = media.store(task.id(), work.id(), new ByteArrayInputStream(wav()));
        // Simulate an older remote worker which only archives audio and completes the music task.
        db.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?", asset, task.id());
        assertTrue(covers.state(owner, work.id()).automaticPending());
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE topic='music.cover' AND event_key=?", Integer.class, task.id()));
        jobs.rejected(claim, "TEST_COMPLETE");
    }

    @Test void coverCanFinishBeforeMusicAndCompletionDoesNotGenerateItAgain() throws Exception {
        var song = fixture();
        jobs.enqueue(song.task);
        var calls = new AtomicInteger();
        byte[] image = image();
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            calls.incrementAndGet();
            return "https://example.test/result.jpg";
        }, covers, url -> new ByteArrayInputStream(image), 10000);
        runner.run();
        runner.run();
        String cover = covers.current(song.work);
        assertNotNull(cover);
        assertEquals("SAVING", db.queryForObject("SELECT status FROM generation_task WHERE id=?", String.class, song.task));
        assertEquals("COVER_DONE", state(song));
        complete(song);
        runner.run();
        assertEquals(cover, covers.current(song.work));
        assertEquals(1, calls.get());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE topic='music.cover' AND event_key=?", Integer.class, song.task));
    }

    @Test void musicCanCompleteWhileTheImageProviderIsStillRunning() throws Exception {
        var song = fixture();
        jobs.enqueue(song.task);
        var started = new CountDownLatch(1);
        var finishImage = new CountDownLatch(1);
        byte[] image = image();
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            started.countDown();
            if (!finishImage.await(10, TimeUnit.SECONDS)) throw new AssertionError("Music did not complete concurrently");
            return "https://example.test/result.jpg";
        }, covers, url -> new ByteArrayInputStream(image), 10000);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var drawing = pool.submit(runner::run);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            complete(song);
            assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM generation_task WHERE id=?", String.class, song.task));
            try (var stream = media.open(song.owner, song.work, song.asset).source().open()) {
                assertArrayEquals(wav(), stream.readAllBytes());
            }
            assertNull(covers.current(song.work));
            finishImage.countDown();
            drawing.get(5, TimeUnit.SECONDS);
            runner.run();
            assertNotNull(covers.current(song.work));
        } finally {
            finishImage.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void manualCoverOrDefaultSelectedDuringImageGenerationIsKept(boolean restoreDefault) throws Exception {
        var song = fixture();
        jobs.enqueue(song.task);
        String manual = restoreDefault ? null : covers.upload(song.owner, song.work, new ByteArrayInputStream(image())).id();
        String release = release(song, song.asset);
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            covers.select(song.owner, song.work, manual, 0);
            return "https://example.test/result.jpg";
        }, covers, url -> { throw new AssertionError("Manual cover must take precedence"); }, 10000);
        runner.run();
        runner.run();
        complete(song);
        runner.run();
        assertEquals(manual, covers.current(song.work));
        assertEquals(1, covers.state(song.owner, song.work).version());
        assertFalse(covers.state(song.owner, song.work).automaticPending());
        assertEquals(manual, db.queryForObject("SELECT cover_id FROM artist_song_release WHERE id=?", String.class, release));
        assertEquals("COVER_SKIPPED", state(song));
    }

    @Test void existingManualCoverDoesNotStartAutomaticDrawing() throws Exception {
        var song = fixture();
        String manual = covers.upload(song.owner, song.work, new ByteArrayInputStream(image())).id();
        covers.select(song.owner, song.work, manual, 0);
        jobs.enqueue(song.task);
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE topic='music.cover' AND event_key=?", Integer.class, song.task));
        assertEquals(manual, covers.current(song.work));
    }

    @ParameterizedTest @ValueSource(strings = {"FAILED", "CANCELLED"})
    void musicFailureDuringImageGenerationDoesNotApplyTheLateImage(String status) {
        var song = fixture();
        jobs.enqueue(song.task);
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            db.update("UPDATE generation_task SET status=? WHERE id=?", status, song.task);
            return "https://example.test/result.jpg";
        }, covers, url -> { throw new AssertionError("Stopped music must not apply a late cover"); }, 10000);
        runner.run();
        runner.run();
        assertNull(covers.current(song.work));
        assertEquals("COVER_SKIPPED", state(song));
        assertFalse(covers.state(song.owner, song.work).automaticPending());
    }

    @Test void legacyWaitingCoverStartsWhileMusicIsStillRunning() {
        var song = fixture();
        jobs.enqueue(song.task);
        db.update("UPDATE task_event_outbox SET state='COVER_WAIT' WHERE topic='music.cover' AND event_key=?", song.task);
        assertTrue(covers.state(song.owner, song.work).automaticPending());
        var claim = jobs.claim(10000, Instant.now()).orElseThrow();
        assertEquals(song.task, claim.task());
        assertEquals("COVER_QUEUED", claim.state());
        jobs.rejected(claim, "TEST_COMPLETE");
    }

    @Test void aNewerMusicTaskPreventsAnOlderCoverFromBeingApplied() {
        var song = fixture();
        jobs.enqueue(song.task);
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            String newer = uuid();
            db.update("""
                    INSERT INTO generation_task(id,work_id,revision_id,work_version,request_key,title,lyrics,style,
                        provider,status,created_at,updated_at,next_attempt_at)
                    SELECT ?,work_id,revision_id,work_version,?,title,lyrics,style,provider,'QUEUED',
                        TIMESTAMPADD(SECOND,1,created_at),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                    FROM generation_task WHERE id=?
                    """, newer, uuid(), song.task);
            return "https://example.test/old-result.jpg";
        }, covers, url -> { throw new AssertionError("The older cover was superseded"); }, 10000);
        runner.run();
        runner.run();
        assertNull(covers.current(song.work));
        assertEquals("COVER_SKIPPED", state(song));
    }

    @Test void cancelledMusicDoesNotTriggerAnImageCall() {
        var song = fixture();
        jobs.enqueue(song.task);
        db.update("UPDATE generation_task SET status='CANCELLED' WHERE id=?", song.task);
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        assertEquals("COVER_SKIPPED", state(song));
    }

    @Test void rolledBackMusicSubmissionCannotLeaveAWaitingCover() {
        String owner = identity.create().creatorId();
        var work = works.create(owner, new WorkApi.Draft("朋友", "生日", "故事", "祝福", "民谣", "歌曲"));
        var revision = lyrics.save(owner, work.id(), 0, "歌名", "雨夜的末班车");
        lyrics.confirm(owner, work.id(), revision.id());
        assertThrows(IllegalStateException.class, () ->
                generation.submit(owner, work.id(), revision.id(), uuid(), task -> {
                    throw new IllegalStateException("Simulated publication failure");
                }));
        assertTrue(generation.list(owner, work.id()).isEmpty());
        assertFalse(covers.state(owner, work.id()).automaticPending());
        assertEquals(0, db.queryForObject("""
                SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'
                """, Integer.class, work.id()));
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
    }

    @Test void recoveredAudioArchiveReactivatesItsWaitingCover() {
        var song = fixture();
        jobs.enqueue(song.task);
        db.update("UPDATE generation_task SET status='FAILED',asset_id=NULL WHERE id=?", song.task);
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        assertEquals("COVER_SKIPPED", state(song));
        db.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=? WHERE id=?", song.asset, song.task);
        var claim = jobs.claim(10000, Instant.now()).orElseThrow();
        assertEquals(song.task, claim.task());
        jobs.rejected(claim, "TEST_COMPLETE");
    }

    @Test void completionQueuesOnceAndLaterFillsTheMatchingPublishedVersion() throws Exception {
        var song = fixture();
        complete(song);
        complete(song);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE topic='music.cover' AND event_key=?", Integer.class, song.task));
        assertEquals("SUCCEEDED", db.queryForObject("SELECT status FROM generation_task WHERE id=?", String.class, song.task));
        assertTrue(covers.state(song.owner, song.work).automaticPending());
        String alternate = media.storeCandidate(song.task, song.work, "alternate", null, new ByteArrayInputStream(wav()));
        String release = release(song, alternate);
        var calls = new AtomicInteger();
        var downloads = new AtomicInteger();
        byte[] image = image();
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            calls.incrementAndGet();
            assertTrue(prompt.contains("末班车"));
            assertFalse(prompt.contains("13912345678"));
            return "https://example.test/result.jpg";
        }, covers, url -> {
            if (downloads.getAndIncrement() == 0) throw new IOException("Temporary archive error");
            return new ByteArrayInputStream(image);
        }, 10000);
        runner.run();
        runner.run();
        assertEquals("COVER_SAVING", state(song));
        assertNull(covers.current(song.work));
        db.update("UPDATE task_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE topic='music.cover' AND event_key=?", song.task);
        runner.run();
        String cover = covers.current(song.work);
        assertNotNull(cover);
        assertEquals(cover, db.queryForObject("SELECT cover_id FROM artist_song_release WHERE id=?", String.class, release));
        assertEquals(7, db.queryForObject("SELECT version FROM artist_song_release WHERE id=?", Integer.class, release));
        try (var stream = covers.content(song.work, cover).source().open()) {
            var saved = ImageIO.read(stream);
            assertEquals(1024, saved.getWidth());
            assertEquals(1024, saved.getHeight());
            boolean titleVisible = false;
            for (int y = 500; y < 830 && !titleVisible; y++) {
                for (int x = 50; x < 980; x++) {
                    if ((saved.getRGB(x, y) & 0xffffff) > 0xeeeeee) {
                        titleVisible = true;
                        break;
                    }
                }
            }
            assertTrue(titleVisible, "The stored cover must include the song title pixels");
        }
        assertEquals(1, calls.get());
        assertEquals(2, downloads.get());
        assertEquals("COVER_DONE", state(song));
        assertFalse(covers.state(song.owner, song.work).automaticPending());
    }

    @Test void anUnknownSubmissionIsNeverReplayedAndMusicRemainsAvailable() throws Exception {
        var song = fixture();
        complete(song);
        var calls = new AtomicInteger();
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            calls.incrementAndGet();
            throw new java.net.http.HttpTimeoutException("Simulated response lost");
        }, covers, url -> { throw new AssertionError("Unexpected download"); }, 10000);
        runner.run();
        runner.run();
        assertEquals(1, calls.get());
        assertEquals("COVER_UNKNOWN", state(song));
        assertNull(covers.current(song.work));
        assertFalse(covers.state(song.owner, song.work).automaticPending());
        try (var stream = media.open(song.owner, song.work, song.asset).source().open()) {
            assertArrayEquals(wav(), stream.readAllBytes());
        }
    }

    @Test void anExpiredSubmissionLeaseCannotCauseASecondProviderCall() {
        var song = fixture();
        complete(song);
        assertEquals(song.task, jobs.claim(10000, Instant.now()).orElseThrow().task());
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        db.update("""
                UPDATE task_event_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP)
                WHERE topic='music.cover' AND event_key=?
                """, song.task);
        assertTrue(jobs.claim(10000, Instant.now()).isEmpty());
        assertEquals("COVER_UNKNOWN", state(song));
    }

    @Test void selectingAnImageDuringArchiveKeepsTheManualChoice() throws Exception {
        var song = fixture();
        complete(song);
        String release = release(song, song.asset);
        byte[] image = image();
        String manual = covers.upload(song.owner, song.work, new ByteArrayInputStream(image)).id();
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> "https://example.test/result.jpg",
                covers, url -> {
                    covers.select(song.owner, song.work, manual, 0);
                    return new ByteArrayInputStream(image);
                }, 10000);
        runner.run();
        runner.run();
        assertEquals(manual, covers.current(song.work));
        assertEquals(manual, db.queryForObject("SELECT cover_id FROM artist_song_release WHERE id=?", String.class, release));
    }

    @Test void dailyLimitLeavesTheDefaultCoverAndDoesNotSubmit() throws Exception {
        var first = fixture();
        complete(first);
        var runner = new AutomaticCoverConfiguration.Runner(jobs, prompt -> {
            throw new com.qingfeng.music.cover.application.CoverImageProvider.Rejected(429);
        }, covers, url -> { throw new AssertionError("Unexpected download"); }, 10000);
        runner.run();
        assertEquals("COVER_FAILED", state(first));
        var song = fixture();
        complete(song);
        assertTrue(jobs.claim(1, Instant.now()).isEmpty());
        assertEquals("COVER_SKIPPED", state(song));
        assertNull(covers.current(song.work));
    }

    private Fixture fixture() {
        String owner = identity.create().creatorId();
        String work = works.create(owner, new WorkApi.Draft("朋友", "生日", "故事", "祝福", "民谣", "歌曲")).id();
        var revision = lyrics.save(owner, work, 0, "歌名", "[Chorus]\n雨夜的末班车\n13912345678");
        String task = uuid(), token = uuid();
        db.update("""
                INSERT INTO generation_task(id,work_id,revision_id,work_version,request_key,title,lyrics,style,
                    provider,status,created_at,updated_at,next_attempt_at,lease_token)
                VALUES(?,?,?,0,?,'歌名',?,'民谣','test','SAVING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?)
                """, task, work, revision.id(), uuid(), revision.lyrics(), token);
        String asset = media.store(task, work, new ByteArrayInputStream(wav()));
        return new Fixture(owner, work, task, token, asset);
    }

    private void complete(Fixture song) {
        executions.succeeded(song.task, song.token, song.asset, Instant.now());
    }

    private String state(Fixture song) {
        return eventState(song.task);
    }

    private String eventState(String task) {
        return db.queryForObject("SELECT state FROM task_event_outbox WHERE topic='music.cover' AND event_key=?", String.class, task);
    }

    private String release(Fixture song, String asset) {
        String release = uuid();
        db.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES(?,?,'测试','',TRUE)",
                song.owner, uuid());
        db.update("""
                INSERT INTO artist_song_release(id,creator_id,work_id,asset_id,title,version,show_on_profile,published_at,discover_published_at)
                VALUES(?,?,?,?,'歌曲',7,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, release, song.owner, song.work, asset);
        return release;
    }

    private static String uuid() { return UUID.randomUUID().toString(); }

    private static byte[] image() throws IOException {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB), "png", buffer);
        return buffer.toByteArray();
    }

    private static byte[] wav() {
        return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(40)
                .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(0).array();
    }
}

@EnabledIfEnvironmentVariable(named="QF_EXECUTION_MYSQL_TEST_PORT", matches="57[0-9]{3}")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_automatic_cover_test?allowPublicKeyRetrieval=true&useSSL=false&connectionCollation=utf8mb4_0900_ai_ci&serverTimezone=UTC",
    "spring.datasource.username=root", "spring.datasource.password=",
    "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"
})
class AutomaticCoverMySqlTest extends AutomaticCoverIntegrationTest {
    @org.junit.jupiter.api.BeforeEach void verifyMySql() throws Exception {
        try (var connection = db.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
    }
}
