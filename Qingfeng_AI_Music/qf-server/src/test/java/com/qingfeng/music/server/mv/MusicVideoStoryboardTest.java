package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.server.agent.AgentAdmission;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.conversation.api.Conversation;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MusicVideoStoryboardTest {
 @SuppressWarnings("unchecked") @Test void usesOwnedLyricsAndMusicDirectionAndOnlyReturnsEditablePrompts(){
  var service=mock(MusicVideoService.class);var works=mock(WorkApi.class);var media=mock(MediaApi.class);var lyrics=mock(LyricsApi.class);var settings=mock(MusicSettings.class);
  var model=mock(AgentChatModel.class);var admission=mock(AgentAdmission.class);var conversations=mock(ConversationService.class);
  ObjectProvider<AgentChatModel> models=mock(ObjectProvider.class);ObjectProvider<AgentAdmission> admissions=mock(ObjectProvider.class);when(models.getIfAvailable()).thenReturn(model);when(admissions.getIfAvailable()).thenReturn(admission);
  var song=new WorkApi.Work("w","","","","","民谣","下一波",1,null,null);when(works.get("o","w")).thenReturn(song);
  when(media.get("o","w","a")).thenReturn(new MediaApi.Asset("a","w","GENERATED","audio/mpeg",1000,"sha",null));when(media.generatedRevision("o","w","a")).thenReturn(Optional.of("r"));
  when(lyrics.get("o","w","r")).thenReturn(new LyricsApi.Revision("r","w",1,"下一波","下一波，我还在","AI",true,null));
  when(settings.get("o","w")).thenReturn(new MusicSettings.View(song,new MusicSettings.Direction(null,"folk",null,"温暖民谣","warm folk",1)));
  when(conversations.current("o")).thenReturn(new Conversation("c","o","w",null,null));var ticket=new AgentAdmission.Ticket("ticket","o",false);when(admission.acquire(eq("o"),eq("c"),eq("key"),anyString(),eq("zh-CN"))).thenReturn(ticket);
  when(service.quote("o","w","a","720P")).thenReturn(new MusicVideoService.Quote(60000,60,120,"720P"));
  var requested=new MusicVideoService.Plan("下一波","16:9","720P",60000,List.of(new MusicVideoService.Shot("",30,"image",5),new MusicVideoService.Shot("",30)));
  when(model.respond(any())).thenReturn(new AgentChatModel.Plan("create_music_video",Map.of("shots",List.of(Map.of("prompt","同一位少年站在海边，暖色远景"),Map.of("prompt","同一位少年迎向晨光，镜头缓慢拉远"))),""));
  when(service.prepare(eq("o"),eq("w"),eq("a"),any())).thenAnswer(i->i.getArgument(3));
  try(var storyboard=new MusicVideoStoryboard(service,works,media,lyrics,settings,models,admissions,conversations)){
   var result=storyboard.create("o","w","a","key",requested);assertEquals(2,result.shots().size());assertEquals("image",result.shots().get(0).imageId());assertEquals(5,result.shots().get(0).imageSeconds());
   var context=org.mockito.ArgumentCaptor.forClass(AgentChatModel.Context.class);verify(model).respond(context.capture());assertTrue(context.getValue().results().contains("下一波，我还在"));assertTrue(context.getValue().results().contains("folk"));verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());verify(admission).finish(ticket,true);
  }
 }
}
