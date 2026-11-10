package com.qingfeng.music.server.config;
import com.qingfeng.music.lyrics.application.*;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.infrastructure.provider.DeepSeekLyricsWriter;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.infrastructure.persistence.lyrics.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration(proxyBeanMethods=false)
public class LyricsCompositionConfiguration {
    @Bean InspirationRewriter inspirationRewriter(Environment env){return new com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash"));}
    // 无数据库的 local 演示环境没有积分账本，改写降级为不计费；mysql/test 下正常计费。
    @Bean InspirationRewriteService inspirationRewriteService(InspirationRewriter writer,Environment env,org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.credits.RewriteCharges> charges){return new InspirationRewriteService(writer,env.getProperty("qingfeng.inspiration.rewrite.enabled",Boolean.class,true),env.getProperty("qingfeng.inspiration.rewrite.concurrent-global",Integer.class,4),charges.getIfAvailable(() -> com.qingfeng.music.credits.RewriteCharges.FREE));}
    @Bean @Profile({"mysql","test"})
    LyricLineRewriteService lyricLineRewriteService(WorkApi works,Environment env){
        var writer=new com.qingfeng.music.infrastructure.provider.DeepSeekInspirationRewriter(
            env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),
            env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash")).lyricLineMode();
        var previews=new InspirationRewriteService(writer,env.getProperty("qingfeng.lyrics.line-rewrite.enabled",Boolean.class,true),
            env.getProperty("qingfeng.lyrics.line-rewrite.concurrent-global",Integer.class,4));
        return new LyricLineRewriteService(works,previews,env.getProperty("qingfeng.lyrics.line-rewrite.per-creator-per-15-minutes",Integer.class,12));
    }
    @Bean LyricsWriter lyricsWriter(Environment env){
        return switch(env.getProperty("qingfeng.lyrics.provider","deepseek").trim().toLowerCase(java.util.Locale.ROOT)){
            case "openai" -> {
                var primary = new OpenAiLyricsWriter(
                    env.getProperty("qingfeng.providers.openai.api-key",env.getProperty("OPENAI_API_KEY","")),
                    env.getProperty("qingfeng.providers.openai.model",OpenAiLyricsWriter.DEFAULT_MODEL),
                    env.getProperty("qingfeng.providers.openai.proxy-url",""));
                yield env.getProperty("qingfeng.lyrics.deepseek-fallback-enabled",Boolean.class,false)
                    ? new FallbackLyricsWriter(primary,deepSeekLyricsWriter(env)) : primary;
            }
            case "deepseek" -> deepSeekLyricsWriter(env);
            default -> throw new IllegalArgumentException("Unsupported qingfeng.lyrics.provider; use openai or deepseek");
        };
    }
    private DeepSeekLyricsWriter deepSeekLyricsWriter(Environment env){
        return new DeepSeekLyricsWriter(env.getProperty("qingfeng.providers.deepseek.api-key",env.getProperty("DEEPSEEK_API_KEY","")),
            env.getProperty("qingfeng.providers.deepseek.model","deepseek-flash"));
    }
    @Bean @Profile({"mysql","test"}) CompositionRequests compositionRequests(JdbcTemplate jdbc,com.qingfeng.music.usage.UsageBudget budget,org.springframework.transaction.PlatformTransactionManager manager,com.qingfeng.music.credits.CreditsLedger credits,com.qingfeng.music.credits.CreditConsumptions consumptions){return new JdbcCompositionRequests(jdbc,budget,new org.springframework.transaction.support.TransactionTemplate(manager),credits,consumptions);}
    @Bean @Profile({"mysql","test"}) LyricsCompositionService lyricsCompositionService(WorkApi works,LyricsApi lyrics,LyricsMapper mapper,LyricsWriter writer,CompositionRequests requests,Environment env,com.qingfeng.music.settings.MusicSettings settings,com.qingfeng.music.settings.CreativePlan.Repository plans){return new LyricsCompositionService(works,lyrics,new MyBatisLyricsRepository(mapper),writer,requests,env.getProperty("qingfeng.lyrics.enabled",Boolean.class,false)).withCreativePlans(settings,plans);}
}
