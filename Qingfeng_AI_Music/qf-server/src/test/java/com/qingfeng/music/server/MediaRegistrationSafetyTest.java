package com.qingfeng.music.server;
import com.qingfeng.music.media.application.*;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.infrastructure.persistence.media.*;
import java.io.*;import java.lang.reflect.*;import java.util.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:media_registration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1") @ActiveProfiles("test")
class MediaRegistrationSafetyTest {
 @Autowired WorkApi works;@Autowired IdentityApi identities;@Autowired MediaMapper mapper;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
 static class Files implements MediaFileStore{Set<String> keys=new HashSet<>();int discarded;public Saved save(InputStream in){String key="local:"+UUID.randomUUID();keys.add(key);return new Saved(key,48,"audio/wav","test-sha");}public InputStream open(String key){return new ByteArrayInputStream(new byte[48]);}public boolean exists(String key){return keys.contains(key);}public void discard(String key){keys.remove(key);discarded++;}}
 record Fixture(String owner,String work){}
 Fixture fixture(){String o=identities.create().creatorId();return new Fixture(o,works.create(o,new WorkApi.Draft("妈妈","生日","回忆","祝福","民谣","歌")).id());}
 MediaMapper fault(java.util.function.Consumer<Method> hook){return (MediaMapper)Proxy.newProxyInstance(MediaMapper.class.getClassLoader(),new Class[]{MediaMapper.class},(p,m,a)->{hook.accept(m);try{return m.invoke(mapper,a);}catch(InvocationTargetException e){throw e.getCause();}});}
 long bytes(Fixture f){return jdbc.queryForObject("SELECT media_bytes FROM creator WHERE id=?",Long.class,f.owner);}
 @Test void assetSelectionAndSettlementRollbackTogetherUsingRealSql(){
  var f=fixture();var files=new Files();var failed=fault(m->{if(m.getName().equals("ensureSelection")){assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE work_id=?",Integer.class,f.work));throw new IllegalStateException("injected after INSERT");}});
  var service=new MediaService(works,new MyBatisMediaRepository(failed,manager),files,1073741824L);
  var error=assertThrows(MediaRepository.RegistrationFailure.class,()->service.importAudio(f.owner,f.work,InputStream.nullInputStream()));assertTrue(error.rolledBack());
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE work_id=?",Integer.class,f.work));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM work_audio_selection WHERE work_id=?",Integer.class,f.work));assertEquals(0,bytes(f));assertEquals(1,files.discarded);assertTrue(files.keys.isEmpty());
 }
 @Test void failureAfterCommitDoesNotDeleteAReferencedFileOrRefundCapacity(){
  var f=fixture();var files=new Files();var afterCommit=fault(m->{if(m.getName().equals("automaticSelection"))TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){public void afterCommit(){throw new IllegalStateException("lost commit acknowledgement");}});});
  var service=new MediaService(works,new MyBatisMediaRepository(afterCommit,manager),files,1073741824L);
  var error=assertThrows(MediaRepository.RegistrationFailure.class,()->service.importAudio(f.owner,f.work,InputStream.nullInputStream()));assertFalse(error.rolledBack());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM media_asset WHERE work_id=?",Integer.class,f.work));assertEquals(48,bytes(f));assertEquals(0,files.discarded);assertEquals(1,files.keys.size());
  String asset=jdbc.queryForObject("SELECT asset_id FROM work_audio_selection WHERE work_id=?",String.class,f.work);assertEquals(48,service.open(f.owner,f.work,asset).size());
 }
 @Test void successfulRegistrationAccountsForActualBytesOnly(){var f=fixture();var files=new Files();var service=new MediaService(works,new MyBatisMediaRepository(mapper,manager),files,1073741824L);var a=service.importAudio(f.owner,f.work,InputStream.nullInputStream());assertEquals(48,bytes(f));assertEquals(a.id(),service.selection(f.owner,f.work).assetId());assertEquals(0,files.discarded);}
}
