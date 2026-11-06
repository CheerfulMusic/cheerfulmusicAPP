package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.*;
import com.qingfeng.music.generation.application.port.*;
import com.qingfeng.music.infrastructure.persistence.generation.*;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"}) @EnableScheduling
public class ExecutionConfiguration {
    @Bean ExecutionRepository executionRepository(ExecutionMapper mapper, org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager, com.qingfeng.music.credits.CreditsLedger credits){return new MyBatisExecutionRepository(mapper).withCredits(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager),credits);}
    @Bean AudioDownloader audioDownloader(@Value("${qingfeng.generation.audio-hosts:}")String hosts,ProviderAudioUrls audioUrls){return new AllowlistedAudioDownloader(Arrays.stream(hosts.split(",")).map(String::trim).map(String::toLowerCase).filter(s->!s.isEmpty()).collect(Collectors.toSet()),audioUrls);}
    @Bean AudioCandidates audioCandidates(org.springframework.jdbc.core.JdbcTemplate jdbc){return new JdbcAudioCandidates(jdbc);}
    @Bean GenerationWorker generationWorker(ExecutionRepository repository,MusicProvider provider,AudioDownloader downloader,GeneratedAudioArchive archive,com.qingfeng.music.generation.application.SectionEditRepository edits,com.qingfeng.music.settings.MusicSettings settings,AudioCandidates candidates){return new GenerationWorker(repository,provider,downloader,archive,Clock.systemUTC(),Duration.ofMinutes(2),5).withSectionEdits(edits).withSettings(settings).withCandidates(candidates);}
    @Bean @ConditionalOnProperty(name={"qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled"},havingValue="true")
    Runner generationRunner(GenerationWorker worker){return new Runner(worker);}
    static class Runner{
        private final GenerationWorker worker;
        Runner(GenerationWorker worker){this.worker=worker;}
        @Scheduled(fixedDelayString="${qingfeng.generation.scan-ms:3000}")
        void scan(){try{worker.runOnce();}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(Runner.class).warn("任务扫描失败，将稍后重新扫描；异常类型={}",e.getClass().getSimpleName());}}
    }
}
