package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.provider.KieAudioTranscriptionProvider;
import com.qingfeng.music.infrastructure.provider.KieRequestLimiter;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.server.lyrics.LyricsPreparation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"mysql","test"})
public class LyricsPreparationConfiguration {
    @Bean AudioTranscriptionProvider audioTranscriptionProvider(Environment env,KieRequestLimiter limiter) {
        return new KieAudioTranscriptionProvider(env.getProperty("qingfeng.lyrics.preparation.transcription-api-key",
                env.getProperty("qingfeng.providers.kie.api-key","")),limiter::beforeCreate,limiter::beforeRead);
    }
    @Bean LyricsPreparation lyricsPreparation(JdbcTemplate db,PlatformTransactionManager manager,
            PlaybackLyricsRepository timelines,LyricsTimingProvider original,AudioTranscriptionProvider transcription,Environment env) {
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new LyricsPreparation(db,tx,timelines,original,transcription,
                env.getProperty("qingfeng.lyrics.preparation.daily-limit",Integer.class,40),
                env.getProperty("qingfeng.lyrics.preparation.transcription-fallback-enabled",Boolean.class,true));
    }
    @Bean
    @Profile("!sg-reader")
    @ConditionalOnProperty(name={"qingfeng.lyrics.preparation.enabled","qingfeng.lyrics.preparation.scheduler-enabled"},havingValue="true")
    PreparationScheduler lyricsPreparationScheduler(LyricsPreparation preparation,Environment env) {
        return new PreparationScheduler(preparation,
                env.getProperty("qingfeng.lyrics.preparation.delay-ms",Long.class,5000L),
                env.getProperty("qingfeng.lyrics.preparation.initial-delay-ms",Long.class,30000L),
                env.getProperty("qingfeng.lyrics.preparation.backfill-published",Boolean.class,true),
                env.getProperty("qingfeng.lyrics.timing.enabled",Boolean.class,true));
    }
    /** A private executor avoids becoming the default scheduler of music/payment workers. */
    static final class PreparationScheduler implements org.springframework.context.SmartLifecycle {
        private final LyricsPreparation worker;
        private final long delay,initialDelay;
        private final boolean backfillPublished,enabled;
        private java.util.concurrent.ScheduledExecutorService executor;
        private volatile boolean running;
        PreparationScheduler(LyricsPreparation worker,long delay,long initialDelay,boolean backfillPublished,boolean enabled){
            this.worker=worker;this.delay=Math.max(1000,delay);this.initialDelay=Math.max(1000,initialDelay);
            this.backfillPublished=backfillPublished;this.enabled=enabled;
        }
        public synchronized void start(){
            if(running)return;
            executor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"lyrics-preparation");thread.setDaemon(true);return thread;});
            running=true;
            executor.scheduleWithFixedDelay(()->{
                try{if(enabled&&worker.schemaAvailable()){worker.enqueueNew(25,backfillPublished);worker.processOnce();}}
                catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(PreparationScheduler.class).warn("Lyrics preparation scan failed: {}",error.getClass().getSimpleName());}
            },initialDelay,delay,java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        public synchronized void stop(){running=false;if(executor!=null)executor.shutdownNow();}
        public boolean isRunning(){return running;}
    }
}
