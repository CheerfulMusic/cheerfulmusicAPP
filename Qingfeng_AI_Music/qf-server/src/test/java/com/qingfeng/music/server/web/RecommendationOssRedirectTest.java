package com.qingfeng.music.server.web;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import java.net.URI;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationOssRedirectTest {
    private static final String FILE = "/media/sample-red-guofeng-v1.mp3";
    private static final String KEY = RecommendationOssRedirect.PREFIX + "sample-red-guofeng-v1.mp3";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    @Test void signsOnlyAllowlistedGetAndHeadAndPreservesRangeRedirect() throws Exception {
        OSS client = mock(OSS.class);
        when(client.doesObjectExist("private-bucket", KEY)).thenReturn(true);
        when(client.generatePresignedUrl(eq("private-bucket"), eq(KEY), any(Date.class), any(HttpMethod.class)))
            .thenReturn(new URL("https://example.invalid/sample?x-oss-signature=redacted"));
        var subject = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        var filter = new RecommendationOssFilter(subject);
        for (String method : new String[]{"GET", "HEAD"}) {
            var req = new MockHttpServletRequest(method, FILE); req.setServletPath(FILE); req.addHeader("Range", "bytes=100-199");
            var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
            filter.doFilter(req, response, chain);
            assertEquals(302, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertTrue(response.getHeader("Location").startsWith("https://"));
            assertNull(chain.getRequest());
        }
        verify(client).generatePresignedUrl(eq("private-bucket"), eq(KEY), eq(Date.from(CLOCK.instant().plusSeconds(3600))), eq(HttpMethod.GET));
        verify(client).generatePresignedUrl(eq("private-bucket"), eq(KEY), any(Date.class), eq(HttpMethod.HEAD));
        assertTrue(subject.destination("/media/../../private.mp3", "GET", false).isEmpty());
        assertTrue(subject.destination(FILE, "POST", false).isEmpty());
        assertTrue(subject.destination(FILE, "GET", true).isEmpty());
    }

    @Test void disabledMissingAndOssErrorFallBackToStaticChain() throws Exception {
        OSS client = mock(OSS.class);
        var disabled = new RecommendationOssRedirect(client, "private-bucket", false, CLOCK);
        assertTrue(disabled.destination(FILE,"GET",false).isEmpty());
        var enabled = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        assertTrue(enabled.destination(FILE,"GET",false).isEmpty());
        when(client.doesObjectExist("private-bucket",KEY)).thenThrow(new IllegalStateException());
        var req = new MockHttpServletRequest("GET",FILE); req.setServletPath(FILE);
        var res = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new RecommendationOssFilter(enabled).doFilter(req,res,chain);
        assertSame(req,chain.getRequest());
        assertNull(res.getHeader("Location"));
    }
}
