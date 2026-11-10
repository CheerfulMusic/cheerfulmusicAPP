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
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:work_deletion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true"})
@ActiveProfiles("test") @Import(WorkDeletionIntegrationTest.Offline.class)
class WorkDeletionIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false) static class Offline {
        @Bean @Primary MusicProvider provider(){return new MusicProvider(){
            public String name(){return "deletion-offline";}public boolean isConfigured(){return true;}
            public Submission submit(Request request,String key){return new Submission("offline-"+key);}
            public Result query(String id){return new Result(id,GenerationStatus.SAVING,"https://audio.test/song.wav");}
        };}
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;
    @Autowired GenerationApi tasks;@Autowired MediaService media;@Autowired SharingApi shares;
    @Autowired WorkLibraryQuery library;@Autowired JdbcTemplate jdbc;
    @Autowired ExecutionRepository executions;@Autowired MusicProvider provider;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    record Fixture(String owner,WorkApi.Work work,String revision){}
    Fixture fixture(){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","旅行","故事","祝福","民谣","我的歌"));var revision=lyrics.save(owner,work.id(),0,"我的歌","歌词");lyrics.confirm(owner,work.id(),revision.id());return new Fixture(owner,work,revision.id());}
    byte[] wav(){return new GiftWorkflowIntegrationTest().wav();}

    @Test void deletionIsOwnedIdempotentAndDoesNotRestoreThroughStaleEdits(){
        var f=fixture();String other=identity.create().creatorId();
        assertThrows(DomainException.class,()->works.delete(other,f.work().id(),true));
        assertNotNull(works.get(f.owner(),f.work().id()));
        works.delete(f.owner(),f.work().id(),false);works.delete(f.owner(),f.work().id(),false);
        assertTrue(works.list(f.owner(),0).isEmpty());assertTrue(library.list(f.owner(),0).isEmpty());
        assertThrows(DomainException.class,()->works.get(f.owner(),f.work().id()));
        var draft=new WorkApi.Draft("朋友","旅行","新故事","祝福","民谣","新歌");
        assertThrows(DomainException.class,()->works.update(f.owner(),f.work().id(),0,draft));
        assertThrows(DomainException.class,()->works.create(f.owner(),draft,f.work().id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM music_work WHERE id=? AND deleted_at IS NOT NULL",Integer.class,f.work().id()));
    }

    @Test void completedSongsRequireConfirmationAndAllShareAccessBecomesUnavailable(){
        var f=fixture();var audio=media.importAudio(f.owner(),f.work().id(),new ByteArrayInputStream(wav()));
        var share=shares.create(f.owner(),f.work().id(),f.revision(),audio.id());
        assertEquals("WORK_DELETE_CONFIRMATION_REQUIRED",assertThrows(DomainException.class,()->works.delete(f.owner(),f.work().id(),false)).code());
        assertNotNull(shares.get(share.token()));
        works.delete(f.owner(),f.work().id(),true);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM share_link WHERE work_id=? AND active=TRUE",Integer.class,f.work().id()));
        assertThrows(DomainException.class,()->shares.get(share.token()));
        assertThrows(DomainException.class,()->shares.audio(share.token()));
        assertThrows(DomainException.class,()->shares.audioRef(share.token()));
        assertThrows(DomainException.class,()->media.open(f.owner(),f.work().id(),audio.id()));
        assertThrows(DomainException.class,()->shares.create(f.owner(),f.work().id(),f.revision(),audio.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE work_id=?",Integer.class,f.work().id()));
    }

    @Test void aRunningTaskCanFinishWithoutBringingTheDeletedWorkBack(){
        var f=fixture();var task=tasks.submit(f.owner(),f.work().id(),f.revision(),UUID.randomUUID().toString());
        var first=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,Clock.fixed(Instant.now().plusSeconds(10),ZoneOffset.UTC),Duration.ofMinutes(2),2);
        assertTrue(first.runOnce(ExecutionRepository.Phase.SUBMIT));works.delete(f.owner(),f.work().id(),false);
        var later=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,Clock.fixed(Instant.now().plusSeconds(30),ZoneOffset.UTC),Duration.ofMinutes(2),2);
        assertTrue(later.runOnce(ExecutionRepository.Phase.QUERY));assertTrue(later.runOnce(ExecutionRepository.Phase.ARCHIVE));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM generation_task WHERE id=?",String.class,task.id()));
        assertTrue(library.list(f.owner(),0).isEmpty());assertTrue(works.list(f.owner(),0).isEmpty());
        assertThrows(DomainException.class,()->tasks.submit(f.owner(),f.work().id(),f.revision(),UUID.randomUUID().toString()));
    }

    /** Reproduces the production fk_attempt_task failure with a completed, generated audio asset. */
    @Test void deletingGeneratedAudioThroughHttpPreservesDependentRowsAndInvalidatesAccess() throws Exception {
        var client=java.net.http.HttpClient.newBuilder().cookieHandler(new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL)).build();
        String csrf=field(send(client,"GET","/api/public/csrf",null).body(),"token");
        String owner=field(send(client,"POST","/api/public/session",csrf).body(),"creatorId");
        var work=works.create(owner,new WorkApi.Draft("朋友","旅行","晚风里的故事","祝福","民谣","生成的歌"));
        var revision=lyrics.save(owner,work.id(),0,"生成的歌","把晚风唱进歌里");lyrics.confirm(owner,work.id(),revision.id());
        var task=tasks.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());
        Instant base=Instant.now();
        for(var phase:java.util.List.of(ExecutionRepository.Phase.SUBMIT,ExecutionRepository.Phase.QUERY,ExecutionRepository.Phase.ARCHIVE)){
            long delay=switch(phase){case SUBMIT->10;case QUERY->30;default->60;};
            var worker=new GenerationWorker(executions,provider,url->new ByteArrayInputStream(wav()),media,Clock.fixed(base.plusSeconds(delay),ZoneOffset.UTC),Duration.ofMinutes(2),2);
            assertTrue(worker.runOnce(phase),phase.name());
        }
        String asset=jdbc.queryForObject("SELECT asset_id FROM generation_task WHERE id=?",String.class,task.id());
        int attempts=jdbc.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE task_id=?",Integer.class,task.id());
        assertTrue(attempts>0);
        // The old physical cleanup cannot remove the parent task while audit rows reference it.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("DELETE FROM generation_task WHERE id=?",task.id()));
        var share=shares.create(owner,work.id(),revision.id(),asset);
        String path="/api/app/works/"+work.id();
        assertEquals(403,send(client,"DELETE",path+"?acknowledgeCompleted=true",null).statusCode());
        assertEquals(409,send(client,"DELETE",path,csrf).statusCode());
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM music_work WHERE id=?",java.sql.Timestamp.class,work.id()));
        var deleted=send(client,"DELETE",path+"?acknowledgeCompleted=true",csrf);
        assertEquals(200,deleted.statusCode(),deleted.body());assertTrue(deleted.body().contains("\"deleted\":true"));
        assertEquals(200,send(client,"DELETE",path+"?acknowledgeCompleted=true",csrf).statusCode());
        assertEquals(404,send(client,"GET",path,null).statusCode());
        assertFalse(send(client,"GET","/api/app/works/library",null).body().contains(work.id()));
        assertEquals(attempts,jdbc.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE task_id=?",Integer.class,task.id()));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM generation_task WHERE id=?",String.class,task.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE id=?",Integer.class,asset));
        assertThrows(DomainException.class,()->shares.get(share.token()));
        assertThrows(DomainException.class,()->shares.audio(share.token()));
    }
    private java.net.http.HttpResponse<String> send(java.net.http.HttpClient client,String method,String path,String csrf) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path));
        if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);
        return client.send(request.method(method,"POST".equals(method)?java.net.http.HttpRequest.BodyPublishers.ofString("{}"):java.net.http.HttpRequest.BodyPublishers.noBody()).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    private String field(String json,String key){var match=java.util.regex.Pattern.compile("\""+key+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);assertTrue(match.find(),json);return match.group(1);}
}
