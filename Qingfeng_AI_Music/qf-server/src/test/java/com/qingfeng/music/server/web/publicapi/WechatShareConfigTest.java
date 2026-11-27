package com.qingfeng.music.server.web.publicapi;

import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;

class WechatShareConfigTest {
    @Test void sendsOfficialNewsAndReusesTheSigningAccessToken() throws Exception {
        var http = mock(HttpClient.class);
        var client = new WechatOfficialClient("official-account", "server-only", http);
        @SuppressWarnings("unchecked") HttpResponse<String> token = mock(HttpResponse.class), ticket = mock(HttpResponse.class),
            sent = mock(HttpResponse.class);
        when(token.statusCode()).thenReturn(200);
        when(ticket.statusCode()).thenReturn(200);
        when(sent.statusCode()).thenReturn(200);
        when(token.body()).thenReturn("{\"access_token\":\"token\",\"expires_in\":7200}");
        when(ticket.body()).thenReturn("{\"ticket\":\"ticket\",\"expires_in\":7200}");
        when(sent.body()).thenReturn("{\"errcode\":0}");
        when(http.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(token, ticket, sent);
        assertEquals("ticket", client.jsapiTicket());
        var article = new WechatOfficialClient.News("歌名", "介绍", "https://cheerfulmusic.com/song/example",
                "https://cheerfulmusic.com/cover.png");
        assertEquals(WechatOfficialClient.Delivery.SENT, client.sendNews("bound-open-id", article));
        var requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(3)).send(requests.capture(), any());
        assertEquals("/cgi-bin/stable_token", requests.getAllValues().get(0).uri().getPath());
        var request = requests.getAllValues().get(2);
        assertEquals("/cgi-bin/message/custom/send", request.uri().getPath());
        assertEquals("access_token=token", request.uri().getQuery());
        var payload = new ObjectMapper().readTree(body(request));
        assertEquals("bound-open-id", payload.path("touser").asString());
        assertEquals("news", payload.path("msgtype").asString());
        var entry = payload.path("news").path("articles").path(0);
        assertEquals(article.title(), entry.path("title").asString());
        assertEquals(article.description(), entry.path("description").asString());
        assertEquals(article.url(), entry.path("url").asString());
        assertEquals(article.picurl(), entry.path("picurl").asString());
    }

    private static byte[] body(HttpRequest request) {
        var result = new CompletableFuture<byte[]>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] next = new byte[buffer.remaining()];
                buffer.get(next);
                bytes.writeBytes(next);
            }
            @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.join();
    }

    @Test void signsTheExactUrlAndCachesTheTicketAcrossSongs() throws Exception {
        String origin = "https://cheerfulmusic.com";
        var client = mock(HttpClient.class);
        var signer = new WechatShareConfigController(new WechatOfficialClient("official-account", "server-only", client), origin);
        @SuppressWarnings("unchecked") HttpResponse<String> token = mock(HttpResponse.class), ticket = mock(HttpResponse.class);
        when(token.statusCode()).thenReturn(200);
        when(ticket.statusCode()).thenReturn(200);
        when(token.body()).thenReturn("{\"access_token\":\"token\",\"expires_in\":7200}");
        when(ticket.body()).thenReturn("{\"ticket\":\"ticket\",\"expires_in\":7200}");
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(token).thenReturn(ticket);
        String discover = "/discover/songs/1485ddee-9a1f-435b-bfc8-403cccb8c243/";
        String shortSong = "/s/Vm-" + "_".repeat(19);
        for (String path : new String[]{"/song/" + "a".repeat(43), shortSong, discover + "1", "/en" + discover + "2"}) {
            String url = origin + path + "?from=timeline&name=a%20b";
            var config = signer.config(url).getBody();
            String canonical = "jsapi_ticket=ticket&noncestr=" + config.get("nonceStr") + "&timestamp=" + config.get("timestamp") + "&url=" + url;
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical.getBytes(StandardCharsets.UTF_8))),config.get("signature"));
            assertEquals(java.util.Set.of("appId","nonceStr","signature","timestamp"),config.keySet());
        }
        verify(client,times(2)).send(any(),any());
        for (String invalid : new String[]{"https://other.example" + discover + "1", origin + discover + "0",
                origin + discover + "1#share-friend", origin + "/discover", origin + "/discover/songs/not-a-song/1",
                origin + shortSong + "/x-player", origin + shortSong + "e", origin + "/s/%41" + "a".repeat(21)}) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> signer.config(invalid)).getStatusCode().value());
        }
    }
}
