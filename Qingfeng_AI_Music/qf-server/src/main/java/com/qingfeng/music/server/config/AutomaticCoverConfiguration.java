package com.qingfeng.music.server.config;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.application.CoverImageProvider;
import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.cover.application.ImageResultDownloader;
import com.qingfeng.music.cover.application.CoverTitleVerifier;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs.Job;
import com.qingfeng.music.infrastructure.provider.ArkCoverImageProvider;
import com.qingfeng.music.infrastructure.provider.ArkCoverTitleVerifier;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
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
    CoverTitleVerifier automaticCoverTitleVerifier(Environment env) {
        String key=env.getProperty("qingfeng.providers.ark.api-key",env.getProperty("ARK_API_KEY","")).trim();
        if(key.isBlank()) throw new IllegalStateException("AI艺术字封面需要配置视觉文字核对服务");
        return new ArkCoverTitleVerifier(key,env.getProperty("qingfeng.cover.automatic.verifier-model",ArkCoverTitleVerifier.DEFAULT_MODEL));
    }

    @Bean
    @ConditionalOnProperty(name = "qingfeng.cover.automatic.scheduler-enabled", havingValue = "true", matchIfMissing = true)
    Runner automaticCoverRunner(JdbcAutomaticCoverJobs jobs, CoverImageProvider provider, CoverService covers,
                                CoverTitleVerifier verifier, Environment env) {
        int daily = env.getProperty("qingfeng.cover.automatic.daily-limit", Integer.class, 100);
        if (daily < 1) throw new IllegalArgumentException("自动封面每日上限必须为正数");
        var approved = new ProviderAudioUrls();
        var downloader = new AllowlistedAudioDownloader(Set.of(), approved);
        // Only URLs stored from authenticated provider responses enter this downloader.
        return new Runner(jobs, provider, covers, url -> {
            approved.approve(url);
            return downloader.open(url);
        }, daily, verifier);
    }

    static final class Runner {
        private final JdbcAutomaticCoverJobs jobs;
        private final CoverImageProvider provider;
        private final CoverService covers;
        private final ImageResultDownloader downloader;
        private final int dailyLimit;
        private final CoverTitleVerifier verifier;
        private final AutomaticCoverPrompt prompts = new AutomaticCoverPrompt();

        Runner(JdbcAutomaticCoverJobs jobs, CoverImageProvider provider, CoverService covers,
               ImageResultDownloader downloader, int dailyLimit, CoverTitleVerifier verifier) {
            this.jobs = jobs;
            this.provider = provider;
            this.covers = covers;
            this.downloader = downloader;
            this.dailyLimit = dailyLimit;
            this.verifier = verifier;
        }

        @Scheduled(fixedDelayString = "${qingfeng.cover.automatic.scan-ms:3000}")
        void run() {
            try {
                jobs.claim(dailyLimit, Instant.now()).ifPresent(job -> {
                    if ("COVER_QUEUED".equals(job.state())) submit(job);
                    else if ("COVER_REPAIR".equals(job.state())) repair(job);
                    else archive(job);
                });
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(Runner.class).warn("自动封面处理异常，类型={}", failure.getClass().getSimpleName());
            }
        }

        private void submit(Job job) {
            String url;
            try {
                url = provider.generate(prompts.create(job.id(), job.title(), job.lyrics(), job.story(), job.style()));
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
            byte[] image = null;
            boolean artwork = JdbcAutomaticCoverJobs.ART_SAVING.equals(job.state());
            try {
                String candidate = candidateKey(job);
                var stored = covers.generated(job.work(), candidate);
                if (stored.isEmpty()) {
                    byte[] downloaded = download(job.url());
                    covers.saveGenerated(job.owner(), job.work(), candidate, new ByteArrayInputStream(downloaded));
                    stored = covers.generated(job.work(), candidate);
                }
                var asset = stored.orElseThrow(() -> new IOException("Candidate cover not stored"));
                cover = asset.id();
                // Only old title-bearing results still need a visual title check.
                if (!artwork) {
                    try (var input = covers.open(job.owner(), job.work(), cover).source().open()) {
                        image = input.readNBytes(5 * 1024 * 1024 + 1);
                    }
                    if (image.length > 5 * 1024 * 1024) {
                        throw new DomainException("INVALID_COVER_IMAGE", "封面图片超过大小限制");
                    }
                }
            } catch (DomainException failure) {
                jobs.archiveFailed(job, Set.of("INVALID_COVER_IMAGE","INVALID_COVER_TITLE","COVER_FONT_UNAVAILABLE").contains(failure.code()));
                return;
            } catch (IOException | RuntimeException failure) {
                jobs.archiveFailed(job, false);
                return;
            }
            if (artwork) {
                jobs.finish(job, cover);
                return;
            }
            try {
                if (!verifier.matches(image,job.title())) {
                    if ("COVER_SAVING".equals(job.state())) jobs.repair(job);
                    else jobs.titleFailed(job);
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();jobs.verificationFailed(job);return;
            } catch (IOException | RuntimeException failure) {
                jobs.verificationFailed(job);return;
            }
            jobs.finish(job, cover);
        }

        private String candidateKey(Job job) {
            return "COVER_RESAVE".equals(job.state())
                    ? java.util.UUID.nameUUIDFromBytes((job.id()+":repair").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()
                    : job.id();
        }

        private byte[] download(String url) throws IOException {
            try (var input=downloader.open(url)) {
                var bytes=input.readNBytes(5*1024*1024+1);
                if (bytes.length>5*1024*1024) throw new DomainException("INVALID_COVER_IMAGE","封面图片超过大小限制");
                return bytes;
            }
        }

        private void repair(Job job) {
            String url;
            try {
                byte[] image;
                var original=covers.generated(job.work(),job.id());
                if(original.isPresent()) {
                    try(var input=covers.open(job.owner(),job.work(),original.get().id()).source().open()) {
                        image=input.readNBytes(5*1024*1024+1);
                    }
                } else image=download(job.url()); // Compatibility with jobs created before candidates were persisted.
                String title=new tools.jackson.databind.ObjectMapper().writeValueAsString(job.title());
                url=provider.edit(image,"精确修正这张唱片封面的主标题，歌名完整准确为"+title+"。"
                        +"保留原有艺术字风格、颜色与清晰的阅读顺序，删除主标题中多余的文字、符号和分隔斜线。"
                        +"可以自然换行，保持人物、场景、色调与构图协调，标题完整、醒目并保留安全边距。");
            } catch (CoverImageProvider.Rejected rejected) {
                jobs.repairRejected(job);return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();jobs.unknown(job);return;
            } catch (IOException | RuntimeException failure) {
                jobs.unknown(job);return;
            }
            jobs.repaired(job,url);
        }
    }

}
