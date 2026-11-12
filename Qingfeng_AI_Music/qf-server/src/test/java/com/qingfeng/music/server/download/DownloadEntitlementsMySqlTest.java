package com.qingfeng.music.server.download;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.media.api.MediaApi;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.media.api.SharedMediaAccess;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QF_DOWNLOAD_MYSQL_TEST_PORT",matches="57[0-9]{3}")
@SpringBootTest(properties={"spring.datasource.url=jdbc:mysql://127.0.0.1:${QF_DOWNLOAD_MYSQL_TEST_PORT}/qf_execution_concurrency_test?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false", "spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "qingfeng.payment.inbox.scheduler-enabled=false"})
@ActiveProfiles("test")
class DownloadEntitlementsMySqlTest {
    @Autowired DownloadEntitlements downloads;
    @Autowired CreditsLedger credits;
    @Autowired IdentityApi identity;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    private MediaApi.Content audio(){return new MediaApi.Content(1,"audio/mpeg","song.mp3",()->new ByteArrayInputStream(new byte[]{1}));}
    private String owner(long initial){String id=identity.create().creatorId();if(initial>0)credits.credit(id,initial,UUID.randomUUID().toString());return id;}
    @Test void concurrentRetriesAcrossDevicesChargeOnceAndAccountsAreIndependent() throws Exception {
        String a=owner(30),b=owner(30),work=UUID.randomUUID().toString();
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(8);var tasks=new ArrayList<Future<?>>();
        try {
            for(int i=0;i<16;i++)tasks.add(pool.submit(()->{try{if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();downloads.unlock(a,work,audio());}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}));
            start.countDown();for(var task:tasks)task.get(20,TimeUnit.SECONDS);
            downloads.unlock(b,work,audio());assertEquals(20,credits.balance(a));assertEquals(20,credits.balance(b));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='DEBIT' AND reference_id=?",Integer.class,DownloadEntitlements.reference(a,work)));
            downloads.unlock(a,work,audio());assertEquals(20,credits.balance(a));
        } finally { pool.shutdownNow(); }
    }
    @Test void lowBalanceBrokenStreamAndTransactionRollbackNeverUnlock(){
        String a=owner(0),b=owner(20),work=UUID.randomUUID().toString();
        assertThrows(DomainException.class,()->downloads.unlock(a,work,audio()));assertFalse(downloads.unlocked(a,work));
        var broken=new MediaApi.Content(1,"audio/mpeg","a.mp3",()->{throw new IOException();});
        assertThrows(DomainException.class,()->downloads.unlock(b,work,broken));assertEquals(20,credits.balance(b));
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(tx->{downloads.unlock(b,work,audio());throw new IllegalStateException();}));
        assertFalse(downloads.unlocked(b,work));assertEquals(20,credits.balance(b));
    }
    @Test void giftPurchaseSurvivesRevocationAndDeletionWithoutExposingAnotherAsset() throws Exception {
        String author=owner(0),buyer=owner(25),other=owner(25),work=UUID.randomUUID().toString(),asset=UUID.randomUUID().toString(),secondAsset=UUID.randomUUID().toString(),privateAsset=UUID.randomUUID().toString();
        byte[] random=new byte[32];new java.security.SecureRandom().nextBytes(random);String token=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("INSERT INTO music_work(id,owner_id,recipient,occasion,story,message,style,title,version,created_at,updated_at) VALUES(?,?,'','OTHER','','','','song',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",work,author);
        for(String id:java.util.List.of(asset,secondAsset,privateAsset))db.update("INSERT INTO media_asset(id,work_id,storage_key,origin,content_type,byte_size,sha256,created_at) VALUES(?,?,'local:test','IMPORTED','audio/mpeg',1,?,CURRENT_TIMESTAMP)",id,work,"0".repeat(64));
        db.update("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at) VALUES(?,?,?,?,'song','','','','IMPORTED',TRUE,CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY))",UUID.randomUUID().toString(),work,hash,asset);
        SharedMediaAccess media=mock(SharedMediaAccess.class);when(media.content(work,asset)).thenReturn(audio());
        var shared=new SharedDownloadGrants(db,new TransactionTemplate(manager),media,downloads);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{shared.unlock(buyer,token);throw new IllegalStateException();}));
        assertEquals(25,credits.balance(buyer));assertTrue(shared.list(buyer).isEmpty());
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
        try{for(int i=0;i<16;i++)jobs.add(pool.submit(()->{try{start.await();shared.unlock(buyer,token);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));start.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(15,credits.balance(buyer));assertEquals(1,shared.list(buyer).size());
        String id=shared.list(buyer).get(0).id();
        byte[] secondRandom=new byte[32];new java.security.SecureRandom().nextBytes(secondRandom);String secondToken=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secondRandom);
        String secondHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(secondToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        db.update("INSERT INTO share_link(id,work_id,token_hash,asset_id,title,recipient,message,lyrics,audio_origin,active,created_at,expires_at) VALUES(?,?,?,?,'song 2','','','','IMPORTED',TRUE,CURRENT_TIMESTAMP,DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 1 DAY))",UUID.randomUUID().toString(),work,secondHash,secondAsset);
        when(media.content(work,secondAsset)).thenReturn(audio());assertEquals(0,shared.price(buyer,secondToken));
        shared.unlock(buyer,secondToken);assertEquals(15,credits.balance(buyer));assertEquals(2,shared.list(buyer).size());
        String third=owner(20);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var race=Executors.newFixedThreadPool(2);
        try {
            var purchase=race.submit(()->new TransactionTemplate(manager).executeWithoutResult(t->{shared.unlock(third,token);locked.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var revoke=race.submit(()->db.update("UPDATE share_link SET active=FALSE WHERE token_hash=?",hash));
            assertThrows(TimeoutException.class,()->revoke.get(200,TimeUnit.MILLISECONDS));release.countDown();purchase.get(10,TimeUnit.SECONDS);revoke.get(10,TimeUnit.SECONDS);
            assertEquals(10,credits.balance(third));assertEquals(1,shared.list(third).size());
        } finally {release.countDown();race.shutdownNow();}
        db.update("UPDATE share_link SET active=FALSE,expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY) WHERE token_hash=?",hash);
        db.update("UPDATE music_work SET deleted_at=CURRENT_TIMESTAMP WHERE id=?",work);
        assertEquals(1,shared.content(buyer,id).size());assertEquals(1,shared.contentForToken(buyer,token).size());
        assertThrows(DomainException.class,()->shared.content(other,id));
        assertTrue(shared.list(other).isEmpty());assertThrows(DomainException.class,()->shared.unlock(other,token));
        assertEquals(25,credits.balance(other));verify(media,never()).content(work,privateAsset);
    }
}
