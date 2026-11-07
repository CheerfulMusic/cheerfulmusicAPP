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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.Set;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class CoverConfiguration {
 private final ProviderAudioUrls approved=new ProviderAudioUrls();
 @Bean CoverRepository coverRepository(JdbcTemplate jdbc,PlatformTransactionManager manager){return new JdbcCoverRepository(jdbc,new TransactionTemplate(manager));}
 @Bean ImageEditProvider imageEditProvider(Environment env,com.qingfeng.music.infrastructure.provider.KieRequestLimiter limiter){String key=env.getProperty("qingfeng.providers.kie.image-api-key",env.getProperty("qingfeng.providers.kie.api-key",env.getProperty("KIE_API_KEY","")));return new KieImageEditProvider(key,env.getProperty("qingfeng.cover.ai.enabled",Boolean.class,true),approved::approve).withRequestLimits(limiter::beforeCreate,limiter::beforeRead);}
 @Bean ImageResultDownloader imageResultDownloader(){var downloader=new AllowlistedAudioDownloader(Set.of(),approved);return downloader::open;}
 @Bean CoverService coverService(WorkApi works,CoverRepository repo,CoverFileStore files,MediaMapper media,UsageBudget budget,Environment env,ImageEditProvider provider,ImageResultDownloader downloader){
  return new CoverService(works,repo,files,new MyBatisMediaRepository(media),provider,budget,downloader,env.getProperty("qingfeng.media.creator-capacity-bytes",Long.class,1073741824L));
 }
 @Bean @ConditionalOnProperty(name="qingfeng.cover.ai.scheduler-enabled",havingValue="true",matchIfMissing=true) Runner coverRunner(CoverService service){return new Runner(service);}
 static class Runner{final CoverService service;Runner(CoverService service){this.service=service;}@Scheduled(fixedDelayString="${qingfeng.cover.ai.scan-ms:5000}")void run(){try{service.runOnce();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(Runner.class).warn("封面扫描异常，稍后重试，类型={}",e.getClass().getSimpleName());}}}
}
