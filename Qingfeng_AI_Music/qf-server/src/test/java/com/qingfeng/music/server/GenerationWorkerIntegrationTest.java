package com.qingfeng.music.server;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.application.port.*;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.infrastructure.persistence.generation.*;
import java.io.*;
import java.nio.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** 供应商和下载完全离线，只验证真实持久化、租约、恢复和归档；绝不消耗余额。 */
@SpringBootTest @ActiveProfiles("test")
class GenerationWorkerIntegrationTest {
    @Autowired IdentityApi identity; @Autowired WorkApi works; @Autowired LyricsApi lyrics;
    @Autowired ExecutionRepository repository; @Autowired TaskMapper mapper; @Autowired MediaService media;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static class Time extends Clock {
        Instant now=Instant.now().plusSeconds(10);
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return now;} void advance(long seconds){now=now.plusSeconds(seconds);}
    }
    static class Provider implements MusicProvider {
        String name="offline-"+UUID.randomUUID(); AtomicInteger submissions=new AtomicInteger(),queries=new AtomicInteger();Request received;
        boolean unknown,rejected,queryFailure,validationFailure,connectFailure; String audioUrl="https://audio.test/song.wav"; CountDownLatch entered,release;
        public void validate(Request request){if(validationFailure)throw new com.qingfeng.music.common.exception.DomainException("INVALID_MUSIC_INPUT","offline validation");}
        public String name(){return name;} public boolean isConfigured(){return true;}
        public Submission submit(Request request,String key){received=request;submissions.incrementAndGet();if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}}if(unknown)throw new IllegalStateException("模拟断线");if(rejected)throw new Rejected();return new Submission("external-"+key);}
        public Result query(String id){queries.incrementAndGet();if(queryFailure){queryFailure=false;throw new IllegalStateException("模拟查询断线");}return new Result(id,GenerationStatus.SAVING,audioUrl);}
    }
    record Fixture(String owner,String work,String id,GenerationApi api,Provider provider,Time time){}
    Fixture fixture(){return fixture(new Provider(),new Time());}
    Fixture fixture(Provider provider,Time time){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","毕业","故事","祝福","民谣","歌曲"));var revision=lyrics.save(owner,work.id(),0,"离线验证歌曲","[副歌]一起向前");lyrics.confirm(owner,work.id(),revision.id());var api=new GenerationService(works,lyrics,provider,new MyBatisTaskRepository(mapper),repository);var task=api.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());return new Fixture(owner,work.id(),task.id(),api,provider,time);}
    GenerationWorker worker(Fixture f,AudioDownloader downloader){return new GenerationWorker(repository,f.provider(),downloader,media,f.time(),Duration.ofMinutes(2),2);}
    byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
    GenerationStatus status(Fixture f){return f.api().list(f.owner(),f.work()).get(0).status();}
    @Test void localPreparationFailureIsNotAnUnknownSubmission(){
        var f=fixture();f.provider().validationFailure=true;assertTrue(worker(f,url->new ByteArrayInputStream(wav())).runOnce());
        assertEquals(GenerationStatus.FAILED,status(f));assertEquals("LOCAL_PREPARATION_FAILED",f.api().list(f.owner(),f.work()).get(0).errorCode());
        assertEquals(0,f.provider().submissions.get());assertEquals(0,jdbc.queryForObject("SELECT submit_attempts FROM generation_task WHERE id=?",Integer.class,f.id()));
    }
    @Test void repeatedQueryFailureSwitchesToBackgroundReviewAndLaterRecoversTheOriginalTask(){
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav())).withRecoveryPolicy(Duration.ofHours(2),2);w.runOnce();
        for(int n=0;n<2;n++){f.time().advance(31);f.provider().queryFailure=true;assertTrue(w.runOnce());}
        assertEquals(GenerationStatus.SUBMISSION_UNKNOWN,status(f));assertEquals("QUERY_REVIEW_REQUIRED",f.api().list(f.owner(),f.work()).get(0).errorCode());
        assertEquals("external-"+f.id(),jdbc.queryForObject("SELECT provider_task_id FROM generation_task WHERE id=?",String.class,f.id()));
        assertFalse(w.runOnce());f.time().advance(301);assertTrue(w.runOnce());assertEquals(GenerationStatus.SAVING,status(f));w.runOnce();assertEquals(GenerationStatus.SUCCEEDED,status(f));assertEquals(1,f.provider().submissions.get());
    }
    @Test void workerCannotSubmitDuringTheFiveSecondUndoWindow(){var f=fixture();var task=f.api().list(f.owner(),f.work()).get(0);f.time().now=task.createdAt().plusSeconds(4);var w=worker(f,url->new ByteArrayInputStream(wav()));assertFalse(w.runOnce());assertEquals(0,f.provider().submissions.get());f.time().now=task.createdAt().plusSeconds(6);assertTrue(w.runOnce());assertEquals(1,f.provider().submissions.get());}

    @Test void workerSendsPersistedVoiceAfterWorkChanges(){
        var provider=new Provider();var time=new Time();String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","毕业","故事","祝福","民谣","歌曲","FEMALE"));var revision=lyrics.save(owner,work.id(),0,"歌名","歌词");lyrics.confirm(owner,work.id(),revision.id());var api=new GenerationService(works,lyrics,provider,new MyBatisTaskRepository(mapper),repository);var accepted=api.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());works.update(owner,work.id(),0,new WorkApi.Draft("朋友","毕业","新故事","祝福","民谣","歌曲","MALE"));var test=new Fixture(owner,work.id(),accepted.id(),api,provider,time);assertTrue(worker(test,url->new ByteArrayInputStream(wav())).runOnce());assertEquals("FEMALE",provider.received.vocalGender());
    }
    @Test void freshWorkerResumesQueryAndArchivesExactlyOneResult(){
        var f=fixture();var first=worker(f,url->new ByteArrayInputStream(wav()));assertTrue(first.runOnce());assertEquals(GenerationStatus.RUNNING,status(f));
        f.time().advance(11);var afterRestart=worker(f,url->new ByteArrayInputStream(wav()));assertTrue(afterRestart.runOnce());assertEquals(GenerationStatus.SAVING,status(f));
        assertTrue(afterRestart.runOnce());assertEquals(GenerationStatus.SUCCEEDED,status(f));assertEquals(1,f.provider().submissions.get());
        var asset=media.list(f.owner(),f.work()).get(0);assertEquals("GENERATED",asset.origin());assertEquals(asset.id(),media.store(f.id(),f.work(),new ByteArrayInputStream(wav())));assertEquals(1,media.list(f.owner(),f.work()).size());
    }
    @Test void unknownSubmissionIsNeverAutomaticallySubmittedAgain(){
        var f=fixture();f.provider().unknown=true;var w=worker(f,url->new ByteArrayInputStream(wav()));assertTrue(w.runOnce());assertEquals(GenerationStatus.SUBMISSION_UNKNOWN,status(f));f.time().advance(3600);assertFalse(w.runOnce());assertEquals(1,f.provider().submissions.get());
    }
    @Test void unknownWithoutExternalIdDoesNotBlockAnotherCreatorsQueuedTask(){
        var first=fixture();first.provider().unknown=true;var w=worker(first,url->new ByteArrayInputStream(wav()));assertTrue(w.runOnce());
        var other=fixture();jdbc.update("UPDATE generation_task SET provider=? WHERE id=?",first.provider().name(),other.id());
        first.provider().unknown=false;assertTrue(w.runOnce());assertEquals(GenerationStatus.RUNNING,status(other));assertEquals(GenerationStatus.SUBMISSION_UNKNOWN,status(first));assertEquals(2,first.provider().submissions.get());
    }
    @Test void unknownWithExternalIdResumesReadOnlyQueryAndArchivesWithoutResubmitting(){
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav()));assertTrue(w.runOnce());
        jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN' WHERE id=?",f.id());f.time().advance(11);
        assertTrue(w.runOnce());assertEquals(GenerationStatus.SAVING,status(f));assertTrue(w.runOnce());assertEquals(GenerationStatus.SUCCEEDED,status(f));
        assertEquals(1,f.provider().submissions.get());assertEquals(1,f.provider().queries.get());
    }
    @Test void checkingQueryFailureBacksOffWithoutSubmittingAgain(){
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav()));assertTrue(w.runOnce());
        jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN' WHERE id=?",f.id());f.time().advance(11);f.provider().queryFailure=true;
        assertTrue(w.runOnce());assertEquals(GenerationStatus.RUNNING,status(f));assertFalse(w.runOnce());f.time().advance(21);assertTrue(w.runOnce());
        assertEquals(GenerationStatus.SAVING,status(f));assertEquals(1,f.provider().submissions.get());assertEquals(2,f.provider().queries.get());
    }
    @Test void expiredSubmittingLeaseBecomesUnknownWithoutCallingProvider(){
        var f=fixture();String token=UUID.randomUUID().toString();var claimed=repository.claim(f.provider().name(),token,f.time().instant(),f.time().instant().plusSeconds(120));assertTrue(claimed.isPresent());assertTrue(repository.startSubmission(f.id(),token,f.time().instant()));f.time().advance(121);
        assertTrue(worker(f,url->new ByteArrayInputStream(wav())).runOnce());assertEquals(GenerationStatus.SUBMISSION_UNKNOWN,status(f));assertEquals(0,f.provider().submissions.get());
    }
    @Test void failedArchiveCanBeRetriedWithoutRegeneration(){
        var f=fixture();AtomicInteger downloads=new AtomicInteger();var w=worker(f,url->{downloads.incrementAndGet();throw new IOException("模拟下载失败");});w.runOnce();f.time().advance(11);w.runOnce();w.runOnce();f.time().advance(61);w.runOnce();assertEquals(GenerationStatus.FAILED,status(f));assertEquals(2,downloads.get());
        assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->f.api().retryArchive(identity.create().creatorId(),f.work(),f.id()));
        f.api().retryArchive(f.owner(),f.work(),f.id());assertTrue(worker(f,url->new ByteArrayInputStream(wav())).runOnce());assertEquals(GenerationStatus.SUCCEEDED,status(f));assertEquals(1,f.provider().submissions.get());
    }
    @Test void queryFailureRetainsExternalTaskAndCanResume(){
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav()));w.runOnce();f.time().advance(11);f.provider().queryFailure=true;w.runOnce();assertEquals(GenerationStatus.RUNNING,status(f));f.time().advance(31);w.runOnce();assertEquals(GenerationStatus.SAVING,status(f));assertEquals(1,f.provider().submissions.get());
    }
    @Test void taskLeasePreventsTwoWorkersFromSubmittingTheSameTask()throws Exception{
        var f=fixture();f.provider().entered=new CountDownLatch(1);f.provider().release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try{var running=executor.submit(()->worker(f,url->new ByteArrayInputStream(wav())).runOnce());assertTrue(f.provider().entered.await(5,TimeUnit.SECONDS));assertFalse(worker(f,url->new ByteArrayInputStream(wav())).runOnce());f.provider().release.countDown();assertTrue(running.get(5,TimeUnit.SECONDS));assertEquals(1,f.provider().submissions.get());}finally{f.provider().release.countDown();executor.shutdownNow();}
    }
    @Test void explicitRejectionFailsWithoutAutomaticRetry(){var f=fixture();f.provider().rejected=true;var w=worker(f,url->new ByteArrayInputStream(wav()));w.runOnce();assertEquals(GenerationStatus.FAILED,status(f));assertFalse(w.runOnce());assertEquals(1,f.provider().submissions.get());}
    @Test void concurrencyLimitOnePreservesSerialDispatchWhenRequested(){var f=fixture();var original=f.api().list(f.owner(),f.work()).get(0);f.api().submit(f.owner(),f.work(),original.revisionId(),UUID.randomUUID().toString());var w=worker(f,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(1);assertTrue(w.runOnce());assertFalse(w.runOnce());assertEquals(1,f.provider().submissions.get());assertEquals(1,f.api().list(f.owner(),f.work()).stream().filter(t->t.status()==GenerationStatus.QUEUED).count());}

    @Test void differentCreatorsCanGenerateTogetherUpToTheInFlightLimit(){
        var first=fixture();var second=fixture(first.provider(),first.time());var third=fixture(first.provider(),first.time());
        var w=worker(first,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(2);
        assertTrue(w.runOnce(ExecutionRepository.Phase.SUBMIT));
        assertTrue(w.runOnce(ExecutionRepository.Phase.SUBMIT));
        assertFalse(w.runOnce(ExecutionRepository.Phase.SUBMIT));
        assertEquals(2,first.provider().submissions.get());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE provider=? AND status='RUNNING'",Integer.class,first.provider().name()));
        // 查询仍可推进；进入归档后释放在途名额，不必等音频上传结束才提交下一首。
        first.time().advance(11);
        assertTrue(w.runOnce(ExecutionRepository.Phase.QUERY));
        assertTrue(w.runOnce(ExecutionRepository.Phase.SUBMIT));
        assertEquals(3,first.provider().submissions.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE provider=? AND status='SAVING'",Integer.class,first.provider().name()));
    }

    @Test void simultaneousWorkersReserveQueuedLeasesWithoutOvershootingTheLimit()throws Exception{
        var f=fixture();fixture(f.provider(),f.time());fixture(f.provider(),f.time());
        f.provider().entered=new CountDownLatch(2);f.provider().release=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try{
            var a=executor.submit(()->worker(f,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(2).runOnce(ExecutionRepository.Phase.SUBMIT));
            var b=executor.submit(()->worker(f,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(2).runOnce(ExecutionRepository.Phase.SUBMIT));
            assertTrue(f.provider().entered.await(5,TimeUnit.SECONDS),"不同任务的提交不能持有整段供应商锁");
            assertFalse(worker(f,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(2).runOnce(ExecutionRepository.Phase.SUBMIT));
            assertEquals(2,f.provider().submissions.get());
            f.provider().release.countDown();assertTrue(a.get(5,TimeUnit.SECONDS));assertTrue(b.get(5,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE provider=? AND status='QUEUED'",Integer.class,f.provider().name()));
        }finally{f.provider().release.countDown();executor.shutdownNow();}
    }

    @Test void slowArchiveDoesNotBlockOtherCreatorsSubmissionOrQuery()throws Exception{
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav()));w.runOnce();f.time().advance(11);w.runOnce();
        var other=fixture(f.provider(),f.time());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        var slow=worker(f,url->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("归档未释放");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return new ByteArrayInputStream(wav());});
        try{
            var saving=executor.submit(()->slow.runOnce(ExecutionRepository.Phase.ARCHIVE));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(w.runOnce(ExecutionRepository.Phase.SUBMIT));assertEquals(GenerationStatus.RUNNING,status(other));
            f.time().advance(11);assertTrue(w.runOnce(ExecutionRepository.Phase.QUERY));assertEquals(GenerationStatus.SAVING,status(other));
            assertEquals(2,f.provider().submissions.get());
            release.countDown();assertTrue(saving.get(5,TimeUnit.SECONDS));assertEquals(GenerationStatus.SUCCEEDED,status(f));
        }finally{release.countDown();executor.shutdownNow();}
    }

    @Test void queuedLeaseCountsTowardCapacityAndExpiredOwnerCannotStartOrReleaseANewLease(){
        var f=fixture();fixture(f.provider(),f.time());String oldToken=UUID.randomUUID().toString();var now=f.time().instant();
        var claimed=repository.claim(f.provider().name(),oldToken,now,now.plusSeconds(120),ExecutionRepository.Phase.SUBMIT,1).orElseThrow();
        assertFalse(worker(f,url->new ByteArrayInputStream(wav())).withConcurrencyLimit(1).runOnce(ExecutionRepository.Phase.SUBMIT));
        assertTrue(repository.renew(f.provider().name(),claimed.task().id(),oldToken,now.plusSeconds(10),now.plusSeconds(130)));
        f.time().advance(131);
        assertFalse(repository.startSubmission(claimed.task().id(),oldToken,f.time().instant()));
        String newToken=UUID.randomUUID().toString();
        var replacement=repository.claim(f.provider().name(),newToken,f.time().instant(),f.time().instant().plusSeconds(120),ExecutionRepository.Phase.SUBMIT,1).orElseThrow();
        repository.release(replacement.task().id(),oldToken);
        assertEquals(newToken,jdbc.queryForObject("SELECT lease_token FROM generation_task WHERE id=?",String.class,replacement.task().id()));
        assertFalse(repository.renew(f.provider().name(),replacement.task().id(),oldToken,f.time().instant(),f.time().instant().plusSeconds(120)));
    }

    @Test void zeroDispatchLimitStillAllowsRecoveryAndArchiving(){
        var f=fixture();var w=worker(f,url->new ByteArrayInputStream(wav()));w.runOnce();fixture(f.provider(),f.time());
        w.withConcurrencyLimit(0);assertFalse(w.runOnce(ExecutionRepository.Phase.SUBMIT));
        f.time().advance(11);assertTrue(w.runOnce(ExecutionRepository.Phase.QUERY));assertTrue(w.runOnce(ExecutionRepository.Phase.ARCHIVE));
        assertEquals(GenerationStatus.SUCCEEDED,status(f));assertEquals(1,f.provider().submissions.get());
    }
    @Test void expiredAudioUrlIsRefreshedByReadonlyQueryWithoutResubmission(){var f=fixture();var w=worker(f,url->{if(url.endsWith("song.wav"))throw new IOException("模拟临时链接过期");return new ByteArrayInputStream(wav());});w.runOnce();f.time().advance(11);w.runOnce();f.provider().audioUrl="https://audio.test/fresh.wav";w.runOnce();assertEquals(GenerationStatus.SAVING,status(f));f.time().advance(31);w.runOnce();assertEquals(GenerationStatus.SUCCEEDED,status(f));assertEquals(1,f.provider().submissions.get());}
}
