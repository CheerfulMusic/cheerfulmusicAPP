package com.qingfeng.music.server.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class RegionalReadConfigurationTest {
    @TempDir Path directory;

    @Test
    void localHotUpdatesDoNotRunPrimaryBackgroundSchedules() {
        try (var local = new AnnotationConfigApplicationContext()) {
            local.getEnvironment().setActiveProfiles("mysql");
            local.getEnvironment().getPropertySources().addFirst(
                    new MockPropertySource().withProperty("qingfeng.scheduling.enabled", "false"));
            local.register(PrimarySchedulingConfiguration.class);
            local.refresh();
            assertTrue(local.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        }
    }

    @Test
    void proofExpiresAndRejectsFutureTimestamps() throws Exception {
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        Path proof = directory.resolve("ready");
        assertFalse(RegionalReadConfiguration.freshProof(proof, now));
        Files.createFile(proof);
        Files.setLastModifiedTime(proof, FileTime.from(now.minusSeconds(2)));
        assertTrue(RegionalReadConfiguration.freshProof(proof, now));
        assertFalse(RegionalReadConfiguration.freshProof(proof, now.plusSeconds(9)));
        assertFalse(RegionalReadConfiguration.freshProof(proof, now.minusSeconds(3)));
    }

    @Test
    void sharingRequiresRegionalOptInPrimaryApprovalAndFreshReplica() throws Exception {
        Path proof = directory.resolve("ready");
        String path = "/gift/" + "A".repeat(43);
        assertEquals(404, response(true, proof, path, false));
        assertEquals(503, response(true, proof, path, true));
        assertEquals(404, response(false, proof, "/api/public/discover", false));
        Files.createFile(proof);
        Files.setLastModifiedTime(proof, FileTime.from(Instant.now().minusSeconds(1)));
        assertEquals(404, response(false, proof, path, true));
        assertEquals(200, response(true, proof, path, true));
        assertEquals(204, response(true, proof, "/internal/regional/ready", false));
    }

    private static int response(boolean gift, Path proof, String path, boolean approved) throws Exception {
        var filter = new RegionalReadConfiguration().regionalReadRoutes(proof.toString(), gift).getFilter();
        var request = new MockHttpServletRequest("GET", path);
        if (approved) request.addHeader("X-QF-Primary-Share-Authorized", "active");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void acceptsOnlyExplicitReadRoutes() {
        assertFalse(RegionalReadConfiguration.allows("GET", "/api/public/discover"));
        assertTrue(RegionalReadConfiguration.allows("HEAD", "/assets/site.js"));
        assertTrue(RegionalReadConfiguration.allows("GET", "/favicon.ico"));
        assertTrue(RegionalReadConfiguration.allows("HEAD", "/favicon.ico"));
        assertFalse(RegionalReadConfiguration.allows("POST", "/favicon.ico"));
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
