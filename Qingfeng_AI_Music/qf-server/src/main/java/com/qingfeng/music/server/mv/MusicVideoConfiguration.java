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
 @Bean VideoProvider videoProvider(Environment e){return new SeedanceVideoProvider(e.getRequiredProperty("qingfeng.video.base-url"),e.getProperty("qingfeng.video.api-key",""),e.getProperty("qingfeng.video.model",""));}
 @Bean MusicVideoService musicVideoService(JdbcTemplate db,PlatformTransactionManager manager,WorkApi works,MediaApi media,VideoProvider provider,CreditsLedger credits,Environment e){return new MusicVideoService(db,new TransactionTemplate(manager),works,media,provider,credits,e.getProperty("qingfeng.video.credits-per-project",Integer.class,0));}
 @Bean MusicVideoWorker musicVideoWorker(JdbcTemplate db,PlatformTransactionManager manager,VideoProvider provider,MediaApi media,Environment e){return new MusicVideoWorker(db,new TransactionTemplate(manager),provider,media,Path.of(e.getRequiredProperty("qingfeng.video.storage-root")),e.getProperty("qingfeng.video.ffmpeg","ffmpeg"));}
 @Bean AgentVideoActions agentVideoActions(MusicVideoService service,ConversationService conversations){return new AgentVideoActions(service,conversations);}
}
