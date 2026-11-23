package com.qingfeng.music.server.config;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.usage.UsageBudget;
import com.qingfeng.music.infrastructure.persistence.lyrics.JdbcPlaybackLyricsRepository;
import com.qingfeng.music.infrastructure.provider.KieLyricsTimingProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class PlaybackLyricsConfiguration {
    @Bean LyricsTimingProvider lyricsTimingProvider(Environment env,com.qingfeng.music.infrastructure.provider.KieRequestLimiter limiter){return new KieLyricsTimingProvider(env.getProperty("qingfeng.providers.kie.api-key",env.getProperty("KIE_API_KEY","")),env.getProperty("qingfeng.providers.kie.lyrics-timing-mode","compatible")).withRequestLimits(limiter::beforeCreate,limiter::beforeRead);}
    @Bean PlaybackLyricsRepository playbackLyricsRepository(JdbcTemplate jdbc,PlatformTransactionManager manager,UsageBudget budget){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);return new JdbcPlaybackLyricsRepository(jdbc,tx,budget);}
    @Bean PlaybackLyricsService playbackLyricsService(MediaApi media,PlaybackLyricsRepository repository,LyricsTimingProvider provider,
            Environment env,com.qingfeng.music.server.lyrics.LyricsPreparation preparation,UsageBudget budget){
        boolean writable=!env.acceptsProfiles(org.springframework.core.env.Profiles.of("sg-reader"));
        boolean useQueue=writable&&env.getProperty("qingfeng.lyrics.preparation.enabled",Boolean.class,false);
        return new PlaybackLyricsService(media,repository,provider,writable&&env.getProperty("qingfeng.lyrics.timing.enabled",Boolean.class,true),
            (owner,source,key)->useQueue&&preparation.enqueueInteractive(owner,source,key,budget));
    }
}
