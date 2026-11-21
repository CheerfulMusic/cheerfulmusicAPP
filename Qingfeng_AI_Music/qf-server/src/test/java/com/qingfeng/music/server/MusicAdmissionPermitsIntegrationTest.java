package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:music_permits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:db/test/audio_version_annotations.sql,classpath:db/optional/music_admission_permits.sql",
    "qingfeng.usage.music.permits-enabled=true","qingfeng.usage.music.concurrent-global=2",
    "qingfeng.usage.music.daily-global=-1","qingfeng.generation.enabled=true","qingfeng.credits.enabled=true"
})
@ActiveProfiles("test")
@Import(KieMusicCapacityIntegrationTest.Offline.class)
class MusicAdmissionPermitsIntegrationTest {
    @Autowired IdentityApi identity;
    @Autowired WorkApi works;
    @Autowired LyricsApi lyrics;
    @Autowired CreditsLedger credits;
    @Autowired GenerationApi tasks;
    @Autowired JdbcTemplate db;
    record Request(String owner,String work,String revision,String key){}
    Request request(){
        var owner=identity.create().creatorId();
        credits.credit(owner,1000,"test-"+owner);
        var work=works.create(owner,new WorkApi.Draft("朋友","旅行","故事","祝福","民谣","歌曲"));
        var revision=lyrics.save(owner,work.id(),0,"容量验证","已确认歌词");
        lyrics.confirm(owner,work.id(),revision.id());
        return new Request(owner,work.id(),revision.id(),UUID.randomUUID().toString());
    }

    @Test void rollbackReturnsTheGrantAndConcurrentWritersShareTheLastSlot()throws Exception{
        var first=request();
        assertThrows(IllegalStateException.class,()->tasks.submit(first.owner,first.work,first.revision,first.key,
            task->{throw new IllegalStateException("rollback after task insert");}));
        assertEquals(1000,credits.balance(first.owner));
        tasks.submit(first.owner,first.work,first.revision,first.key);
        var requests=new ArrayList<Request>();
        for(int i=0;i<12;i++)requests.add(request());
        var start=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(requests.size());
        try{
            var futures=new ArrayList<Future<Boolean>>();
            for(var r:requests)futures.add(pool.submit(()->{
                start.await();
                try{tasks.submit(r.owner,r.work,r.revision,r.key);return true;}
                catch(DomainException e){assertEquals("GLOBAL_GENERATION_BUSY",e.code());return false;}
            }));
            start.countDown();
            int accepted=0;
            for(var f:futures)if(f.get(30,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM generation_task",Integer.class));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT'",Integer.class));
        }finally{pool.shutdownNow();}
    }
}
