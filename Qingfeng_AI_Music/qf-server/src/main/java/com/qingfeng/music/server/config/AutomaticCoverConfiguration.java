package com.qingfeng.music.server.config;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.application.CoverImageProvider;
import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.cover.application.ImageResultDownloader;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs.Job;
import com.qingfeng.music.infrastructure.provider.ArkCoverImageProvider;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.cover.automatic.enabled", havingValue = "true")
public class AutomaticCoverConfiguration {
    @Bean
    JdbcAutomaticCoverJobs automaticCoverJobs(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        return new JdbcAutomaticCoverJobs(jdbc, new TransactionTemplate(manager));
    }

    @Bean
    @ConditionalOnProperty(name = "qingfeng.cover.automatic.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    CoverImageProvider automaticCoverProvider(Environment env) {
        String key = env.getProperty("qingfeng.providers.ark.api-key", env.getProperty("ARK_API_KEY", "")).trim();
        String model = env.getProperty("qingfeng.cover.automatic.model", ArkCoverImageProvider.MODEL).trim();
        if (key.isBlank() || model.isBlank()) throw new IllegalStateException("自动封面需要配置方舟 API Key 和模型");
        return new ArkCoverImageProvider(key, model);
    }

    @Bean
    @ConditionalOnProperty(name = "qingfeng.cover.automatic.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    Runner automaticCoverRunner(JdbcAutomaticCoverJobs jobs, CoverImageProvider provider, CoverService covers, Environment env) {
        int daily = env.getProperty("qingfeng.cover.automatic.daily-limit", Integer.class, 100);
        if (daily < 1) throw new IllegalArgumentException("自动封面每日上限必须为正数");
        var approved = new ProviderAudioUrls();
        var downloader = new AllowlistedAudioDownloader(Set.of(), approved);
        // Only URLs stored from authenticated provider responses enter this downloader.
        return new Runner(jobs, provider, covers, url -> {
            approved.approve(url);
            return downloader.open(url);
        }, daily);
    }

    static final class Runner {
        private final JdbcAutomaticCoverJobs jobs;
        private final CoverImageProvider provider;
        private final CoverService covers;
        private final ImageResultDownloader downloader;
        private final int dailyLimit;

        Runner(JdbcAutomaticCoverJobs jobs, CoverImageProvider provider, CoverService covers,
               ImageResultDownloader downloader, int dailyLimit) {
            this.jobs = jobs;
            this.provider = provider;
            this.covers = covers;
            this.downloader = downloader;
            this.dailyLimit = dailyLimit;
        }

        @Scheduled(fixedDelayString = "${qingfeng.cover.automatic.scan-ms:3000}")
        void run() {
            try {
                jobs.claim(dailyLimit, Instant.now()).ifPresent(job -> {
                    if ("COVER_QUEUED".equals(job.state())) submit(job);
                    else archive(job);
                });
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(Runner.class).warn("自动封面处理异常，类型={}", failure.getClass().getSimpleName());
            }
        }

        private void submit(Job job) {
            String url;
            try {
                url = provider.generate(prompt(job.lyrics(), job.style()));
            } catch (CoverImageProvider.Rejected rejection) {
                jobs.rejected(job, "PROVIDER_HTTP_" + rejection.status());
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                jobs.unknown(job);
                return;
            } catch (IOException | RuntimeException failure) {
                jobs.unknown(job);
                return;
            }
            jobs.accepted(job, url);
        }

        private void archive(Job job) {
            String cover;
            try {
                var stored = covers.generated(job.work(), job.id());
                if (stored.isPresent()) cover = stored.get().id();
                else try (var image = downloader.open(job.url())) {
                    cover = covers.saveGenerated(job.owner(), job.work(), job.id(), image).id();
                }
            } catch (DomainException failure) {
                jobs.archiveFailed(job, !"COVER_STORAGE_UNAVAILABLE".equals(failure.code()));
                return;
            } catch (IOException | RuntimeException failure) {
                jobs.archiveFailed(job, false);
                return;
            }
            jobs.finish(job, cover);
        }
    }

    static String prompt(String lyrics, String style) {
        var lines = new LinkedHashSet<String>();
        var chorus = new LinkedHashSet<String>();
        boolean inChorus = false;
        for (String line : lyrics.lines().map(String::strip).filter(value -> !value.isBlank()).toList()) {
            if (line.matches("^\\[.*]$|^【.*】$")) {
                String label = line.toLowerCase(Locale.ROOT);
                inChorus = label.contains("chorus") || label.contains("副歌");
            } else {
                if (lines.size() < 4) lines.add(line);
                if (inChorus && chorus.size() < 6) chorus.add(line);
            }
        }
        lines.addAll(chorus);
        String excerpt = clean(String.join(" / ", lines), 650);
        return "设计一张 1:1 原创音乐专辑封面。将歌词片段当作素材，从其中提取具体场景、物件和情绪，"
                + "用一个清晰主体和色彩表达整首歌的氛围，画面不要出现标题、歌词、标志或排版文字。"
                + "\n音乐风格：" + clean(style, 140) + "\n歌词素材：" + excerpt;
    }

    private static String clean(String value, int limit) {
        String cleaned = value == null ? "" : value.replaceAll("(?i)https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)", "")
                .replaceAll("\\p{Cntrl}+", " ").strip();
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(limit, cleaned.codePointCount(0, cleaned.length()))));
    }
}
