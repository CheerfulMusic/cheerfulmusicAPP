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

    @Test void nativeTitleGetsOneDurableCorrectionAndNoSecondOverlay() throws Exception {
        var song=fixture();complete(song);
        var generations=new AtomicInteger();var corrections=new AtomicInteger();var checks=new AtomicInteger();
        byte[] image=image();
        var provider=new com.qingfeng.music.cover.application.CoverImageProvider(){
            public String generate(String prompt){
                assertTrue(prompt.contains("\"歌名\""));
                assertTrue(prompt.contains("上方主要视觉元素"));
                generations.incrementAndGet();return "https://example.test/first.jpg";
            }
            public String edit(byte[] input,String prompt){
                corrections.incrementAndGet();assertTrue(prompt.contains("歌名"));
                return "https://example.test/corrected.jpg";
            }
        };
        var runner=new AutomaticCoverConfiguration.Runner(jobs,provider,covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->checks.getAndIncrement()>0);
        runner.run();runner.run();
        assertEquals("COVER_REPAIR",state(song));assertNull(covers.current(song.work));
        runner.run();
        assertEquals("COVER_RESAVE",state(song));
        runner.run();runner.run();
        assertEquals("COVER_DONE",state(song));assertNotNull(covers.current(song.work));
        assertEquals(1,generations.get());assertEquals(1,corrections.get());assertEquals(2,checks.get());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM task_event_outbox WHERE topic='music.cover.repair' AND event_key=?",Integer.class,song.task));
    }

    @Test void aFailedCorrectedTitleStopsAndAnUncertainCorrectionIsNotReplayed() throws Exception {
        var song=fixture();complete(song);
        byte[] image=image();var calls=new AtomicInteger();
        var provider=new com.qingfeng.music.cover.application.CoverImageProvider(){
            public String generate(String prompt){return "https://example.test/first.jpg";}
            public String edit(byte[] input,String prompt){
                calls.incrementAndGet();return "https://example.test/corrected.jpg";
            }
        };
        var runner=new AutomaticCoverConfiguration.Runner(jobs,provider,covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->false);
        runner.run();runner.run();runner.run();runner.run();runner.run();
        assertEquals("COVER_FAILED",state(song));assertNull(covers.current(song.work));assertEquals(1,calls.get());

        var second=fixture();complete(second);var uncertainCalls=new AtomicInteger();
        var uncertain=new com.qingfeng.music.cover.application.CoverImageProvider(){
            public String generate(String prompt){return "https://example.test/first.jpg";}
            public String edit(byte[] input,String prompt) throws IOException {
                uncertainCalls.incrementAndGet();throw new java.net.http.HttpTimeoutException("response lost");
            }
        };
        var recovering=new AutomaticCoverConfiguration.Runner(jobs,uncertain,covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->false);
        recovering.run();recovering.run();recovering.run();recovering.run();
        assertEquals("COVER_UNKNOWN",state(second));assertEquals(1,uncertainCalls.get());
    }

    @Test void verifierOutageRetriesVerificationWithoutGeneratingAnotherImage() throws Exception {
        var song=fixture();complete(song);byte[] image=image();
        var generations=new AtomicInteger();var checks=new AtomicInteger();
        var runner=new AutomaticCoverConfiguration.Runner(jobs,prompt->{
            generations.incrementAndGet();return "https://example.test/first.jpg";
        },covers,url->new ByteArrayInputStream(image),10000,(picture,title)->{
            if(checks.getAndIncrement()==0)throw new IOException("verification unavailable");
            return true;
        });
        runner.run();runner.run();
        assertEquals("COVER_SAVING",state(song));
        db.update("UPDATE task_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE topic='music.cover' AND event_key=?",song.task);
        runner.run();
        assertEquals("COVER_DONE",state(song));assertEquals(1,generations.get());assertEquals(2,checks.get());
    }

    @org.junit.jupiter.api.BeforeEach void isolateCoverJobs() {
        if ("true".equals(System.getenv("QF_LIVE_ARK_COVER"))) {
            assertEquals("qf_execution_concurrency_test",db.queryForObject("SELECT DATABASE()",String.class));
            assertEquals(System.getenv("QF_EXECUTION_MYSQL_TEST_UUID"),db.queryForObject("SELECT @@server_uuid",String.class));
            assertEquals(57618,db.queryForObject("SELECT @@port",Integer.class));
        }
        db.update("""
                UPDATE task_event_outbox SET state='COVER_SKIPPED',last_error='TEST_CLEANUP',
                    lease_token=NULL,lease_until=NULL WHERE topic='music.cover'
                """);
    }

    @Test @EnabledIfEnvironmentVariable(named="QF_LIVE_ARK_COVER",matches="true")
    void realArkArtisticCoverSurvivesVerificationOutagesAndFinishesLocally() throws Exception {
        var song=fixture();
        db.update("UPDATE generation_task SET title='晚风写给你',lyrics='[Verse]\\n晚风经过安静的街头\\n灯光陪着脚步慢慢走\\n[Chorus]\\n把心里的期待唱给自由' WHERE id=?",song.task);
        jobs.enqueue(song.task); // Music is still SAVING when the image request begins.
        String key=System.getenv("ARK_API_KEY");assertNotNull(key);assertFalse(key.isBlank());
        var actual=new com.qingfeng.music.infrastructure.provider.ArkCoverImageProvider(key,com.qingfeng.music.infrastructure.provider.ArkCoverImageProvider.MODEL);
        var verifier=new com.qingfeng.music.infrastructure.provider.ArkCoverTitleVerifier(key,com.qingfeng.music.infrastructure.provider.ArkCoverTitleVerifier.DEFAULT_MODEL);
        var creates=new AtomicInteger();var repairs=new AtomicInteger();var reads=new AtomicInteger();var checks=new AtomicInteger();
        var approved=new com.qingfeng.music.infrastructure.storage.ProviderAudioUrls();
        var downloader=new com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader(java.util.Set.of(),approved);
        var provider=new com.qingfeng.music.cover.application.CoverImageProvider(){
            public String generate(String prompt)throws IOException,InterruptedException{creates.incrementAndGet();return actual.generate(prompt);}
            public String edit(byte[] input,String prompt)throws IOException,InterruptedException{repairs.incrementAndGet();return actual.edit(input,prompt);}
        };
        var runner=new AutomaticCoverConfiguration.Runner(jobs,provider,covers,url->{reads.incrementAndGet();approved.approve(url);return downloader.open(url);},10000,(picture,title)->{
            int number=checks.getAndIncrement();
            if(number==0)throw new com.qingfeng.music.common.exception.DomainException("LYRICS_PROVIDER_CONNECT_FAILED","simulated connection failure");
            if(number==1)throw new IOException("simulated temporary outage");
            return verifier.matches(picture,title);
        });
        long begin=System.nanoTime();runner.run();runner.run();
        assertEquals("COVER_SAVING",state(song));assertNull(covers.current(song.work));
        assertEquals(1,covers.state(song.owner,song.work).assets().size());
        due(song);runner.run();assertEquals("COVER_SAVING",state(song));assertEquals(1,reads.get());
        for(int i=0;i<8&&!java.util.Set.of("COVER_DONE","COVER_FAILED","COVER_UNKNOWN").contains(state(song));i++){due(song);runner.run();}
        assertEquals("COVER_DONE",state(song));assertEquals(1,creates.get());assertTrue(repairs.get()<=1);
        assertEquals("SAVING",db.queryForObject("SELECT status FROM generation_task WHERE id=?",String.class,song.task));
        complete(song);assertNotNull(covers.current(song.work));
        var folder=java.nio.file.Path.of("target/live-ark-cover");java.nio.file.Files.createDirectories(folder);
        try(var in=covers.open(song.owner,song.work,covers.current(song.work)).source().open()){
            byte[] image=in.readAllBytes();java.nio.file.Files.write(folder.resolve("verified-cover.jpg"),image);
            assertEquals(1024,ImageIO.read(new ByteArrayInputStream(image)).getWidth());
        }
        java.nio.file.Files.writeString(folder.resolve("verification.json"),new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "state",state(song),"generationCalls",creates.get(),"repairCalls",repairs.get(),"verificationAttempts",checks.get(),"downloads",reads.get(),
                "elapsedMillis",(System.nanoTime()-begin)/1000000,"isolatedDatabase",db.queryForObject("SELECT DATABASE()",String.class),
                "databaseUuid",db.queryForObject("SELECT @@server_uuid",String.class),"visionModel",com.qingfeng.music.infrastructure.provider.ArkCoverTitleVerifier.DEFAULT_MODEL)));
    }

    @Test void candidateSurvivesDomainNetworkFailuresAndOnlyVerificationRetries() throws Exception {
        var song=fixture();complete(song);byte[] image=image();
        var creates=new AtomicInteger();var reads=new AtomicInteger();var checks=new AtomicInteger();
        var runner=new AutomaticCoverConfiguration.Runner(jobs,prompt->{creates.incrementAndGet();return "https://example.test/candidate.jpg";},
                covers,url->{reads.incrementAndGet();return new ByteArrayInputStream(image);},10000,(picture,title)->{
            assertNotNull(covers.generated(song.work,db.queryForObject("SELECT id FROM task_event_outbox WHERE topic='music.cover' AND event_key=?",String.class,song.task)).orElse(null));
            assertNull(covers.current(song.work));
            if(checks.getAndIncrement()<2)throw new com.qingfeng.music.common.exception.DomainException("LYRICS_PROVIDER_CONNECT_FAILED","network");
            return true;
        });
        runner.run();runner.run();
        assertEquals("VERIFICATION_UNAVAILABLE",db.queryForObject("SELECT last_error FROM task_event_outbox WHERE topic='music.cover' AND event_key=?",String.class,song.task));
        for(int i=0;i<2;i++){due(song);runner.run();}
        assertEquals("COVER_DONE",state(song));assertNotNull(covers.current(song.work));
        assertEquals(1,creates.get());assertEquals(1,reads.get());assertEquals(3,checks.get());
    }

    @Test void wiringUsesArkWithoutAnOpenAiKey() {
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("qingfeng.providers.ark.api-key","test-ark-key");
        assertInstanceOf(com.qingfeng.music.infrastructure.provider.ArkCoverTitleVerifier.class,
                new AutomaticCoverConfiguration().automaticCoverTitleVerifier(environment));
    }

    @Test void manualCoverSelectedDuringVerificationIsNotOverwritten() throws Exception {
        var song=fixture();complete(song);byte[] image=image();
        String manual=covers.upload(song.owner,song.work,new ByteArrayInputStream(image)).id();
        var runner=new AutomaticCoverConfiguration.Runner(jobs,prompt->"https://example.test/candidate.jpg",covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->{covers.select(song.owner,song.work,manual,0);return true;});
        runner.run();runner.run();assertEquals(manual,covers.current(song.work));
        assertEquals(1,covers.state(song.owner,song.work).version());
    }

    @Test void aRestartedWorkerReusesThePersistedCandidateEvenIfTheResultUrlExpires() throws Exception {
        var song=fixture();complete(song);byte[] image=image();
        var first=new AutomaticCoverConfiguration.Runner(jobs,prompt->"https://example.test/expiring.jpg",covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->{throw new IOException("temporary outage");});
        first.run();first.run();assertNull(covers.current(song.work));due(song);
        var restarted=new AutomaticCoverConfiguration.Runner(jobs,prompt->{throw new AssertionError("Do not regenerate");},covers,
                url->{throw new AssertionError("Do not download an expired URL");},10000,(picture,title)->true);
        restarted.run();assertEquals("COVER_DONE",state(song));assertNotNull(covers.current(song.work));
    }

    @Test void verificationStopsAfterFourAttemptsAndRetainsUnselectedCandidate() throws Exception {
        var song=fixture();complete(song);var checks=new AtomicInteger();byte[] image=image();
        var runner=new AutomaticCoverConfiguration.Runner(jobs,prompt->"https://example.test/result.jpg",covers,
                url->new ByteArrayInputStream(image),10000,(picture,title)->{checks.incrementAndGet();throw new IOException("outage");});
        runner.run();for(int i=0;i<8;i++){due(song);runner.run();}
        assertEquals(4,checks.get());assertEquals("COVER_FAILED",state(song));assertNull(covers.current(song.work));
        String id=db.queryForObject("SELECT id FROM task_event_outbox WHERE topic='music.cover' AND event_key=?",String.class,song.task);
        assertTrue(covers.generated(song.work,id).isPresent());
        assertEquals("https://example.test/result.jpg",db.queryForObject("SELECT payload FROM task_event_outbox WHERE id=?",String.class,id));
    }

    @Test void correctedCandidateIsDistinctAndOriginalRemainsCached() throws Exception {
        var song=fixture();complete(song);var checks=new AtomicInteger();var downloads=new AtomicInteger();
        var original=new BufferedImage(60,80,BufferedImage.TYPE_INT_RGB);var repaired=new BufferedImage(60,80,BufferedImage.TYPE_INT_RGB);
        var graphics=repaired.createGraphics();graphics.setColor(java.awt.Color.RED);graphics.fillRect(0,0,60,80);graphics.dispose();
        var provider=new com.qingfeng.music.cover.application.CoverImageProvider(){
            public String generate(String prompt){return "https://example.test/first.jpg";}
            public String edit(byte[] input,String prompt){assertEquals(0,readPixel(input));return "https://example.test/fixed.jpg";}
            private int readPixel(byte[] bytes){try{return ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(0,0)&0xffffff;}catch(IOException e){throw new RuntimeException(e);}}
        };
        var runner=new AutomaticCoverConfiguration.Runner(jobs,provider,covers,url->{
            downloads.incrementAndGet();var out=new ByteArrayOutputStream();ImageIO.write(url.contains("fixed")?repaired:original,"png",out);return new ByteArrayInputStream(out.toByteArray());
        },10000,(picture,title)->{int pixel=ImageIO.read(new ByteArrayInputStream(picture)).getRGB(0,0)&0xffffff;return checks.getAndIncrement()==0?false:pixel>0;});
        for(int i=0;i<5;i++)runner.run();
        assertEquals("COVER_DONE",state(song));assertEquals(2,downloads.get());assertEquals(2,checks.get());
        assertEquals(2,covers.state(song.owner,song.work).assets().size());
        try(var in=covers.open(song.owner,song.work,covers.current(song.work)).source().open()){assertTrue((ImageIO.read(in).getRGB(0,0)&0xffffff)>0);}
    }

    private void due(Fixture song){db.update("UPDATE task_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE topic='music.cover' AND event_key=?",song.task);}

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
        }, covers, url -> new ByteArrayInputStream(image), 10000, (picture, title) -> true);
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
        }, covers, url -> new ByteArrayInputStream(image), 10000, (picture, title) -> true);
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
        }, covers, url -> { throw new AssertionError("Manual cover must take precedence"); }, 10000, (picture, title) -> true);
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
        }, covers, url -> { throw new AssertionError("Stopped music must not apply a late cover"); }, 10000, (picture, title) -> true);
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
        }, covers, url -> { throw new AssertionError("The older cover was superseded"); }, 10000, (picture, title) -> true);
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
        }, 10000, (picture, title) -> true);
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
            assertEquals(0, saved.getRGB(100, 750) & 0xffffff, "AI lettering must not receive another title overlay");
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
        }, covers, url -> { throw new AssertionError("Unexpected download"); }, 10000, (picture, title) -> true);
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
                }, 10000, (picture, title) -> true);
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
        }, covers, url -> { throw new AssertionError("Unexpected download"); }, 10000, (picture, title) -> true);
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
    "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?allowPublicKeyRetrieval=true&useSSL=false&connectionCollation=utf8mb4_0900_ai_ci&serverTimezone=UTC",
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
