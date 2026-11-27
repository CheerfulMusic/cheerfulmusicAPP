package com.qingfeng.music.server.web;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.CatalogSong;
import com.qingfeng.music.server.artist.DiscoverSongService;
import com.qingfeng.music.server.web.publicapi.DiscoverSongController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiscoverSongPageTest {
    private static final String ID = "1485ddee-9a1f-435b-bfc8-403cccb8c243";
    private static final String PATH = "/discover/songs/" + ID + "/1";
    private static final String ORIGIN = "https://cheerfulmusic.com";
    private final DiscoverSongService songs = mock(DiscoverSongService.class);

    private WebPageController controller() {
        var index = new ByteArrayResource("""
                <!doctype html><html><head><title>Cheerful Music</title>
                <meta name="description" content="Generic description"></head>
                <body><div id="app"></div><script src="/assets/app.js"></script></body></html>
                """.getBytes(StandardCharsets.UTF_8));
        return new WebPageController(Optional.of(new PublicDiscoverDocuments(mock(ArtistProfileService.class), songs, ORIGIN, index)));
    }

    @Test void includesThePublishedSongCardInTheFirstHtmlResponse() throws Exception {
        var song = new CatalogSong(ID, 1L, "歌曲 & 音乐", "public-artist", "晨晨 <Live>", true, "cover-revision", Instant.EPOCH);
        when(songs.publicPage(ID, 1L)).thenReturn(Optional.of(new DiscoverSongService.PublicPage(song, "")));
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        for (String path : new String[]{PATH, "/en" + PATH}) {
            mvc.perform(get(path + "?from=timeline"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("歌曲 &amp; 音乐 · 晨晨 &lt;Live&gt;")))
                    .andExpect(content().string(containsString("rel=\"canonical\" href=\"" + ORIGIN + path + "\"")))
                    .andExpect(content().string(containsString("property=\"og:url\" content=\"" + ORIGIN + path + "\"")))
                    .andExpect(content().string(containsString("name=\"twitter:card\" content=\"summary\"")))
                    .andExpect(content().string(containsString("name=\"twitter:image\" content=\"" + ORIGIN
                            + "/api/public" + PATH + "/thumbnail.png?v=cover-revision\"")))
                    .andExpect(content().string(containsString("src=\"/api/public/artists/public-artist/songs/"
                            + ID + "/1/audio\"")))
                    .andExpect(content().string(containsString("<script src=\"/assets/app.js\"")))
                    .andExpect(content().string(not(containsString("Generic description"))));
        }
    }

    @Test void hidesAnUnavailableSongFromSharingAndIndexing() throws Exception {
        when(songs.publicPage(ID, 1L)).thenReturn(Optional.empty());
        MockMvcBuilders.standaloneSetup(controller()).build().perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(content().string(not(containsString("og:title"))))
                .andExpect(content().string(not(containsString("twitter:card"))));
    }

    @Test void thumbnailRechecksDiscoverPublicationBeforeOpeningTheCover() {
        var withdrawn = new DomainException("ARTIST_NOT_FOUND", "Song no longer public on Discover");
        var cover = new ArtistProfileService.PublicSong(ID, 1, "Song", true, "cover-revision");
        when(songs.sharingRelease(ID, 1)).thenReturn(new ArtistProfileService.DiscoverSong(
                "public-artist", "Singer", cover, Instant.EPOCH, "pop", 0)).thenThrow(withdrawn);
        var profiles = mock(ArtistProfileService.class);
        var source = mock(MediaApi.Source.class);
        when(profiles.publicCover("public-artist", ID, 1))
                .thenReturn(new MediaApi.Content(12, "image/jpeg", "cover.jpg", source));

        var controller = new DiscoverSongController(songs, null, profiles);
        assertSame(withdrawn, assertThrows(DomainException.class, () -> controller.thumbnail(UUID.fromString(ID), 1)));
        verifyNoInteractions(source);
    }
}
