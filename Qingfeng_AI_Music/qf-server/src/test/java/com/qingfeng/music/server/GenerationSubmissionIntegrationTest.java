package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="qingfeng.generation.enabled=true") @ActiveProfiles("test") @Import(GenerationSubmissionIntegrationTest.Providers.class)
class GenerationSubmissionIntegrationTest {
 @Autowired com.qingfeng.music.generation.application.ExecutionRepository executions;
 @Test void closingCheckingRequiresOwnerAcknowledgementAndRetainsUsageAndIdempotency(){
  var f=fixture();String key=UUID.randomUUID().toString();var task=tasks.submit(f.owner(),f.work(),f.revision(),key);
  jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN',updated_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP) WHERE id=?",task.id());
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.closeChecking(identity.create().creatorId(),f.work(),task.id(),true));
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.closeChecking(f.owner(),f.work(),task.id(),false));
  var ended=tasks.closeChecking(f.owner(),f.work(),task.id(),true);assertEquals("CHECKING_CLOSED",ended.errorCode());assertEquals(com.qingfeng.music.generation.domain.GenerationStatus.FAILED,ended.status());
  assertEquals(ended.id(),tasks.closeChecking(f.owner(),f.work(),task.id(),true).id());assertEquals(ended.status(),tasks.submit(f.owner(),f.work(),f.revision(),key).status());
  executions.accepted(task.id(),"late-platform-id",java.time.Instant.now(),java.time.Instant.now());
  assertEquals(com.qingfeng.music.generation.domain.GenerationStatus.FAILED,tasks.list(f.owner(),f.work()).get(0).status());
  assertNull(jdbc.queryForObject("SELECT provider_task_id FROM generation_task WHERE id=?",String.class,task.id()));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,f.work()));
  assertNotEquals(ended.id(),tasks.submit(f.owner(),f.work(),f.revision(),UUID.randomUUID().toString()).id());
 }
 @Test void closingCheckingCannotRaceAnActiveLeaseOrDiscardAKnownPlatformTask(){
  var f=fixture();var task=tasks.submit(f.owner(),f.work(),f.revision(),UUID.randomUUID().toString());
  jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN' WHERE id=?",task.id());
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.closeChecking(f.owner(),f.work(),task.id(),true));
  jdbc.update("UPDATE generation_task SET updated_at=TIMESTAMPADD(SECOND,-181,CURRENT_TIMESTAMP),lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE id=?",task.id());
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.closeChecking(f.owner(),f.work(),task.id(),true));
  jdbc.update("UPDATE generation_task SET lease_until=NULL,provider_task_id='known-task' WHERE id=?",task.id());
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.closeChecking(f.owner(),f.work(),task.id(),true));
  assertEquals(com.qingfeng.music.generation.domain.GenerationStatus.SUBMISSION_UNKNOWN,tasks.list(f.owner(),f.work()).get(0).status());
 }
 @TestConfiguration(proxyBeanMethods=false)static class Providers{
  @Bean @Primary MusicProvider offlineOnly(){return new MusicProvider(){public String name(){return "test-only";}public boolean isConfigured(){return true;}public Submission submit(Request r,String key){throw new AssertionError("测试不得调用供应商");}public Result query(String id){throw new AssertionError("测试不得查询供应商");}};}
 }
 @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Autowired com.qingfeng.music.media.application.MediaService media;@Autowired com.qingfeng.music.sharing.api.SharingApi sharing;
 record Fixture(String owner,String work,String revision){}
 Fixture fixture(){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","作品"));var r=lyrics.save(owner,work.id(),0,"歌曲","确认的歌词");lyrics.confirm(owner,work.id(),r.id());return new Fixture(owner,work.id(),r.id());}
 @Test void concurrentSameRequestCreatesOneTask()throws Exception{
  var f=fixture();String key=UUID.randomUUID().toString();var executor=Executors.newFixedThreadPool(2);
  try{var a=executor.submit(()->tasks.submit(f.owner(),f.work(),f.revision(),key));var b=executor.submit(()->tasks.submit(f.owner(),f.work(),f.revision(),key));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());assertEquals(1,tasks.list(f.owner(),f.work()).size());}finally{executor.shutdownNow();}
 }
 @Test void voiceIsCapturedInTheTaskAndDoesNotFollowLaterWorkEdits(){
  String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","歌曲","FEMALE"));var r=lyrics.save(owner,work.id(),0,"歌名","歌词");lyrics.confirm(owner,work.id(),r.id());String key=UUID.randomUUID().toString();var accepted=tasks.submit(owner,work.id(),r.id(),key);assertEquals("FEMALE",accepted.vocalGender());
  works.update(owner,work.id(),0,new WorkApi.Draft("妈妈","生日","新故事","祝福","民谣","歌曲","MALE"));assertEquals("FEMALE",tasks.submit(owner,work.id(),r.id(),key).vocalGender());assertEquals("MALE",works.get(owner,work.id()).vocalGender());
 }
 @Test void unlimitedDailyMusicAllowsMoreThanTwentyAndKeepsIdempotency(){
  var f=fixture();String firstKey=UUID.randomUUID().toString(),firstId="";
  for(int n=0;n<25;n++){var accepted=tasks.submit(f.owner(),f.work(),f.revision(),n==0?firstKey:UUID.randomUUID().toString());if(n==0)firstId=accepted.id();jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",accepted.id());}
  assertEquals(25,tasks.list(f.owner(),f.work()).size());assertEquals(firstId,tasks.submit(f.owner(),f.work(),f.revision(),firstKey).id());assertEquals(25,tasks.list(f.owner(),f.work()).size());
 }
 @Test void laterEditingDoesNotAlterSubmittedSnapshot(){
  var f=fixture();String key=UUID.randomUUID().toString();var task=tasks.submit(f.owner(),f.work(),f.revision(),key);
  works.update(f.owner(),f.work(),0,new WorkApi.Draft("朋友","毕业","新故事","新祝福","爵士","新作品"));
  assertEquals(task.id(),tasks.submit(f.owner(),f.work(),f.revision(),key).id());assertEquals("民谣",tasks.list(f.owner(),f.work()).get(0).style());assertEquals("确认的歌词",tasks.list(f.owner(),f.work()).get(0).lyrics());
 }
 @Test void queuedRequestCanBeCancelledWithoutConsumingMusicQuotaAndReplayCannotResubmit(){
  var f=fixture();String key=UUID.randomUUID().toString();var accepted=tasks.submit(f.owner(),f.work(),f.revision(),key);
  assertTrue(java.time.Duration.between(accepted.createdAt(),accepted.nextAttemptAt()).toMillis()>=4999);
  var cancelled=tasks.cancel(f.owner(),f.work(),accepted.id());assertEquals(com.qingfeng.music.generation.domain.GenerationStatus.CANCELLED,cancelled.status());
  assertEquals(cancelled.id(),tasks.cancel(f.owner(),f.work(),cancelled.id()).id());assertEquals(cancelled.status(),tasks.submit(f.owner(),f.work(),f.revision(),key).status());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,f.work()));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,f.work()));
 }
 @Test void cancellationAndWorkerSubmissionHaveAnAtomicBoundary(){
  var f=fixture();var task=tasks.submit(f.owner(),f.work(),f.revision(),UUID.randomUUID().toString());String token=UUID.randomUUID().toString();
  jdbc.update("UPDATE generation_task SET lease_token=? WHERE id=?",token,task.id());tasks.cancel(f.owner(),f.work(),task.id());
  assertEquals(0,jdbc.update("UPDATE generation_task SET status='SUBMITTING',submit_attempts=1 WHERE id=? AND status='QUEUED' AND lease_token=?",task.id(),token));
  var b=fixture();var started=tasks.submit(b.owner(),b.work(),b.revision(),UUID.randomUUID().toString());jdbc.update("UPDATE generation_task SET status='SUBMITTING',submit_attempts=1 WHERE id=?",started.id());
  assertEquals("TASK_ALREADY_SUBMITTED",assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.cancel(b.owner(),b.work(),started.id())).code());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,b.work()));
  assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->tasks.cancel(identity.create().creatorId(),b.work(),started.id()));
 }
 @Test void oldGeneratedSongCanStillBeGiftedAfterStyleChangesButOnlyWithItsOriginalLyrics(){
  var f=fixture();var task=tasks.submit(f.owner(),f.work(),f.revision(),UUID.randomUUID().toString());
  var b=java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
  String asset=media.store(task.id(),f.work(),new java.io.ByteArrayInputStream(b.array()));works.update(f.owner(),f.work(),0,new WorkApi.Draft("妈妈","生日","故事","祝福","摇滚","作品","MALE"));
  assertNotNull(sharing.create(f.owner(),f.work(),f.revision(),asset).token());var other=lyrics.save(f.owner(),f.work(),1,"新歌","另一版歌词");lyrics.confirm(f.owner(),f.work(),other.id());assertEquals("INVALID_GIFT_SOURCE",assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->sharing.create(f.owner(),f.work(),other.id(),asset)).code());
 }
}
