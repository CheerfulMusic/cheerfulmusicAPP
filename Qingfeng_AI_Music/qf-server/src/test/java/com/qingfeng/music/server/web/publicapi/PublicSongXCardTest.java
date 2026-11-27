package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.lyrics.playback.PlaybackLyricsRepository;
import com.qingfeng.music.server.web.publicapi.PublicSongController.Song;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicSongXCardTest {
    private static final String ID = "A".repeat(43);
    private static final String ORIGIN = "https://cheerfulmusic.com";

    @Test void usesAConfiguredSiteHandleOnlyInTheOptInCard() throws Exception {
        var controller = controller(" @fixture_account ");
        var cardRequest = new MockHttpServletRequest("HEAD", "/song/" + ID);
        cardRequest.addParameter("card", "x");
        String player = controller.page(ID, cardRequest).getBody();
        assertTrue(player.contains("name=\"twitter:site\" content=\"@fixture_account\""));
        assertTrue(player.contains("name=\"twitter:player\" content=\"" + ORIGIN + "/song/" + ID + "/x-player\""));

        var ordinaryRequest = new MockHttpServletRequest("HEAD", "/song/" + ID);
        String summary = controller.page(ID, ordinaryRequest).getBody();
        assertFalse(summary.contains("name=\"twitter:site\""));
        assertFalse(summary.contains("name=\"twitter:player\""));
    }

    @Test void rejectsAnInvalidSiteHandle() {
        assertThrows(IllegalArgumentException.class, () -> controller("https://x.com/fixture_account"));
    }

    private PublicSongController controller(String site) {
        var access = mock(PublicSongAccess.class);
        var grant = new PublicSongAccess.Grant("share", "Song", "Singer", "work", "audio", null, "owner", null);
        when(access.require(ID)).thenReturn(grant);
        var db = mock(JdbcTemplate.class);
        String media = ORIGIN + "/api/songs/" + ID;
        when(db.queryForObject(anyString(), ArgumentMatchers.<RowMapper<Song>>any(), eq(grant.id())))
                .thenReturn(new Song(grant.title(), grant.creator(), ORIGIN + "/song/" + ID, media + "/cover",
                        media + "/thumbnail.png", media + "/audio", "", null, null));
        return new PublicSongController(access, null, null, mock(PlaybackLyricsRepository.class), db, ORIGIN, site,
                new ClassPathResource("song.html"), new ClassPathResource("song-x-player.html"),
                new ClassPathResource("static/brand/share-default-cover.png"));
    }
}
