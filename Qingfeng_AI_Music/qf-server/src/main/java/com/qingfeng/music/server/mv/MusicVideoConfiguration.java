package com.qingfeng.music.server.mv;

import com.qingfeng.music.credits.CreditsLedger;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.conversation.application.ConversationService;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.Path;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
@ConditionalOnProperty(name="qingfeng.video.enabled",havingValue="true")
public class MusicVideoConfiguration {
 @Bean VideoProvider videoProvider(Environment e){
  String url=e.getRequiredProperty("qingfeng.video.base-url"),key=e.getProperty("qingfeng.video.api-key",""),model=e.getProperty("qingfeng.video.model","");
  return switch(e.getProperty("qingfeng.video.provider","seedance")){
   case "wan" -> new WanVideoProvider(url,key,model);
   case "seedance" -> new SeedanceVideoProvider(url,key,model);
   default -> throw new IllegalArgumentException("Unknown video provider");
  };
 }
 @Bean VideoImages videoImages(Environment e){return new VideoImages(Path.of(e.getRequiredProperty("qingfeng.video.storage-root")).resolve("images"));}
 @Bean MusicVideoService musicVideoService(JdbcTemplate db,PlatformTransactionManager manager,WorkApi works,MediaApi media,VideoProvider provider,CreditsLedger credits,VideoImages images,AudioDuration duration){return new MusicVideoService(db,new TransactionTemplate(manager),works,media,provider,credits,duration,images);}
 @Bean MusicVideoWorker musicVideoWorker(JdbcTemplate db,PlatformTransactionManager manager,VideoProvider provider,MediaApi media,VideoImages images,Environment e){return new MusicVideoWorker(db,new TransactionTemplate(manager),provider,media,Path.of(e.getRequiredProperty("qingfeng.video.storage-root")),e.getProperty("qingfeng.video.ffmpeg","ffmpeg"),images);}
 @Bean MusicVideoStoryboard musicVideoStoryboard(MusicVideoService service,WorkApi works,MediaApi media,com.qingfeng.music.lyrics.api.LyricsApi lyrics,com.qingfeng.music.settings.MusicSettings settings,org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.agent.api.AgentChatModel> models,org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.server.agent.AgentAdmission> admissions,ConversationService conversations){return new MusicVideoStoryboard(service,works,media,lyrics,settings,models,admissions,conversations);}
 @Bean AgentVideoActions agentVideoActions(MusicVideoService service,ConversationService conversations){return new AgentVideoActions(service,conversations);}
}
