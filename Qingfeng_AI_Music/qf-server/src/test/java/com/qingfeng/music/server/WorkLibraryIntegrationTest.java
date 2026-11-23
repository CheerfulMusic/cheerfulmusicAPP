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

@org.springframework.test.context.jdbc.Sql(scripts="classpath:db/migration/V47__music_video_projects.sql", executionPhase=org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:work_library;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","qingfeng.generation.enabled=true"})
@ActiveProfiles("test") @Import(GenerationSubmissionIntegrationTest.Providers.class)
class WorkLibraryIntegrationTest {
    @Test void knownSongsRankInTopThreeForTitlesAndOriginalLines(){
        String owner=identity.create().creatorId();
        String[] titles={"海边来信","月台上的灯","旧操场"};
        String[] lines={"海风替我说晚安","最后一班列车绕过家乡","粉笔灰落在旧跑道上"};
        String[] stories={"海岛旅行时写给朋友的晚安歌","回老家陪父亲赶末班火车","毕业时送给同班同学的轻快民谣"};
        String[] themes={"海岛旅行","陪爸爸坐末班车回乡的歌","去年写给毕业同学、风格很轻快的那首"};
        var ids=new java.util.ArrayList<String>();
        for(int i=0;i<titles.length;i++){
            var w=works.create(owner,new WorkApi.Draft("","","故事。".repeat(165)+stories[i],"","",titles[i]));
            ids.add(w.id());
            var revision=lyrics.save(owner,w.id(),0,titles[i],"前一行\n"+lines[i]);
            lyrics.confirm(owner,w.id(),revision.id());
            jdbc.update("UPDATE music_work SET updated_at='2024-01-01' WHERE id=?",w.id());
        }
        for(int i=0;i<4;i++)works.create(owner,new WorkApi.Draft("","","灵感笔记里提到《海边来信》","","","磁带笔记"+i));
        int titleHits=0,lineHits=0,themeHits=0;
        for(int i=0;i<ids.size();i++){
            if(topThree(owner,titles[i],ids.get(i)))titleHits++;
            if(topThree(owner,lines[i],ids.get(i)))lineHits++;
            if(topThree(owner,themes[i],ids.get(i)))themeHits++;
        }
        System.out.printf("SEARCH_TOP3 titles=%d/3 lines=%d/3 themes=%d/3%n",titleHits,lineHits,themeHits);
        assertEquals(3,titleHits);assertEquals(3,lineHits);assertTrue(themeHits>=1);
    }
    private boolean topThree(String owner,String query,String expected){
        return library.search(owner,query,0).stream().limit(3).anyMatch(item->expected.equals(item.work().id()));
    }
    @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;@Autowired MediaService media;@Autowired WorkLibraryQuery library;@Autowired JdbcTemplate jdbc;
    @Test void exposesCompletedVideoBeforeNewQueuedTaskAndIsolatesOwners(){
        String owner=identity.create().creatorId(),other=identity.create().creatorId();
        var w=works.create(owner,new WorkApi.Draft("","","story","","民谣","song"));
        for(String[] v:new String[][]{{"ready",owner,"READY"},{"pending",owner,"RUNNING"},{"foreign",other,"READY"}})
            jdbc.update("INSERT INTO music_video_project(id,creator_id,work_id,asset_id,request_key,fingerprint,state,plan_json) VALUES(?,?,?,?,?,?,?,?)",v[0],v[1],w.id(),"audio",UUID.randomUUID().toString(),"fingerprint",v[2],"{}");
        var row=library.list(owner,0).get(0);assertEquals("ready",row.videoId());assertEquals("READY",row.videoState());
        jdbc.update("UPDATE music_video_project SET state='FAILED' WHERE id='ready'");
        row=library.list(owner,0).get(0);assertEquals("pending",row.videoId());assertEquals("RUNNING",row.videoState());
    }
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
