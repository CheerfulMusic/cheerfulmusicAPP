package com.qingfeng.music.server.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** 由同一个进程提供 Vue 和 API，公开入口使用各自的静态 HTML。 */
@Controller
public class WebPageController {
    private static final String GUIDE = "/guide/{topic:create-song|edit-lyrics|my-music|credits}";
    private static final MediaType HTML = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final Logger LOG = LoggerFactory.getLogger(WebPageController.class);
    private final Optional<PublicDiscoverDocuments> catalog;

    WebPageController(Optional<PublicDiscoverDocuments> catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/")
    public ResponseEntity<String> home() throws IOException {
        return catalogLanding("home.html", false, true);
    }

    @GetMapping({"/artist/{id}","/artist/{id}/albums/{album}"})
    public String publicAppPage() {
        return "forward:/index.html";
    }

    @GetMapping({"/discover/songs/{song}/{version}", "/en/discover/songs/{song}/{version}"})
    public ResponseEntity<String> recording(HttpServletRequest request, @PathVariable String song,
            @PathVariable String version) throws IOException {
        boolean english = isEnglish(request);
        if (!PublicDiscoverDocuments.validPath(song, version)) return missingRecording(request);
        if (catalog.isEmpty()) {
            // Without an authoritative catalog the client can still open an existing release via its read API.
            var index = new ClassPathResource("static/index.html");
            if (!index.exists()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).header("X-Robots-Tag", "noindex")
                    .body(index.getContentAsString(StandardCharsets.UTF_8));
        }
        var page = catalog.get().detail(song, version, english);
        if (page.isPresent()) {
            return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(page.get());
        }
        return missingRecording(request);
    }

    @GetMapping({"/discover/songs/{*tail}", "/en/discover/songs/{*tail}"})
    public ResponseEntity<String> missingRecording(HttpServletRequest request) {
        boolean english = isEnglish(request);
        String text = english ? "Song unavailable" : "作品暂不可用";
        String body = "<!doctype html><html lang=\"" + (english ? "en" : "zh-CN") + "\"><meta charset=\"utf-8\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + text + "</title><h1>" + text
                + "</h1><a href=\"" + (english ? "/en/discover" : "/discover") + "\">"
                + (english ? "Explore more songs" : "更多广场作品") + "</a></html>";
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(HTML).cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex").body(body);
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
    public ResponseEntity<String> englishHome() throws IOException {
        return catalogLanding("en/index.html", true, true);
    }

    @GetMapping({"/en","/en/index.html"})
    public ResponseEntity<Void> redirectEnglishHome(HttpServletRequest request) {
        return permanentRedirect(request, "/en/");
    }

    @GetMapping({"/discover", "/en/discover"})
    public ResponseEntity<String> discover(HttpServletRequest request) throws IOException {
        return catalogLanding(localizedPath(request, "/discover").substring(1) + "/index.html", isEnglish(request), false);
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> sitemap() throws IOException {
        var file = new ClassPathResource("static/sitemap.xml");
        if (!file.exists()) return ResponseEntity.notFound().build();
        String base = file.getContentAsString(StandardCharsets.UTF_8);
        String document = base;
        if (catalog.isPresent()) {
            try { document = catalog.get().sitemap(base); }
            catch (DataAccessException unavailable) {
                LOG.warn("Could not include published songs in the sitemap", unavailable);
            }
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).cacheControl(CacheControl.noStore()).body(document);
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
        Resource document = new ClassPathResource("static/" + filename);
        if (!document.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(document);
    }

    private ResponseEntity<String> catalogLanding(String filename, boolean english, boolean home) throws IOException {
        var file = new ClassPathResource("static/" + filename);
        if (!file.exists()) return ResponseEntity.notFound().build();
        String document = file.getContentAsString(StandardCharsets.UTF_8);
        if (catalog.isPresent()) {
            try { document = catalog.get().landing(document, english, home); }
            catch (DataAccessException unavailable) {
                // The rest of the landing page can still be used during a catalog outage.
                LOG.warn("Cannot read published songs for {}", filename, unavailable);
            }
        }
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(document);
    }

    private static boolean isEnglish(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/en/");
    }

    private static String localizedPath(HttpServletRequest request, String path) {
        String localePrefix = isEnglish(request) ? "/en" : "";
        return localePrefix + path;
    }

    private static ResponseEntity<Void> permanentRedirect(HttpServletRequest request, String target) {
        String query = request.getQueryString();
        String location = request.getContextPath() + target + (query == null ? "" : "?" + query);
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).header(HttpHeaders.LOCATION, location).build();
    }
}
