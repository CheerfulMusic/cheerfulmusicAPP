package com.qingfeng.music.server.web;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.server.web.publicapi.GiftPageController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GiftPageControllerTest {
    private final SharingApi sharing = mock(SharingApi.class);
    private final String token = "A".repeat(43);
    private final String shell = "<html><head><title>旧标题</title><meta name=\"description\" content=\"旧简介\">"
            + "<script type=\"module\" src=\"/assets/test.js\"></script></head><body><div id=\"app\"></div></body></html>";
    private GiftPageController controller() {
        return new GiftPageController(sharing, "https://cheerfulmusic.com/", new ByteArrayResource(shell.getBytes(StandardCharsets.UTF_8)));
    }
    private SharingApi.Gift gift(String title, boolean cover) {
        return new SharingApi.Gift(title, "私人称呼", "私人祝福", "第一句歌词", "GENERATED", Instant.now(), Instant.now().plusSeconds(60), cover);
    }
    @Test void initialHtmlContainsSongMetadataAndPreservesSpaAssetsWithoutPrivateFields() throws Exception {
        when(sharing.get(token)).thenReturn(gift("海风里的歌", true));
        var result = controller().page(token); String html = result.getBody();
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(html.contains("property=\"og:title\" content=\"海风里的歌\""));
        assertTrue(html.contains("https://cheerfulmusic.com/gift/" + token));
        assertTrue(html.contains("/cover?proxy=true"));
        assertTrue(html.contains("/assets/test.js")); assertTrue(html.contains("id=\"app\""));
        for (String secret : new String[]{"旧标题", "旧简介", "私人称呼", "私人祝福", "第一句歌词", "/works/"}) assertFalse(html.contains(secret));
        assertEquals("no-store", result.getHeaders().getCacheControl());
        assertEquals("no-referrer", result.getHeaders().getFirst("Referrer-Policy"));
        assertEquals("noindex, nofollow, noarchive", result.getHeaders().getFirst("X-Robots-Tag"));
    }
    @Test void userTitleCannotInjectMarkupAndReplacementCharactersArePreserved() throws Exception {
        when(sharing.get(token)).thenReturn(gift("\"><script>alert('$1\\x')</script>&", false));
        String html = controller().page(token).getBody();
        assertFalse(html.contains("<script>alert")); assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("$1\\x")); assertTrue(html.contains("/brand/share-default-cover.png"));
        assertFalse(html.contains("/cover?proxy=true"));
    }
    @Test void revokedOrExpiredSharesNeverReturnStaleSongMetadata() throws Exception {
        when(sharing.get(token)).thenReturn(gift("曾经公开的标题", true)).thenThrow(new DomainException("SHARE_NOT_FOUND", "失效"));
        assertEquals(HttpStatus.OK, controller().page(token).getStatusCode());
        var result = controller().page(token);
        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode()); assertFalse(result.getBody().contains("曾经公开的标题"));
        assertFalse(result.getBody().contains("og:image")); assertEquals("no-store", result.getHeaders().getCacheControl());
    }
    @Test void backendFailureIsNotDisguisedAsAnExpiredShare() {
        when(sharing.get(token)).thenThrow(new DomainException("STORAGE_UNAVAILABLE", "失败"));
        assertEquals("STORAGE_UNAVAILABLE", assertThrows(DomainException.class, () -> controller().page(token)).code());
    }
    @Test void canonicalOriginIsConfiguredRatherThanTakenFromUntrustedRequestHeaders() {
        for (String origin : new String[]{"http://evil.test", "https://user@evil.test", "https://safe.test/path", "https://safe.test?x=1"})
            assertThrows(IllegalArgumentException.class, () -> new GiftPageController(sharing, origin, new ByteArrayResource(new byte[0])));
    }
}
