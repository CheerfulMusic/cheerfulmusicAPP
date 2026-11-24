package com.qingfeng.music.server.config;

import com.qingfeng.music.infrastructure.persistence.ai.JdbcAiWorkQueue;
import com.qingfeng.music.server.ai.AiCapacity;
import com.qingfeng.music.server.ai.AiCapacityWorker;
import com.qingfeng.music.server.ai.AiTextQuota;
import com.qingfeng.music.server.ai.AiCapacityMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile({"mysql", "test"})
@ConditionalOnProperty(name = "qingfeng.ai.capacity.enabled", havingValue = "true")
public class AiCapacityConfiguration {
    @Bean AiCapacity aiCapacity(Environment environment) {
        return new AiCapacity(environment);
    }

    @Bean JdbcAiWorkQueue aiWorkQueue(JdbcTemplate db, PlatformTransactionManager manager) {
        return new JdbcAiWorkQueue(db, new TransactionTemplate(manager), Duration.ofSeconds(180));
    }

    @Bean AiTextQuota aiTextQuota(JdbcTemplate db, PlatformTransactionManager manager, Environment environment) {
        return new AiTextQuota(db, new TransactionTemplate(manager), environment);
    }

    @Bean(destroyMethod = "close") AiCapacityWorker aiCapacityWorker(JdbcAiWorkQueue queue, AiCapacity settings,
            List<AiCapacityWorker.Handler> handlers, MeterRegistry meters) {
        return new AiCapacityWorker(queue, settings, handlers, meters);
    }

    @Bean AiCapacityMetrics aiCapacityMetrics(JdbcAiWorkQueue queue, MeterRegistry meters) {
        return new AiCapacityMetrics(queue, meters);
    }

    @Bean ApplicationListener<ApplicationReadyEvent> aiCapacityWorkerStartup(AiCapacityWorker worker, JdbcAiWorkQueue queue) {
        return event -> {
            queue.requireSchema();
            worker.start();
        };
    }
}
