package com.qingfeng.music.server;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:credits_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.credits.enabled=true","qingfeng.generation.enabled=true"}) @ActiveProfiles("test") @org.springframework.context.annotation.Import(CreditsIntegrationTest.Offline.class)
class CreditsIntegrationTest {
 @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) static class Offline {
  @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary com.qingfeng.music.generation.application.port.MusicProvider provider(){return new com.qingfeng.music.generation.application.port.MusicProvider(){public String name(){return "credits-offline";}public boolean isConfigured(){return true;}public Submission submit(Request r,String k){throw new AssertionError();}public Result query(String id){throw new AssertionError();}};}
 }
 @Autowired com.qingfeng.music.generation.application.ExecutionRepository execution;
 @Autowired com.qingfeng.music.work.api.WorkApi works; @Autowired com.qingfeng.music.lyrics.api.LyricsApi lyrics; @Autowired com.qingfeng.music.generation.api.GenerationApi generation;
 @Autowired CreditsLedger credits; @Autowired IdentityApi identity; @Autowired PlatformTransactionManager manager;
 @Autowired com.qingfeng.music.server.web.app.ReferralController referrals;
 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Test void knownUnsubmittedFailuresReleaseQuotaAndCreditsExactlyOnce(){
  for(String code:java.util.List.of("LOCAL_PREPARATION_FAILED","PROVIDER_CONNECT_FAILED","PROVIDER_BALANCE_LOW")){
   String owner=identity.create().creatorId();credits.credit(owner,15,"not-sent-"+owner);
   var work=works.create(owner,new com.qingfeng.music.work.api.WorkApi.Draft("","","故事","","民谣","歌曲"));var revision=lyrics.save(owner,work.id(),0,"歌曲","歌词");lyrics.confirm(owner,work.id(),revision.id());
   String key=java.util.UUID.randomUUID().toString();var task=generation.submit(owner,work.id(),revision.id(),key);assertEquals(0,credits.balance(owner));
   var now=java.time.Instant.now().plusSeconds(10);String token=java.util.UUID.randomUUID().toString();assertTrue(execution.lockProvider("credits-offline",token,now,now.plusSeconds(120)));
   try{
    assertEquals(task.id(),execution.claim("credits-offline",token,now,now.plusSeconds(120)).orElseThrow().task().id());
    if(!code.equals("LOCAL_PREPARATION_FAILED"))assertTrue(execution.startSubmission(task.id(),token,now));
    execution.failed(task.id(),token,code,now);execution.failed(task.id(),token,code,now);
    assertEquals(15,credits.balance(owner));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,work.id()));
    assertEquals(task.id(),generation.submit(owner,work.id(),revision.id(),key).id());assertEquals(15,credits.balance(owner));
   }finally{execution.release(task.id(),token);execution.unlockProvider("credits-offline",token);}
  }
 }
 @Test void archiveRetryKeepsAcceptedGenerationAccountingAndNeverChargesAgain(){
  String owner=identity.create().creatorId();credits.credit(owner,15,"archive-"+owner);
  var work=works.create(owner,new com.qingfeng.music.work.api.WorkApi.Draft("","","故事","","民谣","歌曲"));var revision=lyrics.save(owner,work.id(),0,"歌曲","歌词");lyrics.confirm(owner,work.id(),revision.id());
  var task=generation.submit(owner,work.id(),revision.id(),java.util.UUID.randomUUID().toString());var now=java.time.Instant.now().plusSeconds(10);String token=java.util.UUID.randomUUID().toString();
  assertTrue(execution.lockProvider("credits-offline",token,now,now.plusSeconds(120)));
  try{
   assertEquals(task.id(),execution.claim("credits-offline",token,now,now.plusSeconds(120)).orElseThrow().task().id());execution.startSubmission(task.id(),token,now);execution.accepted(task.id(),"accepted",now,now);execution.saving(task.id(),token,"https://audio.test/result.mp3",now);
   execution.archiveFailed(task.id(),token,5,true,now,now);assertEquals(0,credits.balance(owner));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,work.id()));
   generation.retryArchive(owner,work.id(),task.id());assertEquals(0,credits.balance(owner));
  }finally{execution.release(task.id(),token);execution.unlockProvider("credits-offline",token);jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",task.id());}
 }

 @Test void idempotencyRefundAndRollback(){String owner=identity.create().creatorId();credits.credit(owner,20,"order-1");credits.credit(owner,20,"order-1");assertThrows(DomainException.class,()->credits.credit(owner,30,"order-1"));credits.debit(owner,10,"task-1");credits.debit(owner,10,"task-1");assertEquals(10,credits.balance(owner));credits.refund(owner,"task-1");credits.refund(owner,"task-1");assertEquals(20,credits.balance(owner));assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{credits.debit(owner,10,"rollback");throw new IllegalStateException();}));assertEquals(20,credits.balance(owner));}
 @Test void concurrentDebitsCannotOverspend() throws Exception {String owner=identity.create().creatorId();credits.credit(owner,10,"order-2");var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->attempt(owner,"a",start));var b=pool.submit(()->attempt(owner,"b",start));start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(0,credits.balance(owner));}finally{pool.shutdownNow();}}
 @Test void generationChargesOnceAndCancellationRefunds(){
  String owner=identity.create().creatorId();credits.credit(owner,15,"generation-funding");
  var work=works.create(owner,new com.qingfeng.music.work.api.WorkApi.Draft("朋友","生日","故事","祝福","民谣","歌曲"));
  var revision=lyrics.save(owner,work.id(),0,"歌名","歌词");lyrics.confirm(owner,work.id(),revision.id());
  String key=java.util.UUID.randomUUID().toString();var task=generation.submit(owner,work.id(),revision.id(),key);
  assertEquals(task.id(),generation.submit(owner,work.id(),revision.id(),key).id());assertEquals(0,credits.balance(owner));
  generation.cancel(owner,work.id(),task.id());generation.cancel(owner,work.id(),task.id());assertEquals(15,credits.balance(owner));
 }
 @Test void unknownIsNotRefundedButDefinitiveFailureIsRefundedOnce(){
  String owner=identity.create().creatorId();credits.credit(owner,15,"failure-funding");
  var work=works.create(owner,new com.qingfeng.music.work.api.WorkApi.Draft("朋友","生日","故事","祝福","民谣","歌曲"));
  var revision=lyrics.save(owner,work.id(),0,"歌名","歌词");lyrics.confirm(owner,work.id(),revision.id());
  var task=generation.submit(owner,work.id(),revision.id(),java.util.UUID.randomUUID().toString());
  var now=java.time.Instant.now().plusSeconds(10);String token=java.util.UUID.randomUUID().toString();
  assertTrue(execution.lockProvider("credits-offline",token,now,now.plusSeconds(120)));
  var claim=execution.claim("credits-offline",token,now,now.plusSeconds(120)).orElseThrow();assertEquals(task.id(),claim.task().id());
  assertTrue(execution.startSubmission(task.id(),token,now));execution.unknown(task.id(),token,"SUBMISSION_UNKNOWN",now);assertEquals(0,credits.balance(owner));
  execution.failed(task.id(),token,"PROVIDER_FAILED",now);execution.failed(task.id(),token,"PROVIDER_FAILED",now);assertEquals(15,credits.balance(owner));
  execution.unlockProvider("credits-offline",token);
 }
 @Test void accountSwitchCannotAbandonCredits(){
  String sub=java.util.UUID.randomUUID().toString();var first=identity.create();identity.signInWithGoogle(first.token(),first.creatorId(),sub,null,null);
  var other=identity.create();credits.credit(other.creatorId(),10,"identity-test");
  assertEquals("GOOGLE_ACCOUNT_CONFLICT",assertThrows(DomainException.class,()->identity.signInWithGoogle(other.token(),other.creatorId(),sub,null,null)).code());
  assertEquals(other.creatorId(),identity.resolve(other.token()).orElseThrow());assertEquals(10,credits.balance(other.creatorId()));
 }
 @Test void referralCreditsInviterOnlyAfterFirstLogin(){
  var inviter=identity.create();identity.signInWithGoogle(inviter.token(),inviter.creatorId(),java.util.UUID.randomUUID().toString(),null,null);
  var guest=identity.create();var principal=new com.qingfeng.music.server.security.CreatorPrincipal(guest.creatorId());
  referrals.accept(principal,new com.qingfeng.music.server.web.app.ReferralController.Invite(inviter.creatorId()));
  assertEquals(100,credits.balance(inviter.creatorId()));
  identity.signInWithGoogle(guest.token(),guest.creatorId(),java.util.UUID.randomUUID().toString(),null,null);
  assertEquals(120,credits.balance(inviter.creatorId()));assertEquals(100,credits.balance(guest.creatorId()));
 }
 boolean attempt(String owner,String ref,CountDownLatch start)throws Exception{start.await();try{credits.debit(owner,10,ref);return true;}catch(DomainException e){assertEquals("INSUFFICIENT_CREDITS",e.code());return false;}}
}
