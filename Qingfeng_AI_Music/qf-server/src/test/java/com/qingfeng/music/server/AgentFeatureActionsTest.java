package com.qingfeng.music.server;
import com.qingfeng.music.agent.application.AgentChatPolicy;
import com.qingfeng.music.server.agent.AgentFeatureActions;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.download.SharedDownloadGrants;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.creation.QuickCreationService;
import com.qingfeng.music.infrastructure.provider.AgentToolSchemaCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentFeatureActionsTest {
 WorkApi works=mock(WorkApi.class);LyricsApi lyrics=mock(LyricsApi.class);ConversationService conversations=mock(ConversationService.class);
 ArtistProfileService profiles=mock(ArtistProfileService.class);SharedDownloadGrants downloads=mock(SharedDownloadGrants.class);
 @SuppressWarnings("unchecked") ObjectProvider<QuickCreationService> quick=mock(ObjectProvider.class);
 AgentFeatureActions actions=new AgentFeatureActions(works,lyrics,conversations,profiles,quick,mock(LyricSelectionRewriteService.class),mock(LyricLineRewriteService.class),mock(LyricsCompositionService.class),mock(MusicSettings.class),downloads,mock(CreditsPricing.class));
 WorkApi.Work work(long version){return new WorkApi.Work("w","","","故事","","民谣","歌曲",version,Instant.now(),Instant.now(),"AUTO");}
 ArtistProfileService.ReleaseSelection selection(long version){return new ArtistProfileService.ReleaseSelection(true,true,"public","艺名",new ArtistProfileService.Release("r",version,"歌曲","w","asset",null,false),false,false);}
 @Test void everyAddedToolHasNativeSchemaAndFinancialReviewRemainsUserOperated(){
  for(var cap:actions.capabilities())assertTrue(AgentToolSchemaCatalog.supports(cap.id()),cap.id());
  assertTrue(AgentToolSchemaCatalog.supports("generate_avatar"));
  assertFalse(actions.capabilities().stream().anyMatch(c->c.id().contains("risk")||c.id().contains("resolve_case")));
  AgentToolSchemaCatalog.validateNativeArguments("get_order",Map.of("orderId","id","provider","stripe"));
 }
 @Test void publicationNeedsExplicitIntentAndConfirmation(){
  assertTrue(AgentChatPolicy.requiresConfirmation("publish_artist_song"));assertTrue(AgentChatPolicy.requiresConfirmation("publish_to_discover"));
  assertTrue(AgentChatPolicy.applicationAllowed("publish_to_discover","把这首歌发布到发现页"));
  assertFalse(AgentChatPolicy.applicationAllowed("publish_to_discover","不要发布到发现页"));
  assertFalse(AgentChatPolicy.applicationAllowed("publish_to_discover","解释如何发布到发现页"));
  assertTrue(AgentChatPolicy.requiresConfirmation("start_quick_creation"));
 }
 @Test void staleReleaseCannotPublishANewerVersionUnderOldConfirmation(){
  when(works.get("owner","w")).thenReturn(work(3));when(profiles.hasAccount("owner")).thenReturn(true);when(profiles.selection("owner","w")).thenReturn(selection(2));
  var args=actions.prepare("owner","c","publish_to_discover",Map.of("workId","w"),null);
  when(profiles.selection("owner","w")).thenReturn(selection(3));
  assertThrows(RuntimeException.class,()->actions.execute("owner","c","publish_to_discover",args,"确认","key",null));
  verify(profiles,never()).publishToDiscover(anyString(),anyString(),anyLong());
 }
 @Test void publishUsesFrozenOwnedReleaseVersion(){
  when(works.get("owner","w")).thenReturn(work(3));when(profiles.hasAccount("owner")).thenReturn(true);when(profiles.selection("owner","w")).thenReturn(selection(2));
  var args=actions.prepare("owner","c","publish_to_discover",Map.of("workId","w"),null);
  actions.execute("owner","c","publish_to_discover",args,"确认","key",null);
  verify(profiles).publishToDiscover("owner","w",2);
 }
 @Test void anonymousOwnerCannotPublishOrReadPurchasedDownloads(){
  assertThrows(RuntimeException.class,()->actions.execute("guest","c","list_shared_downloads",Map.of(),"","key",null));verifyNoInteractions(downloads);
  assertThrows(RuntimeException.class,()->actions.prepare("guest","c","set_artist_visibility",Map.of("published",true),null));verify(profiles,never()).setPublished(anyString(),anyBoolean());
 }
 @Test void nativeWorkPanelChecksOwnershipAndNeverForwardsUrls(){
  when(works.get("owner","foreign")).thenThrow(new IllegalArgumentException("not owned"));
  assertThrows(RuntimeException.class,()->actions.execute("owner","c","open_feature",Map.of("feature","work","workId","foreign"),"","key",null));
  assertThrows(RuntimeException.class,()->actions.execute("owner","c","open_feature",Map.of("feature","https://outside.example"),"","key",null));
  verifyNoInteractions(profiles,downloads);
 }
}
