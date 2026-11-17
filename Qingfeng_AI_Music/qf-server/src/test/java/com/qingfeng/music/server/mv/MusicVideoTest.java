package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.credits.CreditsLedger;
import java.util.*;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MusicVideoTest {
 JdbcTemplate db;TransactionTemplate tx;MusicVideoService service;VideoProvider provider;CreditsLedger credits;MediaApi media;AudioDuration duration;VideoImages images;
 @BeforeEach void setup(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:mv"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");db.execute("INSERT INTO creator VALUES('owner')");db.execute("CREATE TABLE agent_admission_lock(id INT PRIMARY KEY)");db.execute("INSERT INTO agent_admission_lock VALUES(1)");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V47__music_video_projects.sql")).execute(ds);provider=mock(VideoProvider.class);when(provider.available()).thenReturn(true);credits=mock(CreditsLedger.class);media=mock(MediaApi.class);duration=mock(AudioDuration.class);when(duration.millis(anyString(),anyString(),anyString())).thenReturn(5000L);images=mock(VideoImages.class);service=new MusicVideoService(db,tx,mock(WorkApi.class),media,provider,credits,duration,images);}
 MusicVideoService.Plan plan(){return new MusicVideoService.Plan("旅行","16:9","720P",5000,List.of(new MusicVideoService.Shot("同一位旅人沿着海岸走向日落，电影风格",5)));}
 String create(){return service.create("owner","work","asset","request",plan());}
 @Test void duplicateRequestIsOneDebitAndNoProviderCalls(){String id=create();assertEquals(id,create());verify(credits,times(1)).debit("owner",10,"music-video:"+id);verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->service.get("other",id));}
 @Test void differentRequestKeyCannotChargeAgainWhileSameAudioIsActive(){
  String id=create();
  for(String state:List.of("QUEUED","RUNNING","ASSEMBLING","UNCERTAIN")){
   db.update("UPDATE music_video_project SET state=? WHERE id=?",state,id);
   var error=assertThrows(com.qingfeng.music.common.exception.DomainException.class,()->service.create("owner","work","asset","another-request",plan()));
   assertEquals("VIDEO_ALREADY_ACTIVE",error.code());assertEquals(id,create());
  }
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM music_video_project",Integer.class));
  verify(credits,times(1)).debit(anyString(),anyLong(),anyString());
 }
 @Test void completedTaskAllowsNewRequestAndOtherAudioCanRunIndependently(){
  String first=create();
  String other=service.create("owner","work","another-asset","other-version",plan());assertNotEquals(first,other);
  db.update("UPDATE music_video_project SET state='READY' WHERE id=?",first);
  String next=service.create("owner","work","asset","new-request",plan());assertNotEquals(first,next);
  verify(credits,times(3)).debit(anyString(),anyLong(),anyString());
 }
 @Test void activeTasksStayVisibleBeyondHistoryLimit(){
  String active=create();
  for(int i=0;i<25;i++)db.update("INSERT INTO music_video_project(id,creator_id,work_id,asset_id,request_key,fingerprint,state,plan_json,created_at) VALUES(?,?,?,?,?,?,'READY','{}',?)",UUID.randomUUID().toString(),"owner","work","asset","past-"+i,"hash",java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(i+1)));
  assertEquals(active,service.list("owner","work").get(0).get("id"));
 }
 @Test void cancelQueuedRefundsOnceAndNeverStarts(){String id=create();service.cancel("owner",id);assertThrows(RuntimeException.class,()->service.cancel("owner",id));verify(credits,times(1)).refund("owner","music-video:"+id);assertEquals("CANCELLED",service.get("owner",id).get("state"));}
 @Test void uncertainSubmissionNeverResubmits(@org.junit.jupiter.api.io.TempDir Path root){String id=create();when(provider.submit(anyString(),anyInt(),anyString(),anyString(),anyString())).thenThrow(new VideoProvider.Failure("VIDEO_SUBMIT_UNCERTAIN",true));var worker=new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg");worker.tick();worker.tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,times(1)).submit(anyString(),anyInt(),anyString(),anyString(),anyString());verify(credits,never()).refund(anyString(),anyString());}
 @Test void processRestartDoesNotResubmitInterruptedPost(@org.junit.jupiter.api.io.TempDir Path root){String id=create();db.update("UPDATE music_video_shot SET state='SUBMITTING' WHERE project_id=?",id);new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());}
 @Test void retryFailedShotPreservesCompletedShotsAndDoesNotDebitAgain(){
  String id=create();db.update("UPDATE music_video_project SET state='FAILED' WHERE id=?",id);db.update("UPDATE music_video_shot SET state='FAILED',provider_task_id='old-task' WHERE project_id=?",id);
  assertThrows(RuntimeException.class,()->service.retryShot("other",id,0,"新的画面"));
  assertThrows(RuntimeException.class,()->service.retryShot("owner",id,0,plan().shots().get(0).prompt()));
  db.update("INSERT INTO music_video_shot(id,project_id,shot_index,prompt,seconds,state,provider_task_id) VALUES(?,?,1,'completed',5,'READY','retained-task')",UUID.randomUUID().toString(),id);
  service.retryShot("owner",id,0,"海浪与远方灯塔的水彩插画，无人物");
  assertEquals("retained-task",db.queryForObject("SELECT provider_task_id FROM music_video_shot WHERE project_id=? AND state='READY'",String.class,id));
  assertEquals("QUEUED",service.get("owner",id).get("state"));
  assertNull(db.queryForObject("SELECT provider_task_id FROM music_video_shot WHERE project_id=? AND shot_index=0",String.class,id));
  assertThrows(RuntimeException.class,()->service.retryShot("owner",id,0,"其他场景"));
  verify(credits,times(1)).debit(anyString(),anyLong(),anyString());
 }
 @Test void faceRejectionIsRecordedWithoutRetrying(){
  String id=create();db.update("UPDATE music_video_shot SET state='RUNNING',provider_task_id='task' WHERE project_id=?",id);when(provider.status("task")).thenReturn("face_rejected");
  var worker=new MusicVideoWorker(db,tx,provider,media,Path.of("target/test-video-failure"),"ffmpeg");worker.tick();
  assertEquals("VIDEO_FACE_REJECTED",service.get("owner",id).get("error_code"));verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
 }
 @Test void admitsFiveGloballyWithoutDuplicateSubmissions(@org.junit.jupiter.api.io.TempDir Path root){
  when(duration.millis(anyString(),anyString(),anyString())).thenReturn(270000L);
  var plan=new MusicVideoService.Plan("x","16:9","480P",270000,Collections.nCopies(9,new MusicVideoService.Shot("海岸场景",30)));
  String id=service.create("owner","work","asset","nine",plan);
  when(provider.submit(anyString(),anyInt(),anyString(),anyString(),anyString())).thenAnswer(call->"task-"+call.getArgument(4));
  when(provider.status(anyString())).thenReturn("running");
  var worker=new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg");worker.tick();
  assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE state='RUNNING'",Integer.class));
  new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();verify(provider,times(5)).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
  service.create("owner","other-work","other-asset","second",plan);worker.tick();
  assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE state='RUNNING'",Integer.class));
  db.update("UPDATE music_video_shot SET state='READY' WHERE project_id=? AND shot_index=0",id);
  worker.tick();assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM music_video_shot WHERE state='RUNNING'",Integer.class));
  verify(provider,times(6)).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
 }
 @Test void failureStillCollectsOtherSubmittedShots(@org.junit.jupiter.api.io.TempDir Path root)throws Exception{
  when(duration.millis(anyString(),anyString(),anyString())).thenReturn(90000L);
  var plan=new MusicVideoService.Plan("x","16:9","480P",90000,Collections.nCopies(3,new MusicVideoService.Shot("海岸",30)));
  String id=service.create("owner","work","asset","siblings",plan);
  db.update("UPDATE music_video_shot SET state='RUNNING',provider_task_id='bad' WHERE project_id=? AND shot_index=0",id);
  db.update("UPDATE music_video_shot SET state='RUNNING',provider_task_id='good' WHERE project_id=? AND shot_index=1",id);
  when(provider.status("bad")).thenReturn("face_rejected");when(provider.status("good")).thenReturn("succeeded");
  doAnswer(call->{java.nio.file.Files.writeString(call.getArgument(1,Path.class),"video");return null;}).when(provider).download(eq("good"),any(Path.class));
  new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();
  assertEquals("FAILED",service.get("owner",id).get("state"));
  assertEquals("READY",db.queryForObject("SELECT state FROM music_video_shot WHERE project_id=? AND shot_index=1",String.class,id));
  verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
 }
 @Test void disabledProviderDoesNotCharge(){when(provider.available()).thenReturn(false);assertThrows(RuntimeException.class,this::create);verifyNoInteractions(credits);}
 @Test void invalidStoryboardRejected(){assertThrows(RuntimeException.class,()->MusicVideoService.validate(new MusicVideoService.Plan("x","16:9","invalid",5000,List.of(new MusicVideoService.Shot("x",99)))));}
 @Test void pollDeadlineParksOriginalTaskWithoutResubmitting(@org.junit.jupiter.api.io.TempDir Path root){String id=create();db.update("UPDATE music_video_shot SET state='RUNNING',provider_task_id='upstream',updated_at=? WHERE project_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7300)),id);new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());}
 @Test void retryAssemblyRequiresAllReadyAndDoesNotDebit(){String id=create();db.update("UPDATE music_video_project SET state='ASSEMBLY_FAILED' WHERE id=?",id);assertThrows(RuntimeException.class,()->service.retryAssembly("owner",id));db.update("UPDATE music_video_shot SET state='READY' WHERE project_id=?",id);service.retryAssembly("owner",id);assertEquals("ASSEMBLING",service.get("owner",id).get("state"));verify(credits,times(1)).debit(anyString(),anyLong(),anyString());}
 @Test void debitFailureRollsBackProject(){doThrow(new IllegalStateException("insufficient")).when(credits).debit(anyString(),anyLong(),anyString());assertThrows(RuntimeException.class,this::create);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM music_video_project",Integer.class));}
 @Test void changedRequestCannotReuseKey(){create();assertThrows(RuntimeException.class,()->service.create("owner","work","asset","request",new MusicVideoService.Plan("其他","16:9","720P",5000,plan().shots())));verify(credits,times(1)).debit(anyString(),anyLong(),anyString());}
 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="QF_VIDEO_TEST_FFMPEG",matches=".+")
 void assemblesRealMp4WithOriginalAudio(@org.junit.jupiter.api.io.TempDir Path root)throws Exception{
  String id=create(),ffmpeg=System.getenv("QF_VIDEO_TEST_FFMPEG");var worker=new MusicVideoWorker(db,tx,provider,media,root,ffmpeg);
  var folder=worker.folder(id);java.nio.file.Files.createDirectories(folder);
  String shot=db.queryForObject("SELECT id FROM music_video_shot WHERE project_id=?",String.class,id);
  var source=folder.resolve(shot+".mp4");var audio=folder.resolve("fixture.wav");
  assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-y","-loglevel","error","-f","lavfi","-i","color=c=blue:s=320x180:d=5","-c:v","libx264",source.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor());
  assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-y","-loglevel","error","-f","lavfi","-i","sine=frequency=440:duration=5",audio.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor());
  when(media.open("owner","work","asset")).thenReturn(new MediaApi.Content(java.nio.file.Files.size(audio),"audio/wav","song.wav",()->java.nio.file.Files.newInputStream(audio)));
  db.update("UPDATE music_video_shot SET state='READY' WHERE project_id=?",id);worker.tick();
  assertEquals("READY",service.get("owner",id).get("state"));assertTrue(java.nio.file.Files.size(folder.resolve("result.mp4"))>1000);
  assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-loglevel","error","-i",folder.resolve("result.mp4").toString(),"-map","0:v:0","-map","0:a:0","-f","null","-").redirectError(ProcessBuilder.Redirect.INHERIT).start().waitFor());
  verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
 }
 @Test void fullSongDurationAndResolutionDetermineQuoteAndIgnoreModelTiming(){
  when(duration.millis(anyString(),anyString(),anyString())).thenReturn(180100L);
  var requested=new MusicVideoService.Plan("x","16:9","1080P",1,List.of(new MusicVideoService.Shot("故事",2)));
  var prepared=service.prepare("owner","work","asset",requested);
  assertEquals(180100,prepared.durationMillis());assertEquals(7,prepared.shots().size());assertEquals(181,prepared.shots().stream().mapToInt(MusicVideoService.Shot::seconds).sum());assertEquals(543,MusicVideoService.price(prepared));
  assertThrows(RuntimeException.class,()->service.create("owner","work","asset","bad",requested));verifyNoInteractions(credits);
 }
 @Test void imageTimeIsFreeAndCannotExceedItsSegment(){
  when(duration.millis(anyString(),anyString(),anyString())).thenReturn(60000L);
  var requested=new MusicVideoService.Plan("x","16:9","720P",60000,List.of(new MusicVideoService.Shot("开场",30,"image-a",5),new MusicVideoService.Shot("结局",30,"image-b",10)));
  var prepared=service.prepare("owner","work","asset",requested);assertEquals(90,MusicVideoService.price(prepared));
  String id=service.create("owner","work","asset","images",prepared);verify(credits).debit("owner",90,"music-video:"+id);verify(images,atLeastOnce()).resolve("owner","work","image-a");
  var invalid=new MusicVideoService.Plan("x","16:9","720P",60000,List.of(new MusicVideoService.Shot("x",30,"image",31),new MusicVideoService.Shot("x",30)));
  assertThrows(RuntimeException.class,()->service.prepare("owner","work","asset",invalid));
 }
 @Test void allImageVideoHasNoChargeOrProviderSubmission(@org.junit.jupiter.api.io.TempDir Path root){
  var p=new MusicVideoService.Plan("x","16:9","480P",5000,List.of(new MusicVideoService.Shot("",5,"image",5)));
  String id=service.create("owner","work","asset","free",p);new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg",images).tick();
  verifyNoInteractions(credits);verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString(),anyString());
  assertEquals("READY",db.queryForObject("SELECT state FROM music_video_shot WHERE project_id=?",String.class,id));
 }
 @Test void changedAudioDurationCannotChargeOldConfirmedQuote(){when(duration.millis(anyString(),anyString(),anyString())).thenReturn(6000L);assertThrows(RuntimeException.class,this::create);verifyNoInteractions(credits);}
 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="QF_VIDEO_TEST_FFMPEG",matches=".+")
 void assemblesFreeEndingImageAndKeepsFullAudio(@org.junit.jupiter.api.io.TempDir Path root)throws Exception{
  String ffmpeg=System.getenv("QF_VIDEO_TEST_FFMPEG");var imageStore=new VideoImages(root.resolve("images"));
  var bitmap=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_RGB);var g=bitmap.createGraphics();g.setColor(java.awt.Color.RED);g.fillRect(0,0,64,64);g.dispose();
  var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(bitmap,"png",bytes);String image=imageStore.save("owner","work",new java.io.ByteArrayInputStream(bytes.toByteArray()));
  when(duration.millis(anyString(),anyString(),anyString())).thenReturn(8000L);
  service=new MusicVideoService(db,tx,mock(WorkApi.class),media,provider,credits,duration,imageStore);
  var plan=new MusicVideoService.Plan("x","16:9","480P",8000,List.of(new MusicVideoService.Shot("海边",8,image,3)));
  String id=service.create("owner","work","asset","image-assembly",plan);var worker=new MusicVideoWorker(db,tx,provider,media,root,ffmpeg,imageStore);
  Path folder=worker.folder(id);java.nio.file.Files.createDirectories(folder);String shot=db.queryForObject("SELECT id FROM music_video_shot WHERE project_id=?",String.class,id);
  var audio=folder.resolve("fixture.wav");
  assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-y","-loglevel","error","-f","lavfi","-i","color=c=blue:s=320x180:d=5","-c:v","libx264",folder.resolve(shot+".mp4").toString()).start().waitFor());
  assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-y","-loglevel","error","-f","lavfi","-i","sine=frequency=440:duration=8",audio.toString()).start().waitFor());
  when(media.open("owner","work","asset")).thenReturn(new MediaApi.Content(java.nio.file.Files.size(audio),"audio/wav","song.wav",()->java.nio.file.Files.newInputStream(audio)));
  db.update("UPDATE music_video_shot SET state='READY' WHERE project_id=?",id);worker.tick();assertEquals("READY",service.get("owner",id).get("state"));
  Path frame=folder.resolve("last.png");assertEquals(0,new ProcessBuilder(ffmpeg,"-nostdin","-y","-loglevel","error","-sseof","-1","-i",folder.resolve("result.mp4").toString(),"-frames:v","1",frame.toString()).start().waitFor());
  var actual=javax.imageio.ImageIO.read(frame.toFile());assertEquals(854,actual.getWidth());assertEquals(480,actual.getHeight());var pixel=new java.awt.Color(actual.getRGB(actual.getWidth()/2,actual.getHeight()/2));assertTrue(pixel.getRed()>200&&pixel.getBlue()<50,"ending still must be shown");
  String ffprobe=Path.of(ffmpeg).resolveSibling("ffprobe").toString();var probe=new ProbedAudioDuration(media,root.resolve("probes"),ffprobe);assertTrue(Math.abs(probe.millis("owner","work","asset")-8000)<50);
  when(media.open("owner","work","result")).thenReturn(new MediaApi.Content(java.nio.file.Files.size(folder.resolve("result.mp4")),"video/mp4","result.mp4",()->java.nio.file.Files.newInputStream(folder.resolve("result.mp4"))));
  assertTrue(Math.abs(probe.millis("owner","work","result")-8000)<100);verify(credits).debit("owner",5,"music-video:"+id);
 }

}
