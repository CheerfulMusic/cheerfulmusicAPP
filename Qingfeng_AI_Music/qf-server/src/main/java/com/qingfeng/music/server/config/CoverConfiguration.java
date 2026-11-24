package com.qingfeng.music.server.config;
import com.qingfeng.music.cover.application.*;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.usage.UsageBudget;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcCoverRepository;
import com.qingfeng.music.infrastructure.persistence.media.*;
import com.qingfeng.music.infrastructure.provider.KieImageEditProvider;
import com.qingfeng.music.infrastructure.storage.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.qingfeng.music.infrastructure.provider.KieRequestLimiter;
import com.qingfeng.music.server.ai.CoverCapacityCallbacks;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Set;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class CoverConfiguration {
 private final ProviderAudioUrls approved=new ProviderAudioUrls();
 @Bean CoverRepository coverRepository(JdbcTemplate jdbc,PlatformTransactionManager manager){return new JdbcCoverRepository(jdbc,new TransactionTemplate(manager));}
 @Bean ImageEditProvider imageEditProvider(Environment env, KieRequestLimiter limiter, JdbcTemplate jdbc, PlatformTransactionManager manager) {
  String key = env.getProperty("qingfeng.providers.kie.image-api-key", env.getProperty("qingfeng.providers.kie.api-key", env.getProperty("KIE_API_KEY", "")));
  String image = "qingfeng.providers.kie.image-rate-limit.";
  String scope = env.getProperty(image + "scope");
  if (scope != null) {
   String musicScope = env.getProperty("qingfeng.providers.kie.rate-limit.scope", "primary");
   String musicKey = env.getProperty("qingfeng.providers.kie.api-key", env.getProperty("KIE_API_KEY", ""));
   if (scope.equals(musicScope) || key.isBlank() || key.equals(musicKey))
    throw new IllegalArgumentException("A separate cover rate limit requires a separate provider account");
   limiter = new KieRequestLimiter(jdbc, new TransactionTemplate(manager), scope,
       env.getProperty(image + "creates-per-ten-seconds", Integer.class, 18),
       env.getProperty(image + "reads-per-second", Integer.class, 8),
       java.time.Duration.ofSeconds(env.getProperty(image + "max-wait-seconds", Long.class, 10L)), true);
  }
  return new KieImageEditProvider(key, env.getProperty("qingfeng.cover.ai.enabled", Boolean.class, true), approved::approve)
      .withRequestLimits(limiter::beforeCreate, limiter::beforeRead);
 }
 @Bean ImageResultDownloader imageResultDownloader(){var downloader=new AllowlistedAudioDownloader(Set.of(),approved);return downloader::open;}
 @Bean CoverService coverService(WorkApi works,CoverRepository repo,CoverFileStore files,MediaMapper media,UsageBudget budget,Environment env,ImageEditProvider provider,ImageResultDownloader downloader,
   org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue> queue,
   org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.server.ai.AiCapacity> settings,
   org.springframework.beans.factory.ObjectProvider<CoverCapacityCallbacks> callbacks){
  var service = new CoverService(works,repo,files,new MyBatisMediaRepository(media),provider,budget,downloader,env.getProperty("qingfeng.media.creator-capacity-bytes",Long.class,1073741824L));
  queue.ifAvailable(jobs -> service.withEditQueue((owner, work, key, source, prompt, insert) -> {
   var lane = com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue.Lane.COVER;
   jobs.submit(lane, owner, work, key, source + "\n" + prompt, settings.getObject().limits(lane), job -> insert.run());
  }));
  callbacks.ifAvailable(notifications -> service.withQueueCallback(notifications::url));
  return service;
 }
 @Bean
 @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
   "${qingfeng.cover.ai.scheduler-enabled:true} and !${qingfeng.ai.capacity.enabled:false}")
 Runner coverRunner(CoverService service){return new Runner(service);}
 static class Runner{final CoverService service;Runner(CoverService service){this.service=service;}@Scheduled(fixedDelayString="${qingfeng.cover.ai.scan-ms:5000}")void run(){try{service.runOnce();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(Runner.class).warn("封面扫描异常，稍后重试，类型={}",e.getClass().getSimpleName());}}}
}
