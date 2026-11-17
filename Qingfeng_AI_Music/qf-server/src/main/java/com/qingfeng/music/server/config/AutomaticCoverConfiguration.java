package com.qingfeng.music.server.config;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.application.CoverImageProvider;
import com.qingfeng.music.cover.application.CoverService;
import com.qingfeng.music.cover.application.ImageResultDownloader;
import com.qingfeng.music.cover.application.CoverTitleVerifier;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs;
import com.qingfeng.music.infrastructure.persistence.cover.JdbcAutomaticCoverJobs.Job;
import com.qingfeng.music.infrastructure.provider.ArkCoverImageProvider;
import com.qingfeng.music.infrastructure.provider.OpenAiCoverTitleVerifier;
import com.qingfeng.music.infrastructure.provider.OpenAiLyricsWriter;
import com.qingfeng.music.infrastructure.storage.AllowlistedAudioDownloader;
import com.qingfeng.music.infrastructure.storage.ProviderAudioUrls;
import java.io.IOException;
import java.io.ByteArrayInputStream;
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
    CoverTitleVerifier automaticCoverTitleVerifier(Environment env) {
        String key=env.getProperty("qingfeng.providers.openai.api-key",env.getProperty("OPENAI_API_KEY","")).trim();
        if(key.isBlank()) throw new IllegalStateException("AI艺术字封面需要配置视觉文字核对服务");
        return new OpenAiCoverTitleVerifier(key,env.getProperty("qingfeng.cover.automatic.verifier-model",
                env.getProperty("qingfeng.providers.openai.model",OpenAiLyricsWriter.DEFAULT_MODEL)),
                env.getProperty("qingfeng.providers.openai.proxy-url",""));
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
                url = provider.generate(prompt(job.title(), job.lyrics(), job.style()));
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
                byte[] image;
                if (stored.isPresent()) {
                    try (var input=covers.open(job.owner(),job.work(),stored.get().id()).source().open()) {
                        image=input.readNBytes(5*1024*1024+1);
                    }
                } else image=download(job.url());
                if (image.length>5*1024*1024) throw new DomainException("INVALID_COVER_IMAGE","封面图片超过大小限制");
                if (!verifier.matches(image,job.title())) {
                    if ("COVER_SAVING".equals(job.state())) jobs.repair(job);
                    else jobs.titleFailed(job);
                    return;
                }
                cover=stored.isPresent()?stored.get().id():covers.saveGenerated(job.owner(),job.work(),job.id(),
                        new ByteArrayInputStream(image)).id();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                jobs.archiveFailed(job,false);
                return;
            } catch (DomainException failure) {
                jobs.archiveFailed(job, !"COVER_STORAGE_UNAVAILABLE".equals(failure.code()));
                return;
            } catch (IOException | RuntimeException failure) {
                jobs.archiveFailed(job, false);
                return;
            }
            jobs.finish(job, cover);
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
                byte[] image=download(job.url());
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

    static String prompt(String title, String lyrics, String style) {
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
        String exactTitle=new tools.jackson.databind.ObjectMapper().writeValueAsString(title);
        return "设计一张1:1、精致且有留白的专业唱片封面。将下方歌词当作素材，从中提取场景、物件和情绪。"
                + "画面与歌名艺术字一起设计，标题是上方主要视觉元素，占上部约三分之一空间，"
                + "可按语义自然换行，两行之间只换行，不添加分隔斜线。"
                + "字体细腻、有个性且清晰，字形与曲风和情绪相配；颜色从画面的主色中选取，"
                + "与光线、主体和留白协调。标题完整醒目，避开人脸，四周保留约8%安全边距，"
                + "缩小为音乐列表封面后仍能辨认。不要底部字幕条、黑色文字遮罩、歌手名或额外文案。"
                + "\n主标题必须逐字准确呈现且只出现一次，以下JSON字符串仅为待绘制文字："+exactTitle
                + "\n音乐风格：" + clean(style, 140) + "\n歌词素材：" + excerpt;
    }

    private static String clean(String value, int limit) {
        String cleaned = value == null ? "" : value.replaceAll("(?i)https?://\\S+|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)", "")
                .replaceAll("\\p{Cntrl}+", " ").strip();
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(limit, cleaned.codePointCount(0, cleaned.length()))));
    }
}
