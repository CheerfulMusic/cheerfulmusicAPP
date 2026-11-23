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

@Configuration(proxyBeanMethods=false) @Profile({"mysql","test"}) @Import(PrimarySchedulingConfiguration.class)
public class ExecutionConfiguration {
    @Bean ExecutionRepository executionRepository(ExecutionMapper mapper, org.springframework.jdbc.core.JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager, com.qingfeng.music.credits.CreditsLedger credits,
            com.qingfeng.music.credits.LyricFreeAllowance lyricFreeAllowance,
            org.springframework.beans.factory.ObjectProvider<com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs> coverJobs){
        var repository = new MyBatisExecutionRepository(mapper)
                .withCredits(jdbc,new org.springframework.transaction.support.TransactionTemplate(manager),credits)
                .withLyricFreeAllowance(lyricFreeAllowance);
        coverJobs.ifAvailable(jobs -> repository.withAutomaticCover(jobs::enqueue));
        return repository;
    }
    @Bean AudioDownloader audioDownloader(@Value("${qingfeng.generation.audio-hosts:}")String hosts,ProviderAudioUrls audioUrls){return new AllowlistedAudioDownloader(Arrays.stream(hosts.split(",")).map(String::trim).map(String::toLowerCase).filter(s->!s.isEmpty()).collect(Collectors.toSet()),audioUrls);}
    @Bean AudioCandidates audioCandidates(org.springframework.jdbc.core.JdbcTemplate jdbc){return new JdbcAudioCandidates(jdbc);}
    @Bean GenerationWorker generationWorker(ExecutionRepository repository,MusicProvider provider,AudioDownloader downloader,GeneratedAudioArchive archive,com.qingfeng.music.generation.application.SectionEditRepository edits,com.qingfeng.music.settings.MusicSettings settings,AudioCandidates candidates,AudioReferenceClips audioReferences,@Value("${qingfeng.generation.review-after-minutes:30}")long reviewMinutes,@Value("${qingfeng.generation.max-query-failures:6}")int queryFailures,@Value("${qingfeng.generation.max-in-flight:100}")int maxInFlight){return new GenerationWorker(repository,provider,downloader,archive,Clock.systemUTC(),Duration.ofMinutes(2),5).withSectionEdits(edits).withSettings(settings).withCandidates(candidates).withAudioReferences(audioReferences).withRecoveryPolicy(Duration.ofMinutes(reviewMinutes),queryFailures).withConcurrencyLimit(maxInFlight);}
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name={"qingfeng.generation.enabled","qingfeng.generation.scheduler-enabled"},havingValue="true")
    GenerationDispatcher generationRunner(GenerationWorker worker,
            @Value("${qingfeng.generation.scan-ms:500}")long scanMillis,
            @Value("${qingfeng.generation.submit-workers:2}")int submitWorkers,
            @Value("${qingfeng.generation.query-workers:4}")int queryWorkers,
            @Value("${qingfeng.generation.archive-workers:2}")int archiveWorkers){
        return new GenerationDispatcher(worker::runOnce,Duration.ofMillis(scanMillis),submitWorkers,queryWorkers,archiveWorkers);
    }
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name={"qingfeng.generation.enabled","qingfeng.generation.vocal-adjustment-worker-enabled"},havingValue="true")
    GenerationDispatcher vocalAdjustmentRunner(GenerationWorker worker){
        return new GenerationDispatcher(worker::runVocalAdjustment,Duration.ofSeconds(2),1,1,1);
    }
    @Bean
    org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> generationRunnerStartup(
            org.springframework.beans.factory.ObjectProvider<GenerationDispatcher> dispatcher){
        // 等整个应用就绪后才允许付费提交；Bean创建或其他启动检查失败时不提前处理任务。
        return event->dispatcher.orderedStream().forEach(GenerationDispatcher::start);
    }
}
