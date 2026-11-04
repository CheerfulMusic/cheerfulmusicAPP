package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.application.port.MusicProvider;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.server.query.WorkLibraryQuery;
import java.io.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:section_edits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true"})
@ActiveProfiles("test") @Import(SectionEditingIntegrationTest.Offline.class)
class SectionEditingIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline {@Bean @Primary Provider provider(){return new Provider();}}
    static class Provider implements MusicProvider {
        int replaces,submits,reads;boolean available=true,unknown,rejected;Request received;Replacement edit;
        public String name(){return "kie-suno";}public boolean isConfigured(){return available;}public boolean supportsSectionEditing(){return true;}
        public Submission submit(Request request,String key){submits++;throw new AssertionError("局部修改不能调用整首生成接口");}
        public Submission replace(Request request,Replacement replacement,String key){replaces++;received=request;edit=replacement;if(unknown)throw new IllegalStateException("response lost");if(rejected)throw new Rejected();return new Submission("edit-"+key);}
        public Result query(String task){return new Result(task,GenerationStatus.SAVING,"https://audio.test/edited.wav",new AudioInfo("clip-edited",120,"https://audio.test/edited.wav"));}
        public AudioInfo sourceInfo(String task,String archived){reads++;return new AudioInfo("clip-original",120,archived);}
    }
    @Autowired WorkApi works;@Autowired IdentityApi identity;@Autowired LyricsApi lyrics;@Autowired GenerationApi generation;
    @Autowired MediaService media;@Autowired SectionEditingService editing;@Autowired SectionEditRepository edits;
    @Autowired ExecutionRepository executions;@Autowired JdbcTemplate jdbc;@Autowired Provider provider;@Autowired SharingApi sharing;@Autowired WorkLibraryQuery library;
    @Autowired com.qingfeng.music.settings.MusicSettings musicSettings;
    @BeforeEach void reset(){jdbc.update("UPDATE generation_task SET status='FAILED' WHERE status<>'SUCCEEDED'");jdbc.update("DELETE FROM model_usage_reservation");provider.replaces=provider.submits=provider.reads=0;provider.unknown=provider.rejected=false;provider.available=true;}
    record Fixture(String owner,String work,String revision,String asset,String task,String text){}
    Fixture fixture(){
        String owner=identity.create().creatorId();var w=works.create(owner,new WorkApi.Draft("妈妈","生日","一碗汤","谢谢陪伴","温暖民谣","门口的灯","FEMALE"));
        String text="[Verse]\n门口的小碗还在\n你说路上慢点\n[Chorus]\n愿你平安归来";
        var revision=lyrics.save(owner,w.id(),0,w.title(),text);lyrics.confirm(owner,w.id(),revision.id());
        var task=generation.submit(owner,w.id(),revision.id(),UUID.randomUUID().toString());
        String asset=media.store(task.id(),w.id(),new ByteArrayInputStream(wav()));
        jdbc.update("UPDATE generation_task SET status='SUCCEEDED',asset_id=?,provider_task_id='platform-original',audio_url='https://audio.test/original.wav' WHERE id=?",asset,task.id());
        edits.recordAudio(task.id(),new MusicProvider.AudioInfo("clip-original",120,"https://audio.test/original.wav"));
        return new Fixture(owner,w.id(),revision.id(),asset,task.id(),text);
    }
    SectionEditingService.Request request(Fixture f,String key){int from=f.text.indexOf("门口的小碗还在");return new SectionEditingService.Request(f.asset,0,key,10,22,from,from+"门口的小碗还在".length(),"门口的灯为我亮");}
    @Test void sectionEditsInheritTheSourceDirectionRatherThanCurrentPreferences(){
        var f=fixture();var r=musicSettings.catalog().recipes().get(0);
        var original=musicSettings.preview(new com.qingfeng.music.settings.MusicSettings.Preferences(1,r.id(),r.choices(),"FEMALE","轻柔自然","重鼓"));musicSettings.snapshot(f.task,original);
        var edited=editing.submit(f.owner,f.work,request(f,UUID.randomUUID().toString()));
        assertEquals(original,musicSettings.frozen(f.work,edited.id()).orElseThrow());
        musicSettings.save(f.owner,f.work,0,new com.qingfeng.music.settings.MusicSettings.Preferences(1,null,Map.of("genre",List.of("rock")),"MALE","有力量",""));
        var time=new Time(edited.createdAt().plusSeconds(6));
        var worker=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,time,Duration.ofMinutes(2),2).withSectionEdits(edits).withSettings(musicSettings);
        assertTrue(worker.runOnce());assertTrue(provider.received.preparedStyle());assertEquals(original.style(),provider.received.style());assertEquals(original.negativeTags(),provider.received.negativeTags());assertEquals("FEMALE",provider.received.vocalGender());
    }
    static byte[] wav(){var b=ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes()).putInt(40).put("WAVEfmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes()).putInt(4).putInt(0);return b.array();}
    static class Time extends Clock {Instant now;Time(Instant now){this.now=now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
    GenerationApi.Task finish(Fixture f,GenerationApi.Task task){
        var time=new Time(task.createdAt().plusSeconds(6));var worker=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,time,Duration.ofMinutes(2),2).withSectionEdits(edits);
        assertTrue(worker.runOnce());time.now=time.now.plusSeconds(11);assertTrue(worker.runOnce());assertTrue(worker.runOnce());
        return generation.list(f.owner,f.work).stream().filter(t->t.id().equals(task.id())).findFirst().orElseThrow();
    }
    @Test void replacementUsesOriginalContextAndOnlyAdoptionChangesCurrentSong(){
        var f=fixture();String token=sharing.create(f.owner,f.work,f.revision,f.asset).token();
        var accepted=editing.submit(f.owner,f.work,request(f,UUID.randomUUID().toString()));
        assertEquals(f.asset,media.selection(f.owner,f.work).assetId());assertEquals(0,provider.replaces);
        var done=finish(f,accepted);assertEquals(GenerationStatus.SUCCEEDED,done.status());assertEquals(1,provider.replaces);assertEquals(0,provider.submits);
        assertEquals("platform-original",provider.edit.taskId());assertEquals("clip-original",provider.edit.audioId());assertEquals(10,provider.edit.start());assertEquals(22,provider.edit.end());
        assertEquals(f.text.replace("门口的小碗还在","门口的灯为我亮"),provider.received.confirmedLyrics());assertEquals("FEMALE",provider.received.vocalGender());assertEquals("温暖民谣",provider.received.style());
        assertEquals(f.asset,media.selection(f.owner,f.work).assetId());assertTrue(media.get(f.owner,f.work,done.assetId()).sectionEdit());assertEquals(f.text,lyrics.get(f.owner,f.work,f.revision).lyrics());
        var choice=media.selection(f.owner,f.work);media.select(f.owner,f.work,done.assetId(),choice.version());
        assertEquals(done.assetId(),media.selection(f.owner,f.work).assetId());assertEquals(done.assetId(),library.list(f.owner,0).get(0).audioId());
        assertEquals(f.text,sharing.get(token).lyrics());assertTrue(sharing.audio(token).filename().startsWith(f.asset));
        assertEquals(provider.received.confirmedLyrics(),sharing.get(sharing.create(f.owner,f.work,done.revisionId(),done.assetId()).token()).lyrics());
        assertThrows(DomainException.class,()->media.select(f.owner,f.work,f.asset,choice.version()));
    }
    @Test void theSameRequestIsIdempotentAndDifferentContentCannotReuseItsKey()throws Exception{
        var f=fixture();String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->editing.submit(f.owner,f.work,request(f,key)));var b=pool.submit(()->editing.submit(f.owner,f.work,request(f,key)));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());}finally{pool.shutdownNow();}
        assertEquals(1,editing.list(f.owner,f.work).size());assertEquals(2,lyrics.list(f.owner,f.work).size());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=?",Integer.class,f.work));
        var q=request(f,key);assertEquals("REQUEST_KEY_CONFLICT",assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,new SectionEditingService.Request(q.sourceAssetId(),0,key,10,22,q.textStart(),q.textEnd(),"另一句歌词"))).code());assertEquals(0,provider.replaces);
    }
    @Test void invalidRangesAndTextDoNotConsumeQuotaOrCreateVersions(){
        var f=fixture();var r=request(f,UUID.randomUUID().toString());
        for(double[] range:new double[][]{{10,19},{0,61},{-1,12},{10,121},{Double.NaN,22},{10.001,22}}){assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,new SectionEditingService.Request(f.asset,0,UUID.randomUUID().toString(),range[0],range[1],r.textStart(),r.textEnd(),r.replacementLyrics())));}
        assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,new SectionEditingService.Request(f.asset,0,UUID.randomUUID().toString(),10,22,r.textStart()+1,r.textEnd(),"新歌词")));
        assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,new SectionEditingService.Request(f.asset,0,UUID.randomUUID().toString(),10,22,r.textStart(),r.textEnd(),"门口的小碗还在")));
        assertEquals(1,lyrics.list(f.owner,f.work).size());assertTrue(editing.list(f.owner,f.work).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=?",Integer.class,f.work));
    }
    @Test void ownershipAndImportedAudioAreRejectedBeforeAnyProviderRequest(){
        var f=fixture();String other=identity.create().creatorId();assertThrows(DomainException.class,()->editing.context(other,f.work,f.asset));assertThrows(DomainException.class,()->editing.submit(other,f.work,request(f,UUID.randomUUID().toString())));
        String imported=media.importAudio(f.owner,f.work,new ByteArrayInputStream(wav())).id();assertFalse(editing.context(f.owner,f.work,imported).available());assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,new SectionEditingService.Request(imported,0,UUID.randomUUID().toString(),0,10,0,7,"新歌词")));assertEquals(0,provider.reads);
    }
    @Test void oldSongsCanRecoverSourceMetadataWithOnlyAReadRequest(){
        var f=fixture();jdbc.update("DELETE FROM generation_audio_source WHERE task_id=?",f.task);
        var context=editing.context(f.owner,f.work,f.asset);assertTrue(context.available());assertEquals(120,context.duration());assertEquals(1,provider.reads);assertEquals(0,provider.replaces);assertEquals(0,provider.submits);
        assertTrue(editing.context(f.owner,f.work,f.asset).available());assertEquals(1,provider.reads);
    }
    @Test void ambiguousSubmissionNeverFallsBackToWholeSongOrRetriesAutomatically(){
        var f=fixture();provider.unknown=true;String key=UUID.randomUUID().toString();var task=editing.submit(f.owner,f.work,request(f,key));
        var clock=new Time(task.createdAt().plusSeconds(6));var worker=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,clock,Duration.ofMinutes(2),2).withSectionEdits(edits);
        assertTrue(worker.runOnce());assertFalse(worker.runOnce());assertEquals(1,provider.replaces);assertEquals(0,provider.submits);assertEquals(GenerationStatus.SUBMISSION_UNKNOWN,editing.submit(f.owner,f.work,request(f,key)).status());assertEquals(f.asset,media.selection(f.owner,f.work).assetId());
    }
    @Test void queuedEditsCanBeCancelledAndUnavailableProviderDoesNotCreateTasks(){
        var f=fixture();var task=editing.submit(f.owner,f.work,request(f,UUID.randomUUID().toString()));assertEquals(GenerationStatus.CANCELLED,generation.cancel(f.owner,f.work,task.id()).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_usage_reservation WHERE work_id=?",Integer.class,f.work));provider.available=false;assertFalse(editing.context(f.owner,f.work,f.asset).available());assertThrows(DomainException.class,()->editing.submit(f.owner,f.work,request(f,UUID.randomUUID().toString())));assertEquals(1,editing.list(f.owner,f.work).size());
    }
}
