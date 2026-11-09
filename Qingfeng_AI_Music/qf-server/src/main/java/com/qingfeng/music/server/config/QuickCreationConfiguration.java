package com.qingfeng.music.server.config;

import com.qingfeng.music.creation.*;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.infrastructure.persistence.generation.JdbcQuickCreationRepository;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"})
public class QuickCreationConfiguration {
    @Bean QuickCreationRepository quickCreationRepository(JdbcTemplate jdbc,PlatformTransactionManager manager){return new JdbcQuickCreationRepository(jdbc,new TransactionTemplate(manager));}
    @Bean QuickCreationService quickCreationService(QuickCreationRepository repository,WorkApi works,LyricsApi lyrics,LyricsCompositionService composer,GenerationApi generation,Environment env,com.qingfeng.music.lyrics.application.LyricsWriter writer,com.qingfeng.music.generation.application.port.MusicProvider provider){
        return new QuickCreationService(repository,works,lyrics,composer,generation,writer.isConfigured()&&provider.isConfigured()&&env.getProperty("qingfeng.lyrics.enabled",Boolean.class,false)&&env.getProperty("qingfeng.quick.enabled",Boolean.class,true)&&env.getProperty("qingfeng.generation.enabled",Boolean.class,false)&&env.getProperty("qingfeng.generation.scheduler-enabled",Boolean.class,true));
    }
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name={"qingfeng.quick.enabled","qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled"},havingValue="true")
    QuickRunner quickRunner(QuickCreationService service,Environment env){return new QuickRunner(service,env.getProperty("qingfeng.quick.workers",Integer.class,4));}
    @Bean org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> quickRunnerStartup(org.springframework.beans.factory.ObjectProvider<QuickRunner> runner){return event->runner.ifAvailable(QuickRunner::start);}
    static final class QuickRunner implements AutoCloseable {
        private final ScheduledThreadPoolExecutor pool;private final QuickCreationService service;private final int workers;private boolean started;
        QuickRunner(QuickCreationService service,int workers){
            if(workers<1||workers>16)throw new IllegalArgumentException("一键创作线程数须在1到16之间");this.workers=workers;this.service=service;
            pool=new ScheduledThreadPoolExecutor(workers,r->{var t=new Thread(r,"quick-creation");t.setDaemon(true);return t;});
            pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);pool.setRemoveOnCancelPolicy(true);
        }
        synchronized void start(){if(started)return;started=true;for(int i=0;i<workers;i++)pool.scheduleWithFixedDelay(()->{try{service.runOnce();}catch(RuntimeException e){System.getLogger(QuickRunner.class.getName()).log(System.Logger.Level.WARNING,"Quick scan interrupted: {0}",e.getClass().getSimpleName());}},0,1000,TimeUnit.MILLISECONDS);}
        public void close(){pool.shutdown();try{if(!pool.awaitTermination(5,TimeUnit.SECONDS))pool.shutdownNow();}catch(InterruptedException e){pool.shutdownNow();Thread.currentThread().interrupt();}}
    }
}
