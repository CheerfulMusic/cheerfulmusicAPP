package com.qingfeng.music.server.config;

import com.qingfeng.music.server.analytics.AnalyticsDataErasureService;
import com.qingfeng.music.server.analytics.retention.AnalyticsRetentionService;
import com.qingfeng.music.server.analytics.retention.CleanupLock;
import com.qingfeng.music.server.analytics.retention.InMemoryCleanupLock;
import com.qingfeng.music.server.analytics.retention.MysqlNamedCleanupLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Arrays;

/**
 * 数据看板隐私治理的配置装配（方案 §2.A / §2.B）。
 *
 * <p><b>全部默认值都是「关闭 / 不配置」，生产值由运维按法务结论经环境变量注入</b>：
 * 本类不修改 application.yml，也不在代码中写入生产保留天数或删除方式。
 *
 * <p>调度 Runner 默认<b>不注册</b>（{@code scheduler-enabled} 缺省 false），
 * 因此即使应用已全局 {@code @EnableScheduling}，也不会有清理任务自动运行。
 */
@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "test"})
public class AnalyticsPrivacyConfiguration {

    private static final String PREFIX = "qingfeng.analytics.";

    @Bean
    CleanupLock analyticsCleanupLock(JdbcTemplate jdbc, Environment env) {
        // H2 / 测试环境无 GET_LOCK；生产 MySQL 用命名锁，测试用可控内存锁。
        boolean test = Arrays.asList(env.getActiveProfiles()).contains("test");
        return test ? new InMemoryCleanupLock()
                : new MysqlNamedCleanupLock(jdbc, "site_event_cleanup", 0);
    }

    @Bean
    AnalyticsRetentionService analyticsRetentionService(JdbcTemplate jdbc, CleanupLock lock, Environment env) {
        return new AnalyticsRetentionService(jdbc, lock,
                env.getProperty(PREFIX + "retention.enabled", Boolean.class, false),
                env.getProperty(PREFIX + "retention.retention-days", Integer.class, 0),
                env.getProperty(PREFIX + "retention.allow-short", Boolean.class, false),
                env.getProperty(PREFIX + "retention.batch-size", Integer.class, 1000),
                env.getProperty(PREFIX + "retention.max-rows", Long.class, 50_000L),
                env.getProperty(PREFIX + "retention.dry-run", Boolean.class, false));
    }

    @Bean
    AnalyticsDataErasureService analyticsDataErasureService(JdbcTemplate jdbc, Environment env) {
        return new AnalyticsDataErasureService(jdbc,
                env.getProperty(PREFIX + "erasure.enabled", Boolean.class, false),
                env.getProperty(PREFIX + "erasure.method", String.class, ""),
                env.getProperty(PREFIX + "erasure.batch-size", Integer.class, 1000));
    }

    /**
     * 仅在运维显式设置 {@code qingfeng.analytics.retention.scheduler-enabled=true} 时注册。
     * 服务本身还有独立开关与天数 fail-closed，双保险。
     */
    @Bean
    @ConditionalOnProperty(prefix = "qingfeng.analytics.retention", name = "scheduler-enabled", havingValue = "true")
    RetentionRunner analyticsRetentionRunner(AnalyticsRetentionService service) {
        return new RetentionRunner(service);
    }

    static class RetentionRunner {
        private static final Logger LOG = LoggerFactory.getLogger(RetentionRunner.class);
        private final AnalyticsRetentionService service;

        RetentionRunner(AnalyticsRetentionService service) { this.service = service; }

        @Scheduled(fixedDelayString = "${qingfeng.analytics.retention.scan-ms:86400000}")
        void run() {
            var result = service.runOnce(Instant.now());
            // no-op / 配置缺失属预期状态，不刷错误日志；仅 SUCCESS / FAILED 提示。
            if (result.status() == AnalyticsRetentionService.Status.FAILED) {
                LOG.warn("analytics.retention scheduled run failed: {}", result.reason());
            }
        }
    }
}
