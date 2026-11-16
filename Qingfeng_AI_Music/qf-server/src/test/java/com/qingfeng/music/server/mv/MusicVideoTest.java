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
 JdbcTemplate db;TransactionTemplate tx;MusicVideoService service;VideoProvider provider;CreditsLedger credits;MediaApi media;
 @BeforeEach void setup(){var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:mv"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));db.execute("CREATE TABLE creator(id VARCHAR(36) PRIMARY KEY)");db.execute("INSERT INTO creator VALUES('owner')");db.execute("CREATE TABLE agent_admission_lock(id INT PRIMARY KEY)");db.execute("INSERT INTO agent_admission_lock VALUES(1)");new ResourceDatabasePopulator(new ClassPathResource("db/migration/V47__music_video_projects.sql")).execute(ds);provider=mock(VideoProvider.class);when(provider.available()).thenReturn(true);credits=mock(CreditsLedger.class);media=mock(MediaApi.class);service=new MusicVideoService(db,tx,mock(WorkApi.class),media,provider,credits,8);}
 MusicVideoService.Plan plan(){return new MusicVideoService.Plan("旅行","16:9",List.of(new MusicVideoService.Shot("同一位旅人沿着海岸走向日落，电影风格",5)));}
 String create(){return service.create("owner","work","asset","request",plan());}
 @Test void duplicateRequestIsOneDebitAndNoProviderCalls(){String id=create();assertEquals(id,create());verify(credits,times(1)).debit("owner",8,"music-video:"+id);verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString());assertThrows(RuntimeException.class,()->service.get("other",id));}
 @Test void cancelQueuedRefundsOnceAndNeverStarts(){String id=create();service.cancel("owner",id);assertThrows(RuntimeException.class,()->service.cancel("owner",id));verify(credits,times(1)).refund("owner","music-video:"+id);assertEquals("CANCELLED",service.get("owner",id).get("state"));}
 @Test void uncertainSubmissionNeverResubmits(@org.junit.jupiter.api.io.TempDir Path root){String id=create();when(provider.submit(anyString(),anyInt(),anyString(),anyString())).thenThrow(new VideoProvider.Failure("VIDEO_SUBMIT_UNCERTAIN",true));var worker=new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg");worker.tick();worker.tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,times(1)).submit(anyString(),anyInt(),anyString(),anyString());verify(credits,never()).refund(anyString(),anyString());}
 @Test void processRestartDoesNotResubmitInterruptedPost(@org.junit.jupiter.api.io.TempDir Path root){String id=create();db.update("UPDATE music_video_shot SET state='SUBMITTING' WHERE project_id=?",id);new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString());}
 @Test void disabledProviderDoesNotCharge(){when(provider.available()).thenReturn(false);assertThrows(RuntimeException.class,this::create);verifyNoInteractions(credits);}
 @Test void invalidStoryboardRejected(){assertThrows(RuntimeException.class,()->MusicVideoService.validate(new MusicVideoService.Plan("x","16:9",List.of(new MusicVideoService.Shot("x",99)))));}
 @Test void pollDeadlineParksOriginalTaskWithoutResubmitting(@org.junit.jupiter.api.io.TempDir Path root){String id=create();db.update("UPDATE music_video_shot SET state='RUNNING',provider_task_id='upstream',updated_at=? WHERE project_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(7300)),id);new MusicVideoWorker(db,tx,provider,media,root,"ffmpeg").tick();assertEquals("UNCERTAIN",service.get("owner",id).get("state"));verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString());}
 @Test void retryAssemblyRequiresAllReadyAndDoesNotDebit(){String id=create();db.update("UPDATE music_video_project SET state='ASSEMBLY_FAILED' WHERE id=?",id);assertThrows(RuntimeException.class,()->service.retryAssembly("owner",id));db.update("UPDATE music_video_shot SET state='READY' WHERE project_id=?",id);service.retryAssembly("owner",id);assertEquals("ASSEMBLING",service.get("owner",id).get("state"));verify(credits,times(1)).debit(anyString(),anyLong(),anyString());}
 @Test void debitFailureRollsBackProject(){doThrow(new IllegalStateException("insufficient")).when(credits).debit(anyString(),anyLong(),anyString());assertThrows(RuntimeException.class,this::create);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM music_video_project",Integer.class));}
 @Test void changedRequestCannotReuseKey(){create();assertThrows(RuntimeException.class,()->service.create("owner","work","asset","request",new MusicVideoService.Plan("其他","16:9",plan().shots())));verify(credits,times(1)).debit(anyString(),anyLong(),anyString());}
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
  verify(provider,never()).submit(anyString(),anyInt(),anyString(),anyString());
 }
}
