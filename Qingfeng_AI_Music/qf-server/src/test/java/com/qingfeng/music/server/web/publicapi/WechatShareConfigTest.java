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
        String discover = "/discover/songs/1485ddee-9a1f-435b-bfc8-403cccb8c243/";
        for (String path : new String[]{"/song/" + "a".repeat(43), discover + "1", "/en" + discover + "2"}) {
            String url = origin + path + "?from=timeline&name=a%20b";
            var config = signer.config(url).getBody();
            String canonical = "jsapi_ticket=ticket&noncestr=" + config.get("nonceStr") + "&timestamp=" + config.get("timestamp") + "&url=" + url;
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8))),config.get("signature"));
            assertEquals(java.util.Set.of("appId","nonceStr","signature","timestamp"),config.keySet());
        }
        verify(client,times(2)).send(any(),any());
        for (String invalid : new String[]{"https://other.example" + discover + "1", origin + discover + "0",
                origin + discover + "1#share-friend", origin + "/discover", origin + "/discover/songs/not-a-song/1"}) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> signer.config(invalid)).getStatusCode().value());
        }
    }
}