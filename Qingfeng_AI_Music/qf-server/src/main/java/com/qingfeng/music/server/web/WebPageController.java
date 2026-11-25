package com.qingfeng.music.server.web;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.server.artist.ArtistProfileService.DiscoverSong;
import com.qingfeng.music.server.artist.DiscoverSongService;
import com.qingfeng.music.server.web.util.SongShareTitle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/** 由同一个进程提供 Vue 和 API，公开入口使用各自的静态 HTML。 */
@Controller
public class WebPageController {
    private static final String GUIDE = "/guide/{topic:create-song|edit-lyrics|my-music|credits}";

    private final ObjectProvider<DiscoverSongService> discoverSongs;
    private final String shareOrigin;
    private final Resource index;

    public WebPageController(ObjectProvider<DiscoverSongService> discoverSongs,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String shareOrigin,
            @Value("classpath:/static/index.html") Resource index) {
        this.discoverSongs = discoverSongs;
        this.index = index;
        this.shareOrigin = shareOrigin.replaceAll("/+$", "");
    }

    @GetMapping("/")
    public ResponseEntity<Resource> home() {
        return publicDocument("home.html");
    }

    @GetMapping({"/artist/{id}","/artist/{id}/albums/{album}"})
    public String publicAppPage() {
        return "forward:/index.html";
    }

    @ResponseBody
    @GetMapping({"/discover/songs/{song}/{version}","/en/discover/songs/{song}/{version}"})
    public ResponseEntity<?> discoverSong(HttpServletRequest request, @PathVariable UUID song, @PathVariable long version)
            throws IOException {
        if (!index.exists()) return ResponseEntity.notFound().build();
        var songs = discoverSongs.getIfAvailable();
        if (songs == null || version < 1) return publicDocument(index);
        DiscoverSong release;
        try {
            release = songs.sharingRelease(song.toString(), version);
        } catch (DomainException missing) {
            if (!"ARTIST_NOT_FOUND".equals(missing.code())) throw missing;
            return publicDocument(index);
        }
        String path = "/discover/songs/" + song + "/" + version;
        String url = shareOrigin + localizedPath(request, path);
        String image = shareOrigin + "/api/public" + path + "/thumbnail.png";
        if (release.song().coverVersion() != null) image += "?v=" + release.song().coverVersion();
        String title = SongShareTitle.forTimeline(release.song().title(), release.stageName());
        String description = request.getRequestURI().startsWith(request.getContextPath() + "/en/")
                ? "Listen to this song on Cheerful Music." : "在青风音乐广场收听这首歌。";
        String metadata = "<title>" + escape(title) + "</title>"
                + "<meta name=\"description\" content=\"" + escape(description) + "\">"
                + socialMeta("type", "music.song") + socialMeta("site_name", SongShareTitle.SITE_NAME)
                + socialMeta("title", title) + socialMeta("description", description)
                + socialMeta("url", url) + socialMeta("image", image);
        String shell = index.getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?is)<title[^>]*>.*?</title>", "")
                .replaceAll("(?i)<meta name=\"description\"[^>]*>", "");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).body(shell.replace("</head>", metadata + "</head>"));
    }

    private static String socialMeta(String property, String content) {
        return "<meta property=\"og:" + property + "\" content=\"" + escape(content) + "\">";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }

    @GetMapping({"/create","/create/photo","/works","/works/{id}","/account","/profile","/recharge","/analytics","/agent"})
    public String privateAppPage(HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex");
        return "forward:/index.html";
    }

    @GetMapping({"/zh-CN", "/zh-CN/", "/zh-CN/index.html"})
    public ResponseEntity<Void> redirectChineseHome(HttpServletRequest request) {
        return permanentRedirect(request, "/");
    }

    @GetMapping("/en/")
    public ResponseEntity<Resource> englishHome() {
        return publicDocument("en/index.html");
    }

    @GetMapping({"/en","/en/index.html"})
    public ResponseEntity<Void> redirectEnglishHome(HttpServletRequest request) {
        return permanentRedirect(request, "/en/");
    }

    @GetMapping({"/discover", "/en/discover"})
    public ResponseEntity<Resource> discover(HttpServletRequest request) {
        return publicDocument(localizedPath(request, "/discover").substring(1) + "/index.html");
    }

    @GetMapping({"/discover/", "/discover/index.html", "/en/discover/", "/en/discover/index.html"})
    public ResponseEntity<Void> redirectDiscover(HttpServletRequest request) {
        return permanentRedirect(request, localizedPath(request, "/discover"));
    }

    @GetMapping("/help")
    public ResponseEntity<Resource> help() {
        return publicDocument("help/index.html");
    }

    @GetMapping({"/help/","/help/index.html"})
    public ResponseEntity<Void> redirectHelp(HttpServletRequest request) {
        return permanentRedirect(request, "/help");
    }

    @GetMapping({GUIDE, "/en" + GUIDE})
    public ResponseEntity<Resource> guide(HttpServletRequest request, @PathVariable("topic") String topic) {
        return publicDocument(localizedPath(request, "/guide/" + topic).substring(1) + "/index.html");
    }

    @GetMapping({GUIDE + "/", GUIDE + "/index.html", "/en" + GUIDE + "/", "/en" + GUIDE + "/index.html"})
    public ResponseEntity<Void> redirectGuide(HttpServletRequest request, @PathVariable("topic") String topic) {
        return permanentRedirect(request, localizedPath(request, "/guide/" + topic));
    }

    @GetMapping("/legal/terms") public String legacyTerms(){return "redirect:/legal/terms.html";}
    @GetMapping("/legal/privacy") public String legacyPrivacy(){return "redirect:/legal/privacy.html";}

    private static ResponseEntity<Resource> publicDocument(String filename) {
        return publicDocument(new ClassPathResource("static/" + filename));
    }

    private static ResponseEntity<Resource> publicDocument(Resource document) {
        if (!document.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(document);
    }

    private static String localizedPath(HttpServletRequest request, String path) {
        String localePrefix = request.getRequestURI().startsWith(request.getContextPath() + "/en/") ? "/en" : "";
        return localePrefix + path;
    }

    private static ResponseEntity<Void> permanentRedirect(HttpServletRequest request, String target) {
        String query = request.getQueryString();
        String location = request.getContextPath() + target + (query == null ? "" : "?" + query);
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).header(HttpHeaders.LOCATION, location).build();
    }
}
