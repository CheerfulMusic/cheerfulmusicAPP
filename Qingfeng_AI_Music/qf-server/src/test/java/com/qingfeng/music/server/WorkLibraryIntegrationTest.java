package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.media.application.MediaService;
import com.qingfeng.music.server.query.WorkLibraryQuery;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

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
}
