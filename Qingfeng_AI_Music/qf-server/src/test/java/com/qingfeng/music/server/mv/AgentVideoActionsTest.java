package com.qingfeng.music.server.mv;
import org.junit.jupiter.api.Test;
import com.qingfeng.music.conversation.application.ConversationService;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentVideoActionsTest {
 @Test void confirmationFreezesStoryboardAndPriceAndRejectsChangedQuote(){
  var service=mock(MusicVideoService.class);when(service.available()).thenReturn(true);when(service.price()).thenReturn(8);
  var actions=new AgentVideoActions(service,mock(ConversationService.class));
  var prepared=actions.prepare("owner","conversation","create_music_video",Map.of("workId","w","assetId","a","title","旅行","shots",List.of(Map.of("prompt","海边旅人","seconds",5))),null);
  assertEquals(8,prepared.get("quotedCredits"));assertTrue(prepared.get("confirmationSummary").toString().contains("海边旅人"));
  when(service.price()).thenReturn(10);
  assertThrows(RuntimeException.class,()->actions.execute("owner","conversation","create_music_video",prepared,"确认","key",null));
  verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());
 }
 @Test void unavailableProviderDoesNotAskUserToPay(){var service=mock(MusicVideoService.class);var actions=new AgentVideoActions(service,mock(ConversationService.class));assertThrows(RuntimeException.class,()->actions.prepare("o","c","create_music_video",Map.of(),null));verify(service,never()).create(anyString(),anyString(),anyString(),anyString(),any());}
}
