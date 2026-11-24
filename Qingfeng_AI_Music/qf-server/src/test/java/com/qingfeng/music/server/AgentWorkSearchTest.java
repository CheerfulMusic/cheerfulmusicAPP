package com.qingfeng.music.server;

import com.qingfeng.music.infrastructure.persistence.work.WorkLibraryMapper;
import com.qingfeng.music.server.query.WorkLibraryQuery;
import com.qingfeng.music.work.api.WorkApi;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentWorkSearchTest {
    @Test void sqlSearchFindsOldWorksPaginatesAndEscapesUserWildcardsWithoutCrossOwnerLeaks() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:work-search-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE music_work(id VARCHAR(36) PRIMARY KEY,owner_id VARCHAR(36),title VARCHAR(100),story TEXT,message TEXT,version BIGINT,deleted_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE lyric_revision(id VARCHAR(36),work_id VARCHAR(36),work_version BIGINT,title VARCHAR(100),lyrics TEXT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE quick_creation(work_id VARCHAR(36),status VARCHAR(30),error_code VARCHAR(80),created_at TIMESTAMP,id VARCHAR(36))");
        jdbc.execute("CREATE TABLE generation_task(id VARCHAR(36),work_id VARCHAR(36),status VARCHAR(30),created_at TIMESTAMP,title VARCHAR(100))");
        jdbc.execute("CREATE TABLE work_cover(work_id VARCHAR(36),cover_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE media_asset(id VARCHAR(36),work_id VARCHAR(36),origin VARCHAR(30),generation_task_id VARCHAR(36),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE work_audio_selection(work_id VARCHAR(36),asset_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE section_edit(task_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE music_video_project(id VARCHAR(36),work_id VARCHAR(36),creator_id VARCHAR(36),state VARCHAR(30),created_at TIMESTAMP)");
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
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        var sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().addMapper(WorkLibraryMapper.class);
        var mapper = new SqlSessionTemplate(sessionFactory).getMapper(WorkLibraryMapper.class);
        var query = new WorkLibraryQuery(works, mapper);

        var first = query.search("owner", "回家", 0);
        var second = query.search("owner", "回家", 1);
        assertEquals(java.util.stream.IntStream.range(0, 20).mapToObj(i -> "w" + (24 - i)).toList(),
            first.stream().map(item -> item.work().id()).toList());
        assertEquals(List.of("w4", "w3", "w2", "w1", "w0"), second.stream().map(item -> item.work().id()).toList());
        assertTrue(query.search("owner", "回家", 2).isEmpty());
        assertEquals(List.of("w0"), query.search("owner", "100%_safe", 0).stream().map(item -> item.work().id()).toList());
        var lyrics = query.search("owner", "桂花", 0);
        assertEquals(List.of("w0"), lyrics.stream().map(item -> item.work().id()).toList());
        var evidence = lyrics.get(0).evidence();
        assertEquals("lyrics", evidence.source());
        assertEquals("r", evidence.revisionId());
        assertEquals(1, evidence.revisionNumber());
        assertEquals("歌词名", evidence.revisionTitle());
        verify(works,never()).get("owner","foreign");verify(works,never()).get("owner","deleted");verify(works,never()).list(anyString(),anyInt());
    }
}
