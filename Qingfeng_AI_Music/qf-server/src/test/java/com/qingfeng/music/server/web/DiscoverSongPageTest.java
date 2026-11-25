package com.qingfeng.music.server.web;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.artist.ArtistProfileService.DiscoverSong;
import com.qingfeng.music.server.artist.ArtistProfileService.PublicSong;
import com.qingfeng.music.server.artist.DiscoverSongService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DiscoverSongPageTest {
    private static final String ID = "1485ddee-9a1f-435b-bfc8-403cccb8c243";
    private static final String PATH = "/discover/songs/" + ID + "/1";
    private static final String ORIGIN = "https://cheerfulmusic.com";
    private final DiscoverSongService songs = mock(DiscoverSongService.class);

    private WebPageController controller() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DiscoverSongService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(songs);
        var index = new ByteArrayResource("""
                <!doctype html><html><head><title>Cheerful Music</title>
                <meta name="description" content="Generic description"></head>
                <body><div id="app"></div><script src="/assets/app.js"></script></body></html>
                """.getBytes(StandardCharsets.UTF_8));
        return new WebPageController(provider, ORIGIN, index);
    }

    @Test void includesThePublishedSongCardInTheFirstHtmlResponse() throws Exception {
        when(songs.sharingRelease(ID, 1L)).thenReturn(new DiscoverSong("public-artist", "晨晨 <Live>",
                new PublicSong(ID, 1L, "歌曲 & 音乐", true, "cover-revision"), Instant.EPOCH, "pop", 0L));
        var mvc = MockMvcBuilders.standaloneSetup(controller()).build();

        for (String path : new String[]{PATH, "/en" + PATH}) {
            mvc.perform(get(path + "?from=timeline"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(containsString("歌曲 &amp; 音乐 · 晨晨 &lt;Live&gt;")))
                    .andExpect(content().string(containsString("content=\"" + ORIGIN + path + "\"")))
                    .andExpect(content().string(containsString(ORIGIN + "/api/public" + PATH + "/thumbnail.png?v=cover-revision")))
                    .andExpect(content().string(containsString("<script src=\"/assets/app.js\"")))
                    .andExpect(content().string(not(containsString("Generic description"))));
        }
    }

    @Test void preservesTheDetailShellWithoutExposingMetadataForAnUnavailableSong() throws Exception {
        when(songs.sharingRelease(ID, 1L)).thenThrow(new DomainException("ARTIST_NOT_FOUND", "Not public"));
        MockMvcBuilders.standaloneSetup(controller()).build().perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<div id=\"app\"></div>")))
                .andExpect(content().string(not(containsString("og:title"))));
    }
}
