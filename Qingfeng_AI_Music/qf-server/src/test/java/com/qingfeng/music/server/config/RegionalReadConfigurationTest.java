package com.qingfeng.music.server.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class RegionalReadConfigurationTest {
    @Test
    void acceptsOnlyExplicitReadRoutes() {
        assertTrue(RegionalReadConfiguration.allows("GET", "/api/public/discover"));
        assertTrue(RegionalReadConfiguration.allows("HEAD", "/assets/site.js"));
        assertFalse(RegionalReadConfiguration.allows("POST", "/api/public/discover"));
        assertFalse(RegionalReadConfiguration.allows("GET", "/api/app/credits"));
        assertFalse(RegionalReadConfiguration.allows("GET", "/api/public/gifts/an-opaque-token"));
        assertFalse(RegionalReadConfiguration.allows("GET", "/assets/../api/app/credits"));
        assertFalse(RegionalReadConfiguration.allows("GET", "/assets/%2e%2e/api/app/credits"));
    }

    @Test
    void backgroundSchedulingRemainsOnForPrimaryAndOffForRegionalReader() {
        try (var primary = new AnnotationConfigApplicationContext();
             var regional = new AnnotationConfigApplicationContext()) {
            primary.getEnvironment().setActiveProfiles("mysql");
            primary.register(PrimarySchedulingConfiguration.class);
            primary.refresh();
            assertFalse(primary.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
            regional.getEnvironment().setActiveProfiles("mysql", "sg-reader");
            regional.register(PrimarySchedulingConfiguration.class);
            regional.refresh();
            assertTrue(regional.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        }
    }
}
