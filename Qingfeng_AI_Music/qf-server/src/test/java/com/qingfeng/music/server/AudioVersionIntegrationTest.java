package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.server.artist.ArtistProfileService;
import java.io.ByteArrayInputStream;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:audio_versions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AudioVersionIntegrationTest {
 @Autowired com.qingfeng.music.lyrics.api.LyricsApi lyrics;
 @Autowired IdentityApi identity; @Autowired WorkApi works; @Autowired MediaApi media;
 @Autowired ArtistProfileService profiles; @Autowired SharingApi shares; @Autowired JdbcTemplate db;
 @Autowired PlatformTransactionManager manager;
 record Fixture(String owner,String work,MediaApi.Asset first,MediaApi.Asset second){}
 static byte[] wav(){return ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0).array();}
 Fixture fixture(){
  String owner=identity.create().creatorId();String work=works.create(owner,new WorkApi.Draft("朋友","生日","故事","祝福","民谣","版本测试")).id();
  var first=media.importAudio(owner,work,new ByteArrayInputStream(wav()));var second=media.importAudio(owner,work,new ByteArrayInputStream(wav()));
  return new Fixture(owner,work,first,second);
 }
 void code(String expected,Runnable action){assertEquals(expected,assertThrows(DomainException.class,action::run).code());}
 @Test void notesPersistClearAndRejectStaleWrites(){
  var f=fixture();var saved=media.saveNote(f.owner,f.work,f.first.id(),"  婚礼入场\n轻快版  ",0);
  assertEquals("婚礼入场\n轻快版",saved.note());assertEquals(1,saved.noteVersion());
  assertEquals(saved.note(),media.list(f.owner,f.work).stream().filter(a->a.id().equals(f.first.id())).findFirst().orElseThrow().note());
  code("AUDIO_NOTE_CONFLICT",()->media.saveNote(f.owner,f.work,f.first.id(),"旧页面",0));
  assertEquals("",media.saveNote(f.owner,f.work,f.first.id(),"",1).note());
 }
 @Test void validatesLengthAndOwnerAndWorkBoundary(){
  var f=fixture();var other=fixture();
  code("INVALID_AUDIO_NOTE",()->media.saveNote(f.owner,f.work,f.first.id(),"中".repeat(501),0));
  assertThrows(DomainException.class,()->media.saveNote(other.owner,f.work,f.first.id(),"越权",0));
  assertThrows(DomainException.class,()->media.deleteVersion(other.owner,f.work,f.first.id()));
  code("AUDIO_NOTE_CONFLICT",()->media.saveNote(f.owner,f.work,other.first.id(),"串作品",0));
  assertEquals(2,media.list(f.owner,f.work).size());
 }
 @Test void deleteIsIdempotentRetainsFilesAndAccountingAndBlocksReselectAndNote(){
  var f=fixture();long bytes=db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,f.owner);
  media.deleteVersion(f.owner,f.work,f.first.id());media.deleteVersion(f.owner,f.work,f.first.id());
  assertEquals(List.of(f.second.id()),media.list(f.owner,f.work).stream().map(MediaApi.Asset::id).toList());
  assertEquals(f.second.id(),media.selection(f.owner,f.work).assetId());
  assertEquals(bytes,db.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,f.owner));
  assertTrue(media.open(f.owner,f.work,f.first.id()).size()>0);
  code("AUDIO_SELECTION_CONFLICT",()->media.select(f.owner,f.work,f.first.id(),media.selection(f.owner,f.work).version()));
  code("AUDIO_NOTE_CONFLICT",()->media.saveNote(f.owner,f.work,f.first.id(),"迟到备注",0));
 }
 @Test void selectedAndLastRecordingCannotBeDeleted(){
  var f=fixture();code("AUDIO_VERSION_SELECTED",()->media.deleteVersion(f.owner,f.work,f.second.id()));
  media.deleteVersion(f.owner,f.work,f.first.id());
  db.update("UPDATE work_audio_selection SET asset_id=NULL WHERE work_id=?",f.work);
  code("AUDIO_VERSION_LAST",()->media.deleteVersion(f.owner,f.work,f.second.id()));
 }
 @Test void publishedVersionProtectedUntilPrivateAndCannotRepublishDeleted(){
  var f=fixture();profiles.save(f.owner,"版本测试歌手","",null,false,null,false,null);
  profiles.publishSong(f.owner,f.work,f.first.id(),null,"公开歌曲");
  code("AUDIO_VERSION_PUBLISHED",()->media.deleteVersion(f.owner,f.work,f.first.id()));
  profiles.makePrivate(f.owner,f.work);media.deleteVersion(f.owner,f.work,f.first.id());
  code("MEDIA_NOT_FOUND",()->profiles.publishSong(f.owner,f.work,f.first.id(),null,"恢复已删版本"));
 }
 @Test void deletedVersionNumbersAreNotReused(){
  var f=fixture();db.update("UPDATE media_asset SET origin='GENERATED',created_at=TIMESTAMP '2020-01-01 00:00:00' WHERE id=?",f.first.id());
  db.update("UPDATE media_asset SET origin='GENERATED',created_at=TIMESTAMP '2020-01-02 00:00:00' WHERE id=?",f.second.id());
  assertEquals(2,media.get(f.owner,f.work,f.second.id()).versionNumber());
  media.deleteVersion(f.owner,f.work,f.first.id());
  assertEquals(2,media.list(f.owner,f.work).get(0).versionNumber());
 }
 @Test void deletingDoesNotBreakExistingShare(){
  var f=fixture();var revision=lyrics.save(f.owner,f.work,works.get(f.owner,f.work).version(),"分享","保留这首歌");lyrics.confirm(f.owner,f.work,revision.id());
  var share=shares.create(f.owner,f.work,revision.id(),f.first.id());
  media.deleteVersion(f.owner,f.work,f.first.id());
  assertNotNull(shares.audio(share.token()));
 }
 @Test void versionChangesParticipateInTheCallingTransaction(){
  var f=fixture();var before=media.selection(f.owner,f.work);
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   media.select(f.owner,f.work,f.first.id(),before.version());
   media.saveNote(f.owner,f.work,f.first.id(),"不应提交",0);
   tx.setRollbackOnly();
  });
  assertEquals(before.assetId(),media.selection(f.owner,f.work).assetId());
  assertEquals(before.version(),media.selection(f.owner,f.work).version());
  assertEquals("",media.get(f.owner,f.work,f.first.id()).note());
  new TransactionTemplate(manager).executeWithoutResult(tx->{media.deleteVersion(f.owner,f.work,f.first.id());tx.setRollbackOnly();});
  assertEquals(2,media.list(f.owner,f.work).size());
 }
 @Test void selectionAndRemovalSerializeOnRecordingLock()throws Exception{
  var f=fixture();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   var selected=pool.submit(()->new TransactionTemplate(manager).execute(tx->{
    db.queryForObject("SELECT id FROM media_asset WHERE id=? FOR UPDATE",String.class,f.first.id());
    locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
    db.update("UPDATE work_audio_selection SET asset_id=?,version=version+1 WHERE work_id=?",f.first.id(),f.work);return true;
   }));
   assertTrue(locked.await(10,TimeUnit.SECONDS));
   var removed=pool.submit(()->{try{media.deleteVersion(f.owner,f.work,f.first.id());return "removed";}catch(DomainException e){return e.code();}});
   release.countDown();assertTrue(selected.get(10,TimeUnit.SECONDS));assertEquals("AUDIO_VERSION_SELECTED",removed.get(10,TimeUnit.SECONDS));
   assertEquals(2,media.list(f.owner,f.work).size());
  }finally{release.countDown();pool.shutdownNow();}
 }
}

@EnabledIfEnvironmentVariable(named="QF_EXECUTION_MYSQL_TEST_PORT",matches="57[0-9]{3}")
@TestPropertySource(properties={
 "spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_EXECUTION_MYSQL_TEST_PORT}/qf_execution_concurrency_test?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC",
 "spring.datasource.username=root","spring.datasource.password=","spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver"})
class AudioVersionMySqlTest extends AudioVersionIntegrationTest {}
