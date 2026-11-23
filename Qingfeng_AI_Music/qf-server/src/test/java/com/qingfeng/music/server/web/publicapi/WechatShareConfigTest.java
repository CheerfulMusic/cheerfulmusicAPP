package com.qingfeng.music.server.web.publicapi;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatShareConfigTest {
    @Test void signsTheExactUrlAndCachesTheTicketAcrossSongs() throws Exception {
        String origin = "https://cheerfulmusic.com";
        var signer = new WechatShareConfigController("official-account", "server-only", origin);
        var client = mock(HttpClient.class);
        ReflectionTestUtils.setField(signer, "client", client);
        @SuppressWarnings("unchecked") HttpResponse<String> token = mock(HttpResponse.class), ticket = mock(HttpResponse.class);
        when(token.statusCode()).thenReturn(200);
        when(ticket.statusCode()).thenReturn(200);
        when(token.body()).thenReturn("{\"access_token\":\"token\",\"expires_in\":7200}");
        when(ticket.body()).thenReturn("{\"ticket\":\"ticket\",\"expires_in\":7200}");
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(token).thenReturn(ticket);
        for (String id : new String[]{"a".repeat(43), "b".repeat(43)}) {
            String url = origin + "/song/" + id + "?from=timeline&name=a%20b";
            var config = signer.config(url).getBody();
            String canonical = "jsapi_ticket=ticket&noncestr=" + config.get("nonceStr") + "&timestamp=" + config.get("timestamp") + "&url=" + url;
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8))),config.get("signature"));
            assertEquals(java.util.Set.of("appId","nonceStr","signature","timestamp"),config.keySet());
        }
        verify(client,times(2)).send(any(),any());
        assertThrows(ResponseStatusException.class, () -> signer.config("https://other.example/song/"+"a".repeat(43)));
    }
}