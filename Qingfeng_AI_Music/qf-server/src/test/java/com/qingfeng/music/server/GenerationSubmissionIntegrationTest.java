package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.application.port.MusicProvider;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="qingfeng.generation.enabled=true") @ActiveProfiles("test") @Import(GenerationSubmissionIntegrationTest.Providers.class)
class GenerationSubmissionIntegrationTest {
 @TestConfiguration(proxyBeanMethods=false)static class Providers{
  @Bean @Primary MusicProvider offlineOnly(){return new MusicProvider(){public String name(){return "test-only";}public boolean isConfigured(){return true;}public Submission submit(Request r,String key){throw new AssertionError("测试不得调用供应商");}public Result query(String id){throw new AssertionError("测试不得查询供应商");}};}
 }
 @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired GenerationApi tasks;
 record Fixture(String owner,String work,String revision){}
 Fixture fixture(){String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("妈妈","生日","故事","祝福","民谣","作品"));var r=lyrics.save(owner,work.id(),0,"歌曲","确认的歌词");lyrics.confirm(owner,work.id(),r.id());return new Fixture(owner,work.id(),r.id());}
 @Test void concurrentSameRequestCreatesOneTask()throws Exception{
  var f=fixture();String key=UUID.randomUUID().toString();var executor=Executors.newFixedThreadPool(2);
  try{var a=executor.submit(()->tasks.submit(f.owner(),f.work(),f.revision(),key));var b=executor.submit(()->tasks.submit(f.owner(),f.work(),f.revision(),key));assertEquals(a.get(10,TimeUnit.SECONDS).id(),b.get(10,TimeUnit.SECONDS).id());assertEquals(1,tasks.list(f.owner(),f.work()).size());}finally{executor.shutdownNow();}
 }
 @Test void laterEditingDoesNotAlterSubmittedSnapshot(){
  var f=fixture();String key=UUID.randomUUID().toString();var task=tasks.submit(f.owner(),f.work(),f.revision(),key);
  works.update(f.owner(),f.work(),0,new WorkApi.Draft("朋友","毕业","新故事","新祝福","爵士","新作品"));
  assertEquals(task.id(),tasks.submit(f.owner(),f.work(),f.revision(),key).id());assertEquals("民谣",tasks.list(f.owner(),f.work()).get(0).style());assertEquals("确认的歌词",tasks.list(f.owner(),f.work()).get(0).lyrics());
 }
}
