package com.qingfeng.music.server;
import com.qingfeng.music.settings.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.GenerationWorker;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:music_settings;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true","qingfeng.generation.scheduler-enabled=false"})
@ActiveProfiles("test") @Import(MusicSettingsIntegrationTest.Offline.class)
class MusicSettingsIntegrationTest {
 @TestConfiguration(proxyBeanMethods=false) static class Offline{@Bean @Primary Provider settingsProvider(){return new Provider();}}
 static class Provider implements MusicProvider{Request received;public String name(){return "kie-suno";}public boolean isConfigured(){return true;}public Submission submit(Request r,String k){received=r;return new Submission("fake-existing");}public Result query(String id){return new Result(id,GenerationStatus.RUNNING,null);}}
 @Autowired MusicSettings settings;@Autowired IdentityApi identities;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi generation;@Autowired com.qingfeng.music.generation.application.ExecutionRepository executions;@Autowired JdbcTemplate jdbc;@Autowired Provider provider;
 @Autowired CreativePlan.Repository plans;
 @BeforeEach void reset(){jdbc.update("UPDATE generation_task SET status='FAILED' WHERE status<>'SUCCEEDED'");jdbc.update("DELETE FROM model_usage_reservation");provider.received=null;}
 MusicSettings.Preferences recipe(){var r=settings.catalog().recipes().get(0);return new MusicSettings.Preferences(1,r.id(),r.choices(),"FEMALE","人声突出，不要太悲伤","刺耳的高音");}
 record Fixture(String owner,WorkApi.Work work){}
 Fixture fixture(){String owner=identities.create().creatorId();return new Fixture(owner,works.create(owner,new WorkApi.Draft("妈妈","生日","一盏灯","谢谢你","温暖民谣","回家的灯")));}
 @Test void preflightAndSubmissionCompileTheReportedCaseWithoutChargingForPreview(){
  String owner=identities.create().creatorId();var work=works.create(owner,new WorkApi.Draft("","","海风与故乡","","中国风","海风","MALE"));
  var revision=lyrics.save(owner,work.id(),0,"海风","[Verse]\n海风吹过故乡");lyrics.confirm(owner,work.id(),revision.id());
  var plan=new CreativePlan("需".repeat(250),"建".repeat(186),"","摘要","summary","MALE");plans.save(work.id(),revision.id(),CreativePlan.sourceHash(work),plan);
  var before=works.get(owner,work.id());var preview=generation.preflight(owner,work.id(),revision.id());
  assertTrue(preview.styleCharacters()<=1000);assertEquals(revision.id(),preview.revisionId());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work.id()));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=?",Integer.class,work.id()));
  assertEquals(before,works.get(owner,work.id()));assertNull(provider.received);
  var task=generation.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());var frozen=settings.frozen(work.id(),task.id()).orElseThrow();
  assertEquals(preview.styleCharacters(),frozen.style().length());assertTrue(frozen.style().contains(plan.requirements()));assertTrue(frozen.style().contains(plan.suggestedArrangement()));
  assertEquals(plan,plans.find(work.id(),CreativePlan.sourceHash(work)).orElseThrow());
 }
 @Test void impossibleHardRequirementsFailBeforeTaskCreationAndRollBackMusicQuota(){
  var f=fixture();var prefs=recipe();var saved=settings.save(f.owner(),f.work().id(),0,new MusicSettings.Preferences(1,prefs.recipeId(),prefs.choices(),"FEMALE","要".repeat(300),""));
  var revision=lyrics.save(f.owner(),f.work().id(),1,"歌词保留","歌词完整保留");lyrics.confirm(f.owner(),f.work().id(),revision.id());
  plans.save(f.work().id(),revision.id(),CreativePlan.sourceHash(saved.work()),new CreativePlan("需".repeat(600),"建议","","","","AUTO"));
  assertThrows(DomainException.class,()->generation.preflight(f.owner(),f.work().id(),revision.id()));
  assertThrows(DomainException.class,()->generation.submit(f.owner(),f.work().id(),revision.id(),UUID.randomUUID().toString()));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,f.work().id()));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE kind='MUSIC' AND work_id=?",Integer.class,f.work().id()));
  assertEquals("歌词完整保留",lyrics.get(f.owner(),f.work().id(),revision.id()).lyrics());
 }
 @Test void savesStructuredPreferencesAndPreservesLegacyStoryFields(){var f=fixture();assertNull(settings.get(f.owner(),f.work().id()).direction());var saved=settings.save(f.owner(),f.work().id(),0,recipe());assertEquals(1,saved.work().version());assertEquals("一盏灯",saved.work().story());assertEquals("FEMALE",saved.work().vocalGender());assertEquals(saved.direction().preferences(),settings.get(f.owner(),f.work().id()).direction().preferences());assertTrue(saved.direction().style().contains("lead instrument: acoustic guitar"));assertTrue(saved.direction().negativeTags().contains("刺耳的高音"));}
 @Test void keepsTheAgentStructuredDescriptionThroughTheCatalogStyleAndIntoTheSubmittedRequest(){
  String owner=identities.create().creatorId();
  var work=works.create(owner,new WorkApi.Draft("","","海风与故乡","","温暖民谣 | 90 BPM, piano, Mandarin vocals","海风","FEMALE"));
  // 没有音乐设置时，Agent 的补充描述必须进入生成用描述。
  assertTrue(settings.forGeneration(owner,works.get(owner,work.id())).style().contains("90 BPM"));
  // 目录风格替换基础风格后，补充描述依然保留：它已经是用户看得见、改得动的自由要求。
  var saved=settings.save(owner,work.id(),0,new MusicSettings.Preferences(1,null,Map.of("genre",List.of("folk")),"FEMALE","90 BPM, piano, Mandarin vocals",""));
  assertFalse(saved.work().style().contains("90 BPM"),saved.work().style());
  var after=settings.forGeneration(owner,works.get(owner,work.id()));
  assertTrue(after.style().contains("90 BPM"),after.style());
  assertTrue(after.style().contains("piano"),after.style());
  // 提交给供应商的请求里同样带着这段描述。
  var revision=lyrics.save(owner,work.id(),saved.work().version(),"海风","[Verse]\n海风吹过故乡");lyrics.confirm(owner,work.id(),revision.id());
  var task=generation.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());
  var worker=new GenerationWorker(executions,provider,url->{throw new AssertionError();},(id,w,in)->{throw new AssertionError();},java.time.Clock.fixed(task.createdAt().plusSeconds(6),java.time.ZoneOffset.UTC),java.time.Duration.ofMinutes(2),2).withSettings(settings);
  assertTrue(worker.runOnce(),"queued task should be claimed");
  assertNotNull(provider.received);
  assertTrue(provider.received.style().contains("90 BPM"),provider.received.style());
  assertTrue(provider.received.style().contains("Mandarin vocals"),provider.received.style());
 }
 @Test void storesTheAgentStructuredDescriptionAndReadsItBackUnchanged(){
  String owner=identities.create().creatorId();
  var work=works.create(owner,new WorkApi.Draft("","","海风与故乡","","温暖民谣 | 90 BPM, piano, Cantonese vocals","海风","FEMALE"));
  // 落库后原样读回：BPM、乐器、语言都没有在持久化途中丢失。
  var stored=works.get(owner,work.id());
  assertEquals("温暖民谣 | 90 BPM, piano, Cantonese vocals",stored.style());
  assertEquals("FEMALE",stored.vocalGender());
  // 生成用描述里同样带着这段参数。
  assertTrue(settings.forGeneration(owner,stored).style().contains("90 BPM"));
  assertTrue(settings.forGeneration(owner,stored).style().contains("Cantonese vocals"));
 }
 @Test void theUserEditedFreeTextWinsOverTheAgentDescription(){
  String owner=identities.create().creatorId();
  var work=works.create(owner,new WorkApi.Draft("","","海风与故乡","","温暖民谣 | 90 BPM, piano","海风","FEMALE"));
  // 用户在音乐设置里改写补充要求：用户最新手动修改优先，Agent 的原始描述不再进入提交内容。
  var saved=settings.save(owner,work.id(),0,new MusicSettings.Preferences(1,null,Map.of("genre",List.of("folk")),"FEMALE","120 BPM, guitar",""));
  assertEquals(1,works.get(owner,work.id()).version());
  var after=settings.forGeneration(owner,works.get(owner,work.id()));
  assertTrue(after.style().contains("120 BPM"),after.style());
  assertTrue(after.style().contains("guitar"),after.style());
  assertFalse(after.style().contains("90 BPM"),after.style());
  assertFalse(after.style().contains("piano"),after.style());
 }
 @Test void theTaskSnapshotFreezesTheCompiledDescription(){
  String owner=identities.create().creatorId();
  var work=works.create(owner,new WorkApi.Draft("","","海风与故乡","","温暖民谣 | 90 BPM, piano, Mandarin vocals","海风","FEMALE"));
  var revision=lyrics.save(owner,work.id(),0,"海风","[Verse]\n海风吹过故乡");lyrics.confirm(owner,work.id(),revision.id());
  var task=generation.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());
  var frozen=settings.frozen(work.id(),task.id()).orElseThrow();
  // 快照与编译结果逐字一致：参数不会在入队与提交之间丢失。
  assertEquals(settings.forGeneration(owner,works.get(owner,work.id())).style(),frozen.style());
  assertTrue(frozen.style().contains("90 BPM"),frozen.style());
  assertTrue(frozen.style().contains("Mandarin vocals"),frozen.style());
  // 任务表里的风格快照同样完整。
  assertEquals("温暖民谣 | 90 BPM, piano, Mandarin vocals",jdbc.queryForObject("SELECT style FROM generation_task WHERE id=?",String.class,task.id()));
 }
 @Test void rejectsUnknownOptionsConflictsAndExcessiveSelectionsBeforeSaving(){var f=fixture();for(var choices:List.of(Map.of("genre",List.of("fake")),Map.of("genre",List.of("rap"),"exclude",List.of("no_rap")),Map.of("genre",List.of("folk"),"emotion",List.of("warm","happy","sad"))))assertThrows(DomainException.class,()->settings.save(f.owner(),f.work().id(),0,new MusicSettings.Preferences(1,null,choices,"AUTO","","")));assertEquals(0,works.get(f.owner(),f.work().id()).version());assertThrows(DomainException.class,()->settings.preview(new MusicSettings.Preferences(1,null,Map.of("genre",List.of("folk")),"FEMALE","请用男声唱","")));}
 @Test void previewDoesNotChangeWorkVersionOrConsumeModelQuota(){var f=fixture();int before=jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation",Integer.class);for(int n=0;n<3;n++)settings.preview(recipe());assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation",Integer.class));assertEquals(0,works.get(f.owner(),f.work().id()).version());}
 @Test void generationFreezesSettingsAndWorkerUsesTheOriginalDirection(){var f=fixture();var saved=settings.save(f.owner(),f.work().id(),0,recipe());var r=lyrics.save(f.owner(),f.work().id(),saved.work().version(),"回家的灯","[Verse]\n你在门口留着灯");lyrics.confirm(f.owner(),f.work().id(),r.id());String key=UUID.randomUUID().toString();var task=generation.submit(f.owner(),f.work().id(),r.id(),key);assertEquals(task.id(),generation.submit(f.owner(),f.work().id(),r.id(),key).id());var frozen=settings.task(f.owner(),f.work().id(),task.id()).orElseThrow();settings.save(f.owner(),f.work().id(),1,new MusicSettings.Preferences(1,null,Map.of("genre",List.of("rock")),"MALE","有力量",""));var worker=new GenerationWorker(executions,provider,url->{throw new AssertionError();},(id,w,in)->{throw new AssertionError();},java.time.Clock.fixed(task.createdAt().plusSeconds(6),java.time.ZoneOffset.UTC),java.time.Duration.ofMinutes(2),2).withSettings(settings);assertTrue(worker.runOnce(),"queued snapshot task should be claimed");assertNotNull(provider.received);assertTrue(provider.received.preparedStyle(),"worker must submit the frozen structured direction");assertEquals(frozen.style(),provider.received.style());assertEquals(frozen.negativeTags(),provider.received.negativeTags());assertEquals("FEMALE",provider.received.vocalGender());assertEquals(frozen,settings.task(f.owner(),f.work().id(),task.id()).orElseThrow());}
 @Test void ownershipAndConcurrentVersionChangesCannotOverwritePreferences(){var f=fixture();settings.save(f.owner(),f.work().id(),0,recipe());assertThrows(DomainException.class,()->settings.save(f.owner(),f.work().id(),0,recipe()));assertThrows(DomainException.class,()->settings.get(identities.create().creatorId(),f.work().id()));assertThrows(DomainException.class,()->settings.save(identities.create().creatorId(),f.work().id(),1,recipe()));}
}
