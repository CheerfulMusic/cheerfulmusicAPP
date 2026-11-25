package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.DiscoverSongService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

import static com.qingfeng.music.server.web.publicapi.WechatSelfCardService.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatSelfCardTest {
    private static final String SONG = "A".repeat(43);
    private static final String PATH = "/song/" + SONG;
    private static final String ORIGIN = "https://cheerfulmusic.com";
    private final MutableClock clock = new MutableClock();
    private final WechatOfficialClient client = mock(WechatOfficialClient.class);
    private final PublicSongAccess songs = mock(PublicSongAccess.class);
    private final DiscoverSongService discover = mock(DiscoverSongService.class);
    private WechatSelfCardService service;

    @BeforeEach void start() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:wechat-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V58__wechat_self_card.sql")).execute(source);
        when(client.configured()).thenReturn(true);
        when(client.appId()).thenReturn("fixture-app");
        when(client.sendNews(anyString(), any())).thenReturn(WechatOfficialClient.Delivery.SENT);
        when(songs.require(SONG)).thenReturn(new PublicSongAccess.Grant("id", "Moon", "Singer", "w", "a", "c", "owner", null, 0));
        service = new WechatSelfCardService(new JdbcTemplate(source), new DataSourceTransactionManager(source), client,
                songs, discover, ORIGIN, clock);
    }

    @Test void usesTheCurrentAccountsOpenIdAndTheCanonicalPublicCardOnce() {
        UUID request = UUID.randomUUID();
        assertEquals(BIND_REQUIRED, service.send("first", request, PATH));
        var challenge = service.challenge("first");
        accept("wechat-first", challenge.command());
        assertTrue(service.bound("first"));
        assertFalse(service.bound("second"));
        accept("wechat-other", challenge.command());
        assertEquals(SENT, service.send("first", request, PATH));
        assertEquals(SENT, service.send("first", request, PATH));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.send("first", request, "/song/" + "B".repeat(43))).getStatusCode().value());
        verify(client).sendNews("wechat-first", new WechatOfficialClient.News("Moon · Singer",
                PublicSongController.DESCRIPTION, ORIGIN + PATH, ORIGIN + "/api/songs/" + SONG + "/thumbnail.png"));
    }

    @Test void expiresTheCodeAndRejectsLinkingOneWechatToTwoAccounts() {
        var expired = service.challenge("first");
        clock.advance(Duration.ofMinutes(11));
        accept("wechat-first", expired.command());
        assertFalse(service.bound("first"));
        accept("wechat-first", service.challenge("first").command());
        accept("wechat-first", service.challenge("second").command());
        assertTrue(service.bound("first"));
        assertFalse(service.bound("second"));
        service.unlink("first");
        accept("wechat-first", service.challenge("second").command());
        assertTrue(service.bound("second"));
        verify(client, never()).sendNews(anyString(), any());
    }

    @Test void stalePushDoesNotRenewAndProviderRefusalRequiresANewMessage() {
        accept("wechat-first", service.challenge("first").command());
        long firstMessage = clock.instant().getEpochSecond();
        clock.advance(Duration.ofHours(49));
        service.accept(new WechatCardPush.Text("wechat-first", "分享卡片", firstMessage));
        UUID first = UUID.randomUUID();
        assertEquals(NEEDS_MESSAGE, service.send("first", first, PATH));
        accept("wechat-first", "分享卡片");
        when(client.sendNews(anyString(), any())).thenReturn(WechatOfficialClient.Delivery.NEEDS_MESSAGE,
                WechatOfficialClient.Delivery.SENT);
        assertEquals(NEEDS_MESSAGE, service.send("first", first, PATH));
        assertEquals(NEEDS_MESSAGE, service.send("first", UUID.randomUUID(), PATH));
        clock.advance(Duration.ofSeconds(2));
        accept("wechat-first", "分享卡片");
        assertEquals(SENT, service.send("first", UUID.randomUUID(), PATH));
        verify(client, times(2)).sendNews(anyString(), any());
    }

    @Test void neverResendsAnUnconfirmedDeliveryOrAnInvalidPublicPage() {
        accept("wechat-first", service.challenge("first").command());
        when(client.sendNews(anyString(), any())).thenReturn(WechatOfficialClient.Delivery.UNKNOWN);
        UUID attempt = UUID.randomUUID();
        assertEquals(UNKNOWN, service.send("first", attempt, PATH));
        assertEquals(UNKNOWN, service.send("first", attempt, PATH));
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.send("first", UUID.randomUUID(), "https://other.example/song")).getStatusCode().value());
        when(songs.require(SONG)).thenThrow(PublicSongAccess.missing());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.send("first", UUID.randomUUID(), PATH)).getStatusCode().value());
        verify(client).sendNews(anyString(), any());
    }

    @Test void sendsAPublishedDiscoverRecordingWithItsPublicCover() {
        String id = "1485ddee-9a1f-435b-bfc8-403cccb8c243";
        var song = new ArtistProfileService.PublicSong(id, 3, "Night", true, null);
        when(discover.sharingRelease(id, 3)).thenReturn(new ArtistProfileService.DiscoverSong(
                "public-artist", "Singer", song, clock.instant(), "pop", 0));
        accept("wechat-first", service.challenge("first").command());
        String path = "/en/discover/songs/" + id + "/3";
        assertEquals(SENT, service.send("first", UUID.randomUUID(), path));
        verify(client).sendNews("wechat-first", new WechatOfficialClient.News("Night · Singer",
                "在青风音乐广场收听这首歌。", ORIGIN + path,
                ORIGIN + "/api/public/artists/public-artist/songs/" + id + "/3/cover"));
    }

    private void accept(String openId, String content) {
        service.accept(new WechatCardPush.Text(openId, content, clock.instant().getEpochSecond()));
    }

    private static final class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-11-25T04:00:00Z");
        void advance(Duration amount) { time = time.plus(amount); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time, zone); }
        @Override public Instant instant() { return time; }
    }
}
