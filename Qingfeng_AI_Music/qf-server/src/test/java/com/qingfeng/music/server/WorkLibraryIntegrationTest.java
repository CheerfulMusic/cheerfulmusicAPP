package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.server.query.WorkLibraryQuery;
import com.qingfeng.music.common.exception.DomainException;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:work_library;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true"})
@ActiveProfiles("test") @Import(GenerationSubmissionIntegrationTest.Providers.class)
class WorkLibraryIntegrationTest {
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;@Autowired MediaService media;@Autowired WorkLibraryQuery library;@Autowired JdbcTemplate jdbc;
    @Test void followsRealStateAndDoesNotExposeOtherCreatorsAudio(){
        String owner=identity.create().creatorId(),other=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","回忆","祝福","民谣","故事"));
        assertEquals("LYRICS_DRAFT",library.list(owner,0).get(0).status());
        var revision=lyrics.save(owner,work.id(),0,"生成的歌曲名","歌词");lyrics.confirm(owner,work.id(),revision.id());assertEquals("READY_TO_GENERATE",library.list(owner,0).get(0).status());
        var task=tasks.submit(owner,work.id(),revision.id(),java.util.UUID.randomUUID().toString());assertEquals("GENERATING",library.list(owner,0).get(0).status());
        jdbc.update("UPDATE generation_task SET status='FAILED' WHERE id=?",task.id());assertEquals("GENERATION_FAILED",library.list(owner,0).get(0).status());
        var asset=media.importAudio(owner,work.id(),new ByteArrayInputStream(new GiftWorkflowIntegrationTest().wav()));
        assertEquals("COMPLETED",library.list(owner,0).get(0).status());assertEquals(asset.id(),library.list(owner,0).get(0).audioId());assertTrue(library.list(other,0).isEmpty());
        jdbc.update("UPDATE media_asset SET generation_task_id=? WHERE id=?",task.id(),asset.id());assertEquals("生成的歌曲名",library.list(owner,0).get(0).songTitle());
        jdbc.update("UPDATE generation_task SET status='SUBMISSION_UNKNOWN' WHERE id=?",task.id());assertEquals("CHECKING",library.list(owner,0).get(0).status());
    }
    @Test void softDeleteHidesWorkAndPreservesRelatedRows(){
        String owner=identity.create().creatorId(),other=identity.create().creatorId();
        var work=works.create(owner,new WorkApi.Draft("妈妈","生日","回忆","祝福","民谣","故事"));
        // 他人不能删除不属于自己的作品
        assertThrows(DomainException.class,()->works.delete(other,work.id(),true));
        var revision=lyrics.save(owner,work.id(),0,"歌名","歌词");lyrics.confirm(owner,work.id(),revision.id());
        var task=tasks.submit(owner,work.id(),revision.id(),UUID.randomUUID().toString());
        var asset=media.importAudio(owner,work.id(),new ByteArrayInputStream(new GiftWorkflowIntegrationTest().wav()));
        jdbc.update("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(),work.id(),UUID.randomUUID().toString(),asset.id(),"t","r","m","l","GENERATED",true);
        jdbc.update("INSERT INTO cover_asset(id,work_id,storage_key,byte_size,origin,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),work.id(),"k",1,"UPLOADED");
        jdbc.update("INSERT INTO work_music_preferences(work_id,settings_json) VALUES(?,?)",work.id(),"{}");
        jdbc.update("INSERT INTO lyrics_composition_request(work_id,request_key,status,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",work.id(),"rk","PENDING");
        jdbc.update("INSERT INTO generation_attempt(id,task_id,phase,outcome,started_at) VALUES(?,?,'GENERATE','SUCCEEDED',CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),task.id());
        works.delete(owner,work.id(),true);
        assertTrue(library.list(owner,0).isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM music_work WHERE id=? AND deleted_at IS NOT NULL",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM generation_task WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM lyric_revision WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM share_link WHERE work_id=? AND active=FALSE",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cover_asset WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM work_music_preferences WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM lyrics_composition_request WHERE work_id=?",Integer.class,work.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM generation_attempt WHERE task_id=?",Integer.class,task.id()));
        assertThrows(DomainException.class,()->works.get(owner,work.id()));
    }
}
