package com.qingfeng.music.server;

import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.server.lyrics.LyricsPreparation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lyrics_preparation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.flyway.target=49","qingfeng.generation.enabled=true","qingfeng.lyrics.timing.enabled=true","qingfeng.usage.timing.daily-per-creator=2"})
@Import(LyricsPreparationIntegrationTest.TranscriptionConfig.class)
@org.springframework.test.context.jdbc.Sql(scripts="classpath:db/migration/V49__lyrics_preparation.sql",executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class LyricsPreparationIntegrationTest extends PlaybackLyricsIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class TranscriptionConfig {
        @Bean @Primary FakeTranscription fakeTranscription(){return new FakeTranscription();}
    }
    static class FakeTranscription implements AudioTranscriptionProvider {
        AtomicInteger submits=new AtomicInteger(),polls=new AtomicInteger();String state="READY",submission="OK";
        public boolean isConfigured(){return true;}
        public String submit(String audio){submits.incrementAndGet();if(submission.equals("UNKNOWN"))throw new IllegalStateException("timeout");if(submission.equals("REJECT"))throw new Rejected("PROVIDER_422");if(submission.equals("AUTH"))throw new Rejected("PROVIDER_401");return "external-task";}
        public Result poll(String task){polls.incrementAndGet();if(state.equals("READ_FAIL"))throw new IllegalStateException("read failed");return new Result(state,state.equals("READY")?List.of(new PlaybackLyrics.Word("已识别的演唱歌词\n",5,9)):List.of());}
    }
    @Autowired LyricsPreparation preparation;@Autowired FakeTranscription transcribe;@Autowired PlaybackLyricsRepository repository;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void clearJobs(){
        assertTrue(jdbc.getDataSource()!=null);
        try(var connection=jdbc.getDataSource().getConnection()){assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:"));}
        catch(java.sql.SQLException error){throw new IllegalStateException(error);}
        jdbc.update("DELETE FROM lyrics_preparation_job");
        jdbc.update("UPDATE media_lyrics_timeline SET state='READY',words_json='[]' WHERE state='PREPARING'");
        jdbc.update("UPDATE media_asset_annotation SET deleted_at=CURRENT_TIMESTAMP WHERE deleted_at IS NULL");
        // Prior fixtures are retained but excluded from scans; no real data is present in this H2-only test database.
        jdbc.update("UPDATE music_work SET deleted_at=CURRENT_TIMESTAMP WHERE deleted_at IS NULL");
        jdbc.update("DELETE FROM lyrics_preparation_control");
        transcribe.submits.set(0);transcribe.polls.set(0);transcribe.state="READY";transcribe.submission="OK";
    }
    Fixture transcriptionFixture(){var f=fixture();jdbc.update("UPDATE generation_task SET provider_task_id=NULL WHERE asset_id=?",f.asset());preparation.enqueueArchived(25);return f;}
    String state(){return jdbc.queryForObject("SELECT state FROM lyrics_preparation_job ORDER BY created_at,source_key LIMIT 1",String.class);}
    void due(){jdbc.update("UPDATE lyrics_preparation_job SET next_attempt_at=TIMESTAMP '2000-01-01 00:00:00'");}
    @Test void oldAndNewArchivesArePreparedWithoutPlaybackAndNeverRecharged(){
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='TIMING'",Integer.class);
        var a=fixture();assertEquals(1,preparation.enqueueArchived(25));assertEquals(0,preparation.enqueueArchived(25));
        assertEquals("PREPARING",repository.find(a.asset()).orElseThrow().status());preparation.processOnce();
        assertEquals("READY",repository.find(a.asset()).orElseThrow().status());assertEquals(1,timing.calls.get());
        var b=fixture();assertEquals(1,preparation.enqueueArchived(25));preparation.processOnce();assertEquals("READY",repository.find(b.asset()).orElseThrow().status());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='TIMING'",Integer.class));
    }
    @Test void existingReadyAndManuallyFetchingTimelinesAreNeverOverwritten(){
        var f=fixture();service.sync(f.owner(),f.work(),f.asset(),UUID.randomUUID().toString());assertEquals(0,preparation.enqueueArchived(25));
        String other=asset(f.owner(),f.work(),f.revision());jdbc.update("INSERT INTO media_lyrics_timeline(asset_id,work_id,state,updated_at) VALUES(?,?,'FETCHING',CURRENT_TIMESTAMP)",other,f.work());
        assertEquals(0,preparation.enqueueArchived(25));assertEquals(1,timing.calls.get());
    }
    @Test void originalRejectionFallsBackOnceAndRestartOnlyPollsTheSavedExternalTask(){
        var f=fixture();timing.mode="FAILED";preparation.enqueueArchived(25);preparation.processOnce();due();preparation.processOnce();
        assertEquals(1,transcribe.submits.get());assertEquals("external-task",jdbc.queryForObject("SELECT external_task_id FROM lyrics_preparation_job",String.class));
        var restarted=new LyricsPreparation(jdbc,new TransactionTemplate(manager),repository,timing,transcribe,40);
        due();restarted.processOnce();assertEquals("READY",repository.find(f.asset()).orElseThrow().status());assertEquals(1,transcribe.submits.get());assertEquals(1,transcribe.polls.get());
    }
    @Test void unknownSubmissionIsNeverRepostedAndDoesNotBlockOtherJobs(){
        transcriptionFixture();transcribe.submission="UNKNOWN";preparation.processOnce();due();preparation.processOnce();assertEquals(1,transcribe.submits.get());
        assertEquals("UNKNOWN",state());
        transcriptionFixture();transcribe.submission="OK";preparation.processOnce();assertEquals(2,transcribe.submits.get());
    }
    @Test void failedSubmissionsHaveAtMostTwoAttemptsAndModelAuthStopsImmediately(){
        transcriptionFixture();transcribe.submission="REJECT";
        preparation.processOnce();due();preparation.processOnce();due();preparation.processOnce();assertEquals(2,transcribe.submits.get());assertEquals("FAILED",state());
    }
    @Test void simultaneousWorkersSubmitOnlyOnce()throws Exception {
        transcriptionFixture();var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(preparation::processOnce);var b=pool.submit(preparation::processOnce);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,transcribe.submits.get());}finally{pool.shutdownNow();}
    }
    @Test void deletedVersionIsCancelledBeforeAnyProviderCall(){
        var f=fixture();preparation.enqueueArchived(25);jdbc.update("INSERT INTO media_asset_annotation(asset_id,deleted_at) VALUES(?,CURRENT_TIMESTAMP)",f.asset());preparation.processOnce();assertEquals(0,timing.calls.get());assertEquals("CANCELLED",jdbc.queryForObject("SELECT state FROM lyrics_preparation_job",String.class));
    }
    @Test void staleSubmitDoesNotRetryAndStalePollCanResume(){
        transcriptionFixture();jdbc.update("UPDATE lyrics_preparation_job SET state='SUBMITTING',lease_until=TIMESTAMP '2000-01-01 00:00:00'");preparation.processOnce();assertEquals("UNKNOWN",state());assertEquals(0,transcribe.submits.get());
        jdbc.update("UPDATE lyrics_preparation_job SET state='READING',external_task_id='saved-task',lease_until=TIMESTAMP '2000-01-01 00:00:00'");preparation.processOnce();assertEquals(1,transcribe.polls.get());assertEquals(0,transcribe.submits.get());
    }

    @Test void systemDailyLimitDoesNotDebitOrSubmit(){
        var bounded=new LyricsPreparation(jdbc,new TransactionTemplate(manager),repository,timing,transcribe,0);
        transcriptionFixture();bounded.processOnce();assertEquals(0,transcribe.submits.get());
    }

    @Test void providerWaitStartsAtSubmissionRatherThanTimeSpentInTheQueue(){
        var f=transcriptionFixture();
        jdbc.update("UPDATE lyrics_preparation_job SET created_at=TIMESTAMP '2000-01-01 00:00:00'");
        preparation.processOnce();due();preparation.processOnce();
        assertEquals("READY",repository.find(f.asset()).orElseThrow().status());
        assertEquals(1,transcribe.submits.get());assertEquals(1,transcribe.polls.get());
    }

    @Test void aSuccessfulPendingReadResetsConsecutivePollFailures(){
        transcriptionFixture();preparation.processOnce();transcribe.state="READ_FAIL";
        for(int n=0;n<4;n++){due();preparation.processOnce();}
        transcribe.state="PENDING";due();preparation.processOnce();
        assertEquals(0,jdbc.queryForObject("SELECT poll_failures FROM lyrics_preparation_job",Integer.class));
        transcribe.state="READ_FAIL";due();preparation.processOnce();assertEquals("POLLING",state());
        assertEquals(1,transcribe.submits.get());
    }

    @Test void manualAndAutomaticPreparationRaceWithoutDuplicateProviderCalls()throws Exception {
        var f=fixture();var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->preparation.enqueueArchived(25));var b=pool.submit(()->service.sync(f.owner(),f.work(),f.asset(),UUID.randomUUID().toString()));a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);preparation.processOnce();assertEquals(1,timing.calls.get());assertEquals("READY",repository.find(f.asset()).orElseThrow().status());}finally{pool.shutdownNow();}
    }

    @Test void onlyNewArchivesAndHistoricPublicSongsAreAutomaticallyQueued(){
        var f=fixture();jdbc.update("UPDATE media_asset SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",f.asset());
        assertEquals(0,preparation.enqueueNewAndPublished(25));var pub=publish(f);
        jdbc.update("UPDATE artist_song_release SET discover_published_at=CURRENT_TIMESTAMP WHERE id=?",pub[1]);
        assertEquals(1,preparation.enqueueNewAndPublished(25));preparation.processOnce();
        var newer=fixture();assertEquals(1,preparation.enqueueNewAndPublished(25));assertEquals(0,preparation.enqueueNewAndPublished(25));
    }

    @Test void authorizationFailureIsTerminalAndReadErrorsOnlyPollTheKnownJob(){
        transcriptionFixture();transcribe.submission="AUTH";preparation.processOnce();due();preparation.processOnce();assertEquals(1,transcribe.submits.get());assertEquals("FAILED",state());
        jdbc.update("DELETE FROM lyrics_preparation_job");transcriptionFixture();transcribe.submission="OK";preparation.processOnce();transcribe.state="READ_FAIL";
        for(int n=0;n<6;n++){due();preparation.processOnce();}assertEquals(2,transcribe.submits.get());assertEquals(5,transcribe.polls.get());assertEquals("UNKNOWN",state());
    }
    @Test void deletionDuringAProviderCallDoesNotAttachItsLateResult()throws Exception {
        var f=fixture();preparation.enqueueArchived(25);timing.entered=new CountDownLatch(1);timing.release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        try{var running=pool.submit(preparation::processOnce);assertTrue(timing.entered.await(3,TimeUnit.SECONDS));jdbc.update("INSERT INTO media_asset_annotation(asset_id,deleted_at) VALUES(?,CURRENT_TIMESTAMP)",f.asset());timing.release.countDown();running.get(10,TimeUnit.SECONDS);assertEquals("CANCELLED",state());assertNotEquals("READY",repository.find(f.asset()).orElseThrow().status());}finally{timing.release.countDown();pool.shutdownNow();}
    }
}
