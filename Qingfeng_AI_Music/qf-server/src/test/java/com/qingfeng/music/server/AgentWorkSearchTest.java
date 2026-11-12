package com.qingfeng.music.server;

import com.qingfeng.music.server.query.WorkLibraryQuery;
import com.qingfeng.music.work.api.WorkApi;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkSearchTest {
    @Test void sqlSearchFindsOldWorksPaginatesAndEscapesUserWildcardsWithoutCrossOwnerLeaks(){
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:work-search-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE music_work(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(36),title VARCHAR(100),story TEXT,message TEXT,version BIGINT,deleted_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE lyric_revision(id VARCHAR(36),work_id VARCHAR(36),work_version BIGINT,title VARCHAR(100),lyrics TEXT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE quick_creation(work_id VARCHAR(36),status VARCHAR(30),created_at TIMESTAMP,id VARCHAR(36))");
        jdbc.execute("CREATE TABLE generation_task(id VARCHAR(36),work_id VARCHAR(36),status VARCHAR(30),created_at TIMESTAMP,title VARCHAR(100))");
        jdbc.execute("CREATE TABLE work_cover(work_id VARCHAR(36),cover_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE media_asset(id VARCHAR(36),work_id VARCHAR(36),origin VARCHAR(30),generation_task_id VARCHAR(36),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE work_audio_selection(work_id VARCHAR(36),asset_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE section_edit(task_id VARCHAR(36))");
        WorkApi works=mock(WorkApi.class);Instant now=Instant.now();var rows=new ArrayList<Object[]>();
        for(int i=0;i<2025;i++) {
            String id="w"+i,title=i<25?"回家"+i:"其他作品";
            rows.add(new Object[]{id,"owner",title,i==0?"literal 100%_safe":"故事","",0,null,Timestamp.from(now.plusSeconds(i))});
            when(works.get("owner",id)).thenReturn(new WorkApi.Work(id,"","",i==0?"literal 100%_safe":"故事","","",title,0,now,now,"AUTO"));
        }
        jdbc.batchUpdate("INSERT INTO music_work VALUES(?,?,?,?,?,?,?,?)",rows);
        jdbc.update("INSERT INTO music_work VALUES('foreign','other','回家','literal 100%_safe','',0,NULL,?)",Timestamp.from(now.plusSeconds(5000)));
        jdbc.update("INSERT INTO music_work VALUES('deleted','owner','回家','literal 100%_safe','',0,?,?)",Timestamp.from(now),Timestamp.from(now.plusSeconds(5000)));
        jdbc.update("INSERT INTO lyric_revision VALUES('r','w0',0,'歌词名','小院里的桂花',?)",Timestamp.from(now));
        var query=new WorkLibraryQuery(works,jdbc);
        var first=query.search("owner","回家",0);var second=query.search("owner","回家",1);
        assertEquals(20,first.size());assertEquals(5,second.size());assertEquals("w24",first.get(0).work().id());assertEquals("w0",second.get(4).work().id());
        assertEquals(List.of("w0"),query.search("owner","100%_safe",0).stream().map(i->i.work().id()).toList());
        assertEquals(List.of("w0"),query.search("owner","桂花",0).stream().map(i->i.work().id()).toList());
        verify(works,never()).get("owner","foreign");verify(works,never()).get("owner","deleted");verify(works,never()).list(anyString(),anyInt());
    }
}
