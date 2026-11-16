package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.Test;
import com.qingfeng.music.conversation.application.ConversationService;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentVideoActionsTest {
 @Test void previewWithoutResolutionShowsAllPricesAndDoesNotSubmit(){
  var service=mock(MusicVideoService.class);when(service.available()).thenReturn(true);
  when(service.prepare(anyString(),anyString(),anyString(),any())).thenAnswer(c->c.getArgument(3));
  var actions=new AgentVideoActions(service,mock(ConversationService.class));
  var result=actions.execute("o","c","preview_music_video",Map.of("workId","w","assetId","a","title","歌","durationMillis",30000L,"shots",List.of(Map.of("prompt","故事开场","seconds",30,"imageSeconds",10,"imageId","img"))),"先预览","k",null);
  assertTrue(result.reply().contains("尚未选择分辨率"));assertTrue(result.reply().contains("720P 40 积分"));assertTrue(result.reply().contains("1080P 60 积分"));
  verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void confirmationFreezesStoryboardAndPriceAndRejectsChangedQuote(){
  var service=mock(MusicVideoService.class);when(service.available()).thenReturn(true);var plan=new MusicVideoService.Plan("旅行","16:9","720P",5000,List.of(new MusicVideoService.Shot("海边旅人",5)));when(service.prepare(anyString(),anyString(),anyString(),any())).thenReturn(plan);
  var actions=new AgentVideoActions(service,mock(ConversationService.class));
  var prepared=actions.prepare("owner","conversation","create_music_video",Map.of("workId","w","assetId","a","title","旅行","shots",List.of(Map.of("prompt","海边旅人","seconds",5))),null);
  assertEquals(10,prepared.get("quotedCredits"));assertTrue(prepared.get("confirmationSummary").toString().contains("海边旅人"));
  prepared.put("resolution","1080P");
  assertThrows(RuntimeException.class,()->actions.execute("owner","conversation","create_music_video",prepared,"确认","key",null));
  verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void uploadedImagesPassThroughPreviewAndOnlyVideoSecondsAreCharged(){
  var service=mock(MusicVideoService.class);when(service.available()).thenReturn(true);
  when(service.prepare(anyString(),anyString(),anyString(),any())).thenAnswer(call->call.getArgument(3));
  var actions=new AgentVideoActions(service,mock(ConversationService.class));
  var args=new LinkedHashMap<String,Object>(Map.of("workId","w","assetId","a","title","旅行","ratio","16:9","resolution","720P","durationMillis",30000L,"shots",List.of(Map.of("prompt","海边","seconds",30,"imageId","owned-image","imageSeconds",10))));
  var prepared=actions.prepare("owner","c","create_music_video",args,null);
  assertEquals(40,prepared.get("quotedCredits"));assertTrue(prepared.get("confirmationSummary").toString().contains("免费图片 10 秒"));
  actions.execute("owner","c","preview_music_video",args,"预览","key",null);
  verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());
  var capture=org.mockito.ArgumentCaptor.forClass(MusicVideoService.Plan.class);verify(service,atLeastOnce()).prepare(eq("owner"),eq("w"),eq("a"),capture.capture());assertEquals("owned-image",capture.getValue().shots().get(0).imageId());
 }
 @Test void uploadCommandContainsOnlyOwnedWorkAndAudioAndDoesNotCreateVideo(){
  var service=mock(MusicVideoService.class);var actions=new AgentVideoActions(service,mock(ConversationService.class));
  var result=actions.execute("owner","c","upload_video_image",Map.of("workId","w","assetId","a"),"上传图片","key",null);
  verify(service).quote("owner","w","a","480P");assertEquals("feature",result.commands().get(0).kind());assertTrue(result.commands().get(0).text().contains("mv_image"));
  verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void unavailableProviderDoesNotAskUserToPay(){var service=mock(MusicVideoService.class);var actions=new AgentVideoActions(service,mock(ConversationService.class));assertThrows(RuntimeException.class,()->actions.prepare("o","c","create_music_video",Map.of(),null));verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());}
}
