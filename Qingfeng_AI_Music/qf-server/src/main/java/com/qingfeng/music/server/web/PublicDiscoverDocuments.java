package com.qingfeng.music.server.web;

import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.artist.ArtistProfileService.CatalogSong;
import com.qingfeng.music.server.artist.DiscoverSongService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

/** The primary catalog is authoritative for withdrawals. A regional read replica must not render catalog entries. */
@Component
@Profile({"mysql & !sg-reader", "test"})
final class PublicDiscoverDocuments {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SLOT = "<!--public-discover-links-->";
    private static final Pattern PUBLIC_ID = Pattern.compile("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}");
    private final ArtistProfileService artists;
    private final DiscoverSongService songs;
    private final String origin;

    PublicDiscoverDocuments(ArtistProfileService artists, DiscoverSongService songs,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin) {
        this.artists = artists;
        this.songs = songs;
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Expected a public HTTPS site origin");
        }
        this.origin = "https://" + uri.getRawAuthority();
    }

    String landing(String shell, boolean english, boolean home) {
        if (!shell.contains(SLOT)) return shell;
        List<CatalogSong> catalog = artists.latestCatalogSongs(home, home ? 3 : 24);
        if (catalog.isEmpty()) return shell.replace(SLOT, "");
        StringBuilder block = new StringBuilder("<section><h2>");
        block.append(english ? "Listen to original songs" : "试听原创歌曲").append("</h2><ul>");
        for (CatalogSong song : catalog) {
            block.append("<li><a href=\"").append(path(song, english)).append("\">")
                    .append(escape(song.title())).append(" — ").append(escape(song.stageName())).append("</a></li>");
        }
        return shell.replace(SLOT, block.append("</ul></section>").toString());
    }

    String sitemap(String base) {
        StringBuilder entries = new StringBuilder();
        for (CatalogSong song : artists.latestCatalogSongs(false, 1000)) {
            for (boolean english : List.of(false, true)) {
                entries.append("  <url><loc>").append(escape(origin + path(song, english))).append("</loc></url>\n");
            }
        }
        return base.replace("</urlset>", entries + "</urlset>");
    }

    Optional<String> detail(String id, String versionText, boolean english) throws IOException {
        long version = Long.parseLong(versionText); // The page controller validates the canonical URL first.
        var page = songs.publicPage(id, version);
        if (page.isEmpty()) return Optional.empty();
        CatalogSong song = page.get().song();
        String site = english ? "cheerfulmusic" : "青妙Ai";
        String title = song.title() + " — " + song.stageName() + " | " + site;
        String description = page.get().description();
        if (description.isBlank()) {
            description = english ? "Listen to " + song.title() + " by " + song.stageName() + " on " + site + "."
                    : "在青妙Ai收听" + song.stageName() + "的原创歌曲《" + song.title() + "》。";
        }
        String canonical = origin + path(song, english);
        String artistPath = "/artist/" + song.artistId();
        String media = "/api/public/artists/" + song.artistId() + "/songs/" + song.id() + "/" + song.version();
        String image = origin + (song.hasCover() ? media + "/cover" : "/brand/share-default-cover.png");
        String schema = JSON.writeValueAsString(Map.of("@context", "https://schema.org", "@type", "MusicRecording",
                "name", song.title(), "url", canonical, "image", image, "description", description,
                "datePublished", song.publishedAt().toString(), "byArtist",
                Map.of("@type", "MusicGroup", "name", song.stageName(), "url", origin + artistPath)))
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        String head = """
                <title>%s</title><meta name="description" content="%s">
                <link data-site-seo rel="canonical" href="%s">
                <link data-site-seo rel="alternate" hreflang="zh-CN" href="%s">
                <link data-site-seo rel="alternate" hreflang="en" href="%s">
                <meta data-site-seo property="og:type" content="music.song"><meta data-site-seo property="og:site_name" content="%s">
                <meta data-site-seo property="og:title" content="%s"><meta data-site-seo property="og:description" content="%s">
                <meta data-site-seo property="og:url" content="%s"><meta data-site-seo property="og:image" content="%s">
                <script data-site-seo type="application/ld+json">%s</script>
                """.formatted(escape(title), escape(description), canonical, origin + path(song, false),
                origin + path(song, true), site, escape(title), escape(description), canonical, escape(image), schema);
        String cover = song.hasCover() ? "<img src=\"" + media + "/cover\" width=\"240\" height=\"240\" alt=\""
                + escape(english ? "Cover of " + song.title() : "《" + song.title() + "》的封面") + "\">" : "";
        String body = """
                <main><h1>%s</h1>%s<p><a href="%s">%s</a></p><p>%s</p>
                <audio controls preload="none" src="%s/audio"></audio>
                <p><a href="%s">%s</a></p></main>
                """.formatted(escape(song.title()), cover, artistPath, escape(song.stageName()), escape(description),
                media, english ? "/en/discover" : "/discover", english ? "Explore more songs" : "更多广场作品");
        String shell = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
        String html = shell.replaceFirst("(?i)<html lang=\"[^\"]*\"", "<html lang=\"" + (english ? "en" : "zh-CN") + "\"")
                .replaceAll("(?is)<title[^>]*>.*?</title>", "")
                .replaceAll("(?is)<meta\\s+name=[\"']description[\"'][^>]*>", "")
                .replace("</head>", head + "</head>")
                .replace("<div id=\"app\"></div>", "<div id=\"app\">" + body + "</div>");
        return Optional.of(html);
    }

    private static String path(CatalogSong song, boolean english) {
        return (english ? "/en" : "") + "/discover/songs/" + song.id() + "/" + song.version();
    }

    static boolean validPath(String id, String version) {
        if (!PUBLIC_ID.matcher(id).matches() || !version.matches("[1-9][0-9]{0,18}")) return false;
        try { Long.parseLong(version); return true; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
}
