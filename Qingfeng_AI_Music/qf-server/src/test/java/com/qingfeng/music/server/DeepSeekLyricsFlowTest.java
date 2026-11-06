package com.qingfeng.music.server;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.common.exception.DomainException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="qingfeng.lyrics.enabled=true") @ActiveProfiles("test") @Import(DeepSeekLyricsFlowTest.Offline.class)
class DeepSeekLyricsFlowTest {
 @TestConfiguration(proxyBeanMethods=false) static class Offline {
  @Bean @Primary FakeWriter fakeWriter(){return new FakeWriter();}
 }
 static class FakeWriter implements LyricsWriter {
  WorkApi.Work lastWork;String lastLanguage;String instructions;String output;
  public Draft write(WorkApi.Work work,String language,String instructions,String previous){this.instructions=instructions;return output==null?write(work,language):new Draft("结构测试",output);}
  AtomicInteger calls=new AtomicInteger();public boolean isConfigured(){return true;}
  public Draft write(WorkApi.Work work,String language){lastWork=work;lastLanguage=language;calls.incrementAndGet();return new Draft("风中的故事",language.equals("en")?"[Verse]\nA little memory stays with me":"[Verse]\n把你的名字写在风里\n[Chorus]\n每个日落都想起你");}
 }
 @Autowired IdentityApi identity;@Autowired WorkApi works;@Autowired LyricsApi lyrics;@Autowired LyricsCompositionService composer;@Autowired FakeWriter writer;
 @Test void templateReachesWriterAndMismatchNeverCreatesRevisionOrRetries(){
  String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("","","一段真实的故事","","民谣",""));
  var structure=new LyricsStructure("ABC",true);String key=UUID.randomUUID().toString();
  try{
   writer.output=String.join("\n",structure.sections().stream().map(s->"["+s+"]"+(s.equals("Interlude")?"":"\n今天仍然有风")).toList());
   var result=composer.compose(owner,work.id(),0,key,"mixed","保留真实细节",null,"ABC",true);
   assertTrue(writer.instructions.contains(structure.instruction()));assertTrue(writer.instructions.startsWith("保留真实细节"));
   assertEquals(result.id(),composer.compose(owner,work.id(),0,key,"mixed","保留真实细节",null,"ABC",true).id());
   var conflict=assertThrows(DomainException.class,()->composer.compose(owner,work.id(),0,key,"mixed","保留真实细节",null,"ABCD",true));
   assertEquals("REQUEST_KEY_CONFLICT",conflict.code());
   int count=lyrics.list(owner,work.id()).size();writer.output="[Verse]\n不符合模板";
   var failure=assertThrows(DomainException.class,()->composer.compose(owner,work.id(),0,UUID.randomUUID().toString(),"mixed","",null,"ABC",false));
   assertEquals("LYRICS_STRUCTURE_MISMATCH",failure.code());assertEquals(count,lyrics.list(owner,work.id()).size());
  }finally{writer.output=null;}
 }
 @Test void freeInspirationAndMixedLanguageReachWriterWithoutInventedRecipient(){
  String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("","","海边旅行与重新出发","","流行",""));
  String key=UUID.randomUUID().toString();int before=writer.calls.get();
  var generated=composer.compose(owner,work.id(),0,key,"mixed");
  assertEquals("mixed",writer.lastLanguage);assertEquals("",writer.lastWork.recipient());assertEquals("",writer.lastWork.occasion());
  assertEquals("海边旅行与重新出发",writer.lastWork.story());assertFalse(generated.confirmed());
  assertEquals(generated.id(),composer.compose(owner,work.id(),0,key,"mixed").id());assertEquals(before+1,writer.calls.get());
 }
 @Test void savesUnconfirmedAiVersionAndReusesSuccessfulRequestWithoutCallingAgain(){
  String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("小雨","纪念","相识的故事","愿你快乐","民谣","故事"));
  var manual=lyrics.save(owner,work.id(),0,"原歌词","不要替换我的旧歌词");String key=UUID.randomUUID().toString();int before=writer.calls.get();
  var generated=composer.compose(owner,work.id(),0,key,"zh-CN");assertFalse(generated.confirmed());assertEquals("DEEPSEEK",generated.source());
  assertEquals(generated.id(),composer.compose(owner,work.id(),0,key,"zh-CN").id());assertEquals(before+1,writer.calls.get());
  assertEquals("不要替换我的旧歌词",lyrics.get(owner,work.id(),manual.id()).lyrics());assertTrue(lyrics.confirm(owner,work.id(),generated.id()).confirmed());
 }
 @Test void refusesOtherOwnersAndStaleStoriesBeforeCallingProvider(){
  String owner=identity.create().creatorId(),other=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","毕业","故事","祝福","爵士","回忆"));int before=writer.calls.get();
  assertThrows(DomainException.class,()->composer.compose(other,work.id(),0,UUID.randomUUID().toString(),"en"));
  assertThrows(DomainException.class,()->composer.compose(owner,work.id(),1,UUID.randomUUID().toString(),"en"));assertEquals(before,writer.calls.get());
 }
 @Test void unknownRequestIsNotAutomaticallySubmittedAgain(){
  String owner=identity.create().creatorId();var work=works.create(owner,new WorkApi.Draft("朋友","生日","故事","祝福","民谣","回忆"));
  var repo=new CompositionRequests(){public java.util.Optional<Entry> find(String w,String k){return java.util.Optional.of(new Entry("UNKNOWN",null));}public boolean claim(String w,String k){throw new AssertionError();}public void complete(String w,String k,String r){throw new AssertionError();}public void failed(String w,String k){throw new AssertionError();}};
  var service=new LyricsCompositionService(works,lyrics,null,writer,repo,true);int before=writer.calls.get();assertThrows(DomainException.class,()->service.compose(owner,work.id(),0,UUID.randomUUID().toString(),"en"));assertEquals(before,writer.calls.get());
 }
}
