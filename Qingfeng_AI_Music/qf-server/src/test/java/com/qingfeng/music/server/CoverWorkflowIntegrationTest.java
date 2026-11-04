package com.qingfeng.music.server;
import com.qingfeng.music.cover.api.*;
import com.qingfeng.music.cover.application.*;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.common.exception.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.*;
import java.io.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cover_workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1") @ActiveProfiles("test") @Import(CoverWorkflowIntegrationTest.Offline.class)
class CoverWorkflowIntegrationTest {
 @TestConfiguration(proxyBeanMethods=false)static class Offline{
  @Bean @Primary FakeImages fakeImages(){return new FakeImages();}
  @Bean @Primary ImageResultDownloader fakeImageDownload(){return url->new ByteArrayInputStream(image());}
 }
 static class FakeImages implements ImageEditProvider{int submits,queries;boolean unknown;public boolean configured(){return true;}public String model(){return "google/nano-banana-edit";}public String submit(byte[] photo,String prompt,String model){submits++;assertTrue(photo.length>0);if(unknown)throw new IllegalStateException("offline response lost");return "offline-image-"+UUID.randomUUID();}public Result query(String task,String model){queries++;return new Result("SAVING","https://cdn.example/cover.png");}}
 @Autowired CoverService covers;@Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired FakeImages provider;@Autowired JdbcTemplate jdbc;
 @Autowired LyricsApi lyrics;@Autowired MediaApi media;@Autowired SharingApi sharing;
 static byte[] image(){try{var b=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB),"png",b);return b.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
 record F(String owner,String work){}
 F fixture(){String owner=identity.create().creatorId();return new F(owner,works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","封面测试")).id());}
 @BeforeEach void reset(){jdbc.update("UPDATE cover_edit_task SET status='FAILED'");provider.submits=0;provider.queries=0;provider.unknown=false;}
 String upload(F f){return covers.upload(f.owner(),f.work(),new ByteArrayInputStream(image())).id();}
 void run(){jdbc.update("UPDATE cover_edit_task SET next_attempt_at=TIMESTAMP '2020-01-01 00:00:00'");covers.runOnce();}
 @Test void uploadsAreDecodedReencodedOwnedAndSelectedWithOptimisticConcurrency()throws Exception{
  var f=fixture();String id=upload(f);assertNull(covers.state(f.owner(),f.work()).currentCoverId());
  var state=covers.select(f.owner(),f.work(),id,0);assertEquals(id,state.currentCoverId());assertEquals(1,state.version());
  try(var in=covers.open(f.owner(),f.work(),id).source().open()){var decoded=ImageIO.read(in);assertEquals(1024,decoded.getWidth());assertEquals(1024,decoded.getHeight());}
  var other=fixture();assertThrows(DomainException.class,()->covers.open(other.owner(),f.work(),id));assertThrows(DomainException.class,()->covers.select(other.owner(),other.work(),id,0));
  assertEquals("COVER_VERSION_CONFLICT",assertThrows(DomainException.class,()->covers.select(f.owner(),f.work(),null,0)).code());assertEquals(id,covers.state(f.owner(),f.work()).currentCoverId());assertEquals(0,provider.submits);
 }
 @Test void invalidImagesAreRejectedWithoutChargingCapacity(){
  var f=fixture();long before=jdbc.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,f.owner());
  assertThrows(DomainException.class,()->covers.upload(f.owner(),f.work(),new ByteArrayInputStream("<svg onload='alert(1)'/>".getBytes())));
  assertThrows(DomainException.class,()->covers.upload(f.owner(),f.work(),new ByteArrayInputStream(new byte[5*1024*1024+1])));
  assertEquals(before,jdbc.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,f.owner()));assertTrue(covers.state(f.owner(),f.work()).assets().isEmpty());
 }
 @Test void aiEditingIsExplicitIdempotentAndKeepsOriginalUntilUserAppliesNewCover(){
  var f=fixture();String source=upload(f);covers.select(f.owner(),f.work(),source,0);String key=UUID.randomUUID().toString();
  var edit=covers.edit(f.owner(),f.work(),source,"温暖胶片，不加文字",key);assertEquals(0,provider.submits);assertEquals(edit.id(),covers.edit(f.owner(),f.work(),source,"温暖胶片，不加文字",key).id());
  assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,()->covers.edit(f.owner(),f.work(),source,"改成水彩",key)).code());
  run();run();run();var state=covers.state(f.owner(),f.work());var finished=state.edits().get(0);assertEquals("SUCCEEDED",finished.status());assertEquals(2,state.assets().size());assertEquals(source,state.currentCoverId());assertEquals(1,provider.submits);
  assertNotEquals(source,finished.coverId());covers.select(f.owner(),f.work(),finished.coverId(),state.version());assertEquals(finished.coverId(),covers.state(f.owner(),f.work()).currentCoverId());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='COVER'",Integer.class,f.work()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=? AND kind='MUSIC'",Integer.class,f.work()));
 }
 @Test void ambiguousImageSubmissionIsNeverAutomaticallyResubmitted(){var f=fixture();String id=upload(f);provider.unknown=true;var edit=covers.edit(f.owner(),f.work(),id,"胶片",UUID.randomUUID().toString());run();run();assertEquals("SUBMISSION_UNKNOWN",covers.state(f.owner(),f.work()).edits().get(0).status());assertEquals(1,provider.submits);assertEquals(edit.id(),covers.state(f.owner(),f.work()).edits().get(0).id());}
 @Test void giftCoverIsImmutableAndRevocationStopsItsAccess()throws Exception{
  var f=fixture();String first=upload(f);covers.select(f.owner(),f.work(),first,0);var revision=lyrics.save(f.owner(),f.work(),0,"歌名","中文与 English");lyrics.confirm(f.owner(),f.work(),revision.id());
  var wave=java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN);wave.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);
  var audio=media.importAudio(f.owner(),f.work(),new ByteArrayInputStream(wave.array()));var gift=sharing.create(f.owner(),f.work(),revision.id(),audio.id());String second=upload(f);covers.select(f.owner(),f.work(),second,1);
  assertTrue(sharing.get(gift.token()).hasCover());try(var original=covers.open(f.owner(),f.work(),first).source().open();var published=sharing.cover(gift.token()).source().open()){assertArrayEquals(original.readAllBytes(),published.readAllBytes());}
  sharing.revokeAll(f.owner(),f.work());assertEquals("SHARE_NOT_FOUND",assertThrows(DomainException.class,()->sharing.cover(gift.token())).code());
 }
}
