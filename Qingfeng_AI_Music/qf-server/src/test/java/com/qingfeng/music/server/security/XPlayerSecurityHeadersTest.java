package com.qingfeng.music.server.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class XPlayerSecurityHeadersTest {
    private static final String SONG = "/song/" + "A".repeat(43);
    private static final String PLAYER = SONG + "/x-player";
    private static final String POLICY = "Content-Security-Policy";
    private static final String FRAME = "X-Frame-Options";

    @Test void onlyXHostsCanFrameTheSuccessfulPlayerDocument() {
        for (String method : List.of("GET", "HEAD")) {
            var request = new MockHttpServletRequest(method, PLAYER);
            request.setQueryString("lang=en");
            request.addHeader("Origin", "https://untrusted.example");
            var response = headers(request, 200, "text/html;charset=UTF-8");
            assertNull(response.getHeader(FRAME));
            assertTrue(response.getHeader(POLICY).endsWith(
                    "frame-ancestors https://x.com https://twitter.com https://platform.x.com https://platform.twitter.com"));
            assertTrue(response.getHeader(POLICY).contains("media-src 'self'"));
            assertTrue(response.getHeader(POLICY).contains("default-src 'none'"));
        }
    }

    @Test void ordinaryPagesAndMethodsKeepDefaultFrameDenial() {
        for (String path : List.of(SONG, "/s/" + "A".repeat(22), PLAYER + "/", PLAYER + "/extra", "/song/malformed/x-player", "/works")) {
            assertDenied(headers(new MockHttpServletRequest("GET", path), 200, "text/html"));
        }
        assertDenied(headers(new MockHttpServletRequest("POST", PLAYER), 200, "text/html"));
        var forwarded = new MockHttpServletRequest("GET", PLAYER);
        forwarded.setDispatcherType(DispatcherType.FORWARD);
        assertDenied(headers(forwarded, 200, "text/html"));
    }

    @Test void missingExpiredAndErrorResponsesCannotBeFramed() {
        for (int status : List.of(302, 400, 403, 404, 410, 500)) {
            assertDenied(headers(new MockHttpServletRequest("GET", PLAYER), status, "text/html"));
        }
        assertDenied(headers(new MockHttpServletRequest("GET", PLAYER), 200, "application/json"));
    }

    private static MockHttpServletResponse headers(MockHttpServletRequest request, int status, String type) {
        var response = new MockHttpServletResponse();
        response.setStatus(status);
        response.setContentType(type);
        WebSecurityConfiguration.writeFrameProtection(request, response);
        return response;
    }

    private static void assertDenied(MockHttpServletResponse response) {
        assertEquals("DENY", response.getHeader(FRAME));
        assertNull(response.getHeader(POLICY));
    }
}
