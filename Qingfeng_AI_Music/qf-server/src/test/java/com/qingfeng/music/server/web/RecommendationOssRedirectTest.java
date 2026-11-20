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
    @Test void restoredSamplesUseTheirLegacyAudioAndCheckedCoverObjects() {
        var cdn = new MediaCdnSigner(true, "https://cdn.example.com", "test-key", "", 600, "0", "qingfeng/audio/", "qingfeng/covers/");
        var redirect = new RecommendationOssRedirect(null, "", false, Clock.systemUTC(), cdn);
        var audio = redirect.destination("/media/sample-love-v2-preview-128.mp3", "GET", true).orElseThrow();
        assertEquals("/" + RecommendationOssRedirect.PREVIEW_PREFIX + "sample-love-v2-preview-128.mp3", audio.getPath());
        for (String cover : new String[]{"explore-love-v1.webp", "explore-guofeng-v1.svg", "explore-disco-v1.jpg"}) {
            var destination = redirect.destination("/media/" + cover, "GET", false).orElseThrow();
            assertEquals("cdn.example.com", destination.getHost());
            assertTrue(destination.getPath().matches("/qingfeng/recommendation-samples/20261119/cover/[a-f0-9]{12}/" + java.util.regex.Pattern.quote(cover)));
            assertTrue(destination.getQuery().startsWith("auth_key="));
        }
        assertTrue(redirect.destination("/media/explore-unknown-v1.jpg", "GET", false).isEmpty());
    }

    @Test void newHashNamedSamplesUseTheSameSignedCdnAndRejectUnknownOrChangedHashes() {
        var cdn=new MediaCdnSigner(true,"https://cdn.example.com","test-key","",600,"0","qingfeng/audio/","qingfeng/covers/");
        var redirect=new RecommendationOssRedirect(null,"",false,Clock.systemUTC(),cdn);
        for(var file:java.util.List.of("recommendations/body-language-8e59749c37e9-preview-128.mp3","recommendations/body-language-c251e80045fe-cover.jpg")){
            var destination=redirect.destination("/media/"+file,"GET",false).orElseThrow();
            assertEquals("cdn.example.com",destination.getHost());
            assertTrue(destination.getPath().startsWith("/qingfeng/recommendation-samples/20261119/"));
            assertTrue(destination.getQuery().startsWith("auth_key="));
            assertTrue(redirect.destination("/media/"+file,"HEAD",false).isPresent());
            assertNotEquals(destination.getQuery(),redirect.destination("/media/"+file,"GET",true).orElseThrow().getQuery());
            assertTrue(redirect.destination("/media/"+file,"POST",false).isEmpty());
        }
        assertTrue(redirect.destination("/media/recommendations/body-language-000000000000-preview-128.mp3","GET",false).isEmpty());
        assertTrue(redirect.destination("/media/recommendations/unknown-8e59749c37e9-preview-128.mp3","GET",false).isEmpty());
        assertTrue(redirect.destination("/media/recommendations/../private.mp3","GET",false).isEmpty());
    }
    @Test void curatedAudioAndCoversUseCdnWithoutAnOssProbeAndRenewExpiredUrls() {
        var cdn = new MediaCdnSigner(true, "https://cdn.example.com", "test-key", "", 600, "0", "qingfeng/audio/", "qingfeng/covers/");
        var redirect = new RecommendationOssRedirect(null, "", false, Clock.systemUTC(), cdn);
        for (String file : new String[]{"sample-feng-ye-xing-v1-preview-128.mp3", "sample-feng-ye-xing-v1-cover.jpg"}) {
            String object = com.qingfeng.music.server.hotsong.RecommendationSamples.objectKey(file).orElseThrow();
            assertTrue(RecommendationOssRedirect.isSamplePath("/media/" + file));
            var initial = redirect.destination("/media/" + file, "GET", false).orElseThrow();
            var renewed = redirect.destination("/media/" + file, "GET", true).orElseThrow();
            assertEquals("cdn.example.com", initial.getHost());
            assertEquals("/" + object, initial.getPath());
            assertNotEquals(initial.getQuery(), renewed.getQuery());
            assertTrue(redirect.destination("/media/" + file, "HEAD", false).isPresent());
            assertTrue(redirect.destination("/media/" + file, "POST", false).isEmpty());
        }
        assertTrue(cdn.recommendation("../private.mp3").isEmpty());
        assertTrue(cdn.recommendation("sample-unknown-v1-preview-128.mp3").isEmpty());
    }

    @Test void vocalRecordingAndArtworkHaveTheirOwnCdnObjects() {
        var cdn = new MediaCdnSigner(true, "https://cdn.example.com", "test-key", "", 600, "0", "", "");
        var redirect = new RecommendationOssRedirect(null, "", false, Clock.systemUTC(), cdn);
        var audio = redirect.destination("/media/recommendations/vocal-liu-lang-shi-ren-58376af9823c-preview-128.mp3", "GET", false).orElseThrow();
        var artwork = redirect.destination("/media/recommendations/vocal-liu-lang-shi-ren-e187026fea88-cover.jpg", "GET", false).orElseThrow();
        assertEquals("/qingfeng/recommendation-samples/20261120/audio/58376af9823c/vocal-liu-lang-shi-ren-58376af9823c-preview-128.mp3", audio.getPath());
        assertEquals("/qingfeng/recommendation-samples/20261120/cover/e187026fea88/vocal-liu-lang-shi-ren-e187026fea88-cover.jpg", artwork.getPath());
        assertTrue(redirect.destination("/media/recommendations/vocal-liu-lang-shi-ren-58376af9823c-original.wav", "GET", false).isEmpty());
    }

    private static final String FILE = "/media/sample-red-guofeng-v1.mp3";
    private static final String KEY = RecommendationOssRedirect.PREFIX + "sample-red-guofeng-v1.mp3";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

    @Test void signsOnlyAllowlistedGetAndHeadAndPreservesRangeRedirect() throws Exception {
        OSS client = mock(OSS.class);
        when(client.doesObjectExist("private-bucket", KEY)).thenReturn(true);
        when(client.generatePresignedUrl(eq("private-bucket"), eq(KEY), any(Date.class), any(HttpMethod.class)))
            .thenReturn(new URL("https://example.oss-cn-hongkong.aliyuncs.com/sample?x-oss-signature=redacted"));
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

    @Test void previewAndOriginalAreBothAllowlistedAndMissingPreviewFallsBack() {
        String preview = "/media/sample-red-guofeng-v1-preview-128.mp3";
        assertTrue(RecommendationOssRedirect.isSamplePath(FILE));
        assertTrue(RecommendationOssRedirect.isSamplePath(preview));
        assertFalse(RecommendationOssRedirect.isSamplePath("/media/sample-red-guofeng-v1-preview-129.mp3"));
        OSS client = mock(OSS.class);
        var subject = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        assertTrue(subject.destination(preview, "GET", false).isEmpty());
        verify(client).doesObjectExist("private-bucket", RecommendationOssRedirect.PREVIEW_PREFIX + "sample-red-guofeng-v1-preview-128.mp3");
    }

    @Test void internalEndpointCannotBeGivenToBrowsers() throws Exception {
        OSS client = mock(OSS.class);
        when(client.doesObjectExist("private-bucket", KEY)).thenReturn(true);
        when(client.generatePresignedUrl(eq("private-bucket"), eq(KEY), any(Date.class), any(HttpMethod.class)))
            .thenReturn(new URL("https://example.oss-cn-hongkong-internal.aliyuncs.com/sample"));
        assertTrue(new RecommendationOssRedirect(client, "private-bucket", true, CLOCK).destination(FILE, "GET", false).isEmpty());
    }

    @Test void aSlowOssCheckFallsBackWithinOneSecondAndCachesFailure() throws Exception {
        OSS client = mock(OSS.class);
        when(client.doesObjectExist("private-bucket", KEY)).thenAnswer(invocation -> { Thread.sleep(5000); return true; });
        var service = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        long started = System.nanoTime();
        assertTrue(service.destination(FILE, "GET", false).isEmpty());
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
        assertTrue(service.destination(FILE, "GET", false).isEmpty());
        verify(client, times(1)).doesObjectExist("private-bucket", KEY);
    }

    @Test void disabledMissingAndOssErrorFallBackToStaticChain() throws Exception {
        OSS client = mock(OSS.class);
        var disabled = new RecommendationOssRedirect(client, "private-bucket", false, CLOCK);
        assertTrue(disabled.destination(FILE,"GET",false).isEmpty());
        var enabled = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        assertTrue(enabled.destination(FILE,"GET",false).isEmpty());
        when(client.doesObjectExist("private-bucket",KEY)).thenThrow(new IllegalStateException());
        enabled = new RecommendationOssRedirect(client, "private-bucket", true, CLOCK);
        var req = new MockHttpServletRequest("GET",FILE); req.setServletPath(FILE);
        var res = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new RecommendationOssFilter(enabled).doFilter(req,res,chain);
        assertSame(req,chain.getRequest());
        assertNull(res.getHeader("Location"));
    }
}
