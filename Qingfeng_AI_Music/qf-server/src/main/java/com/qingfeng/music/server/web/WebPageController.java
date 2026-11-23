package com.qingfeng.music.server.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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

    @GetMapping("/")
    public ResponseEntity<Resource> home() {
        return publicDocument("home.html");
    }

    @GetMapping("/artist/{id}")
    public String artistPage() {
        return "forward:/index.html";
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

    @GetMapping("/discover")
    public ResponseEntity<Resource> discover() {
        return publicDocument("discover/index.html");
    }

    @GetMapping({"/discover/","/discover/index.html"})
    public ResponseEntity<Void> redirectDiscover(HttpServletRequest request) {
        return permanentRedirect(request, "/discover");
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
        return publicDocument(guidePath(request, topic).substring(1) + "/index.html");
    }

    @GetMapping({GUIDE + "/", GUIDE + "/index.html", "/en" + GUIDE + "/", "/en" + GUIDE + "/index.html"})
    public ResponseEntity<Void> redirectGuide(HttpServletRequest request, @PathVariable("topic") String topic) {
        return permanentRedirect(request, guidePath(request, topic));
    }

    @GetMapping("/legal/terms") public String legacyTerms(){return "redirect:/legal/terms.html";}
    @GetMapping("/legal/privacy") public String legacyPrivacy(){return "redirect:/legal/privacy.html";}

    private static ResponseEntity<Resource> publicDocument(String filename) {
        Resource document = new ClassPathResource("static/" + filename);
        if (!document.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(document);
    }

    private static String guidePath(HttpServletRequest request, String topic) {
        String localePrefix = request.getRequestURI().startsWith(request.getContextPath() + "/en/") ? "/en" : "";
        return localePrefix + "/guide/" + topic;
    }

    private static ResponseEntity<Void> permanentRedirect(HttpServletRequest request, String target) {
        String query = request.getQueryString();
        String location = request.getContextPath() + target + (query == null ? "" : "?" + query);
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT).header(HttpHeaders.LOCATION, location).build();
    }
}
