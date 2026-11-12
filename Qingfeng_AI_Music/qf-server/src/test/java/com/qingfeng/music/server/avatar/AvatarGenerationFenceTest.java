package com.qingfeng.music.server.avatar;

import com.qingfeng.music.cover.application.CoverFileStore;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.infrastructure.persistence.credits.JdbcCreditsLedger;
import java.io.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JDBC transactions and competing workers, with an in-memory image store and no provider network. */
class AvatarGenerationFenceTest {
    JdbcTemplate db; TransactionTemplate tx; AvatarService avatars; AvatarGenerationService service;
    JdbcCreditsLedger ledger; AvatarImageProvider provider; MemoryImages images;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:avatar_fence_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        var manager=new DataSourceTransactionManager(ds);db=new JdbcTemplate(ds);tx=new TransactionTemplate(manager);
        db.execute("CREATE TABLE creator (id VARCHAR(36) PRIMARY KEY,payment_risk_frozen INT NOT NULL DEFAULT 0)");
        db.execute("CREATE TABLE creator_avatar (creator_id VARCHAR(36) PRIMARY KEY,storage_key VARCHAR(512) NOT NULL,byte_size BIGINT NOT NULL,version VARCHAR(36) NOT NULL)");
        db.execute("CREATE TABLE credit_account (owner_id VARCHAR(36) PRIMARY KEY,balance BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        db.execute("CREATE TABLE credit_ledger (entry_type VARCHAR(16) NOT NULL,reference_id VARCHAR(160) NOT NULL,owner_id VARCHAR(36) NOT NULL,amount BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,PRIMARY KEY(entry_type,reference_id))");
        db.execute("CREATE TABLE avatar_generation_task (id VARCHAR(36) PRIMARY KEY,creator_id VARCHAR(36) NOT NULL,request_key VARCHAR(36) NOT NULL,prompt VARCHAR(1000) NOT NULL,expected_version VARCHAR(36),status VARCHAR(24) NOT NULL,provider_task_id VARCHAR(255),lease_token VARCHAR(36),lease_until TIMESTAMP,next_attempt_at TIMESTAMP,failures INT NOT NULL DEFAULT 0,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,UNIQUE(creator_id,request_key))");
        db.update("INSERT INTO creator(id) VALUES ('alice')");
        images=new MemoryImages();avatars=new AvatarService(db,images,manager);ledger=new JdbcCreditsLedger(db,tx);ledger.credit("alice",20,"seed");
        provider=mock(AvatarImageProvider.class);when(provider.configured()).thenReturn(true);when(provider.submit(anyString())).thenReturn("remote");
        var pricing=mock(CreditsPricing.class);when(pricing.enabled()).thenReturn(true);
        service=new AvatarGenerationService(db,tx,avatars,provider,ledger,pricing,3);
    }
    private InputStream picture(int value){return new ByteArrayInputStream(new byte[]{(byte)value});}

    @Test void expiredWorkerCannotInstallImageAfterReplacementWorkerRefunds() throws Exception {
        var original=avatars.save("alice",picture(1));String oldKey=avatars.image("alice").key();
        var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","image"),new AvatarImageProvider.Result("FAILED",null));
        when(provider.download("image")).thenAnswer(call->picture(2));
        var prepared=new CountDownLatch(1);var resume=new CountDownLatch(1);
        images.afterSave.set(()->{prepared.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new AssertionError("worker did not resume");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var oldWorker=executor.submit(service::runOnce);assertTrue(prepared.await(5,TimeUnit.SECONDS));
            db.update("UPDATE avatar_generation_task SET lease_until=? WHERE id=?",Timestamp.from(Instant.now().minusSeconds(1)),task.id());
            service.runOnce();assertEquals("FAILED",service.latest("alice").status());assertEquals(20,ledger.balance("alice"));
            resume.countDown();oldWorker.get(5,TimeUnit.SECONDS);
            assertEquals(original.version(),avatars.current("alice").version());assertEquals(Set.of(oldKey),images.files.keySet());
            assertEquals("FAILED",service.latest("alice").status());assertEquals(20,ledger.balance("alice"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE entry_type='REFUND'",Integer.class));
            verify(provider,times(1)).submit("猫");
        } finally {resume.countDown();executor.shutdownNow();}
    }
    @Test void expiredLeaseWithoutReplacementCannotApplyAndDoesNotRefund() {
        var original=avatars.save("alice",picture(1));String oldKey=avatars.image("alice").key();
        var task=service.start("alice",UUID.randomUUID().toString(),"猫");String token=UUID.randomUUID().toString();
        db.update("UPDATE avatar_generation_task SET lease_token=?,lease_until=? WHERE id=?",token,Timestamp.from(Instant.now().minusSeconds(1)),task.id());
        assertThrows(AvatarService.GenerationLeaseLost.class,()->avatars.saveGenerated("alice",original.version(),picture(2),task.id(),token));
        assertEquals(original.version(),avatars.current("alice").version());assertEquals(Set.of(oldKey),images.files.keySet());
        assertEquals(15,ledger.balance("alice"));assertEquals("RUNNING",service.latest("alice").status());
    }
    @Test void liveLeaseCommitsImageAndRecoversTaskWithoutSecondProviderSubmission() throws Exception {
        avatars.save("alice",picture(1));var task=service.start("alice",UUID.randomUUID().toString(),"猫");
        when(provider.query("remote")).thenReturn(new AvatarImageProvider.Result("SUCCEEDED","image"));
        when(provider.download("image")).thenAnswer(call->picture(2));
        service.runOnce();service.runOnce();
        assertEquals(task.id(),avatars.current("alice").version());assertEquals("SUCCEEDED",service.latest("alice").status());
        assertEquals(15,ledger.balance("alice"));assertEquals(1,images.files.size());verify(provider,times(1)).submit("猫");
    }
    @Test void outerRollbackRetainsOldImageAndRemovesOnlyUncommittedNewImage() {
        var original=avatars.save("alice",picture(1));String oldKey=avatars.image("alice").key();
        tx.executeWithoutResult(status->{
            avatars.save("alice",picture(2));
            assertTrue(images.files.containsKey(oldKey));assertEquals(2,images.files.size());
            status.setRollbackOnly();
        });
        assertEquals(original.version(),avatars.current("alice").version());assertEquals(Set.of(oldKey),images.files.keySet());
    }
    @Test void outerCommitDeletesOldImageOnlyAfterDatabaseCommit() {
        avatars.save("alice",picture(1));String oldKey=avatars.image("alice").key();
        tx.executeWithoutResult(status->{avatars.save("alice",picture(2));assertTrue(images.files.containsKey(oldKey));assertEquals(2,images.files.size());});
        assertFalse(images.files.containsKey(oldKey));assertEquals(Set.of(avatars.image("alice").key()),images.files.keySet());
    }
    static final class MemoryImages implements CoverFileStore {
        final Map<String,byte[]> files=new ConcurrentHashMap<>();final AtomicReference<Runnable> afterSave=new AtomicReference<>();
        public Stored save(InputStream input)throws IOException{
            byte[] bytes=input.readAllBytes();String key=UUID.randomUUID().toString();files.put(key,bytes);
            Runnable hook=afterSave.getAndSet(null);if(hook!=null)hook.run();return new Stored(key,bytes.length);
        }
        public InputStream open(String key)throws IOException{byte[] bytes=files.get(key);if(bytes==null)throw new FileNotFoundException();return new ByteArrayInputStream(bytes);}
        public void discard(String key){files.remove(key);}
    }
}
