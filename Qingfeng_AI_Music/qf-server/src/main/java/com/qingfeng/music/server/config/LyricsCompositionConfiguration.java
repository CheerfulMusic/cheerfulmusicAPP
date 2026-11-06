package com.qingfeng.music.server.config;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.infrastructure.persistence.lyrics.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration(proxyBeanMethods=false)
public class LyricsCompositionConfiguration {
    @Bean InspirationRewriter inspirationRewriter(Environment env){return new com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash"));}
    @Bean InspirationRewriteService inspirationRewriteService(InspirationRewriter writer,Environment env){return new InspirationRewriteService(writer,env.getProperty("qingfeng.inspiration.rewrite.enabled",Boolean.class,true),env.getProperty("qingfeng.inspiration.rewrite.concurrent-global",Integer.class,4));}
    @Bean LyricsWriter lyricsWriter(Environment env){return new DeepSeekLyricsWriter(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash"));}
    @Bean @Profile({"mysql","test"}) CompositionRequests compositionRequests(JdbcTemplate jdbc,com.qingfeng.music.usage.UsageBudget budget,org.springframework.transaction.PlatformTransactionManager manager){return new JdbcCompositionRequests(jdbc,budget,new org.springframework.transaction.support.TransactionTemplate(manager));}
    @Bean @Profile({"mysql","test"}) LyricsCompositionService lyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsMapper mapper,LyricsWriter writer,CompositionRequests requests,Environment env,com.qingfeng.music.settings.MusicSettings settings,com.qingfeng.music.settings.CreativePlan.Repository plans){return new LyricsCompositionService(works,lyrics,new MyBatisLyricsRepository(mapper),writer,requests,env.getProperty("qingfeng.lyrics.enabled",Boolean.class,false)).withCreativePlans(settings,plans);}
}
