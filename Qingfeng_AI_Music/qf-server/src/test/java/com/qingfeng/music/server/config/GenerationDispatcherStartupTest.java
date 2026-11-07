package com.qingfeng.music.server.config;

import com.qingfeng.music.generation.application.GenerationDispatcher;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import static org.junit.jupiter.api.Assertions.*;

class GenerationDispatcherStartupTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class StartupOnly {
        @Bean ApplicationListener<ApplicationReadyEvent> starter(ObjectProvider<GenerationDispatcher> dispatcher) {
            return new ExecutionConfiguration().generationRunnerStartup(dispatcher);
        }
    }

    @Test void beanCreationDoesNotDispatchBeforeTheApplicationIsReady() throws Exception {
        var entered = new CountDownLatch(3);
        try (var dispatcher = new GenerationDispatcher(phase -> entered.countDown(), Duration.ofSeconds(30), 1, 1, 1);
             var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GenerationDispatcher.class, () -> dispatcher);
            context.register(StartupOnly.class);
            context.refresh();
            assertEquals(3, entered.getCount());
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(StartupOnly.class), new String[0], context, Duration.ZERO));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void disabledSchedulerDoesNotRequireADispatcherBean() {
        try (var context = new AnnotationConfigApplicationContext(StartupOnly.class)) {
            assertDoesNotThrow(() -> context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(StartupOnly.class), new String[0], context, Duration.ZERO)));
        }
    }
}
