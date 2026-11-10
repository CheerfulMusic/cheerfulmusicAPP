package com.qingfeng.music.server.config;
import com.qingfeng.music.settings.*;
import com.qingfeng.music.infrastructure.persistence.generation.JdbcMusicSettingsRepository;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class MusicSettingsConfiguration {
 @Bean MusicBriefService musicBriefService(org.springframework.core.env.Environment env,org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.credits.DailyQuota> quota){
  var writer=new com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash")).musicBriefMode();
  // 无数据库的 local 演示环境没有配额表，降级为不限；mysql/test 下正常限次。
  return new MusicBriefService(writer,env.getProperty("qingfeng.music-settings.simplify.enabled",Boolean.class,true),env.getProperty("qingfeng.music-settings.simplify.concurrent-global",Integer.class,2),quota.getIfAvailable(() -> com.qingfeng.music.credits.DailyQuota.FREE),env.getProperty("qingfeng.music-settings.simplify.daily-per-creator",Long.class,20L));
 }
 @Bean CreativePlan.Repository creativePlans(JdbcTemplate jdbc){return new com.qingfeng.music.infrastructure.persistence.generation.JdbcCreativePlanRepository(jdbc);}
 @Bean MusicSettings musicSettings(JdbcTemplate jdbc,WorkApi works,CreativePlan.Repository plans,com.qingfeng.music.generation.application.port.MusicProvider provider)throws java.io.IOException{try(var in=new ClassPathResource("music/catalog-v1.json").getInputStream()){return new MusicSettingsService(new ObjectMapper().readValue(in,MusicSettings.Catalog.class),new JdbcMusicSettingsRepository(jdbc),works).withCreativePlans(plans).withLimits(provider.limits()).withLegacyStyle((style,voice)->provider.prepare(new com.qingfeng.music.generation.application.port.MusicProvider.Request("","",style,voice)).style());}}
}
