package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.sharing.api.SharingApi;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/** 在原有收听页的 HTML 首次响应中提供预览信息；不公开作品编辑地址或私人故事。 */
@RestController
@Profile({"mysql", "test"})
public class GiftPageController {
    private final SharingApi sharing;
    private final String origin;
    private final Resource index;

    public GiftPageController(SharingApi sharing,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin,
            @Value("classpath:/static/index.html") Resource index) {
        this.sharing = sharing;
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("分享站点地址必须是 HTTPS 站点根地址");
        this.origin = origin.replaceAll("/+$", "");
        this.index = index;
    }

    @GetMapping(value = "/gift/{token}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String token) throws IOException {
        SharingApi.Gift gift;
        try { gift = sharing.get(token); }
        catch (DomainException error) {
            if (!"SHARE_NOT_FOUND".equals(error.code())) throw error;
            return html(HttpStatus.NOT_FOUND, "<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\">"
                    + "<title>分享已失效 | Cheerful Music</title><h1>这首歌的分享已失效</h1>"
                    + "<p>This song link is unavailable. 请向作者索取新的收听链接</p><a href=\"/\">Cheerful Music</a></html>");
        }
        if (!index.exists()) return html(HttpStatus.SERVICE_UNAVAILABLE,
                "<!doctype html><meta charset=\"UTF-8\"><title>Cheerful Music</title><p>收听页面暂时不可用，请稍后重试</p>");
        String title = escape(gift.title());
        String description = "在 Cheerful Music 收听这首作品 · Listen to this song on Cheerful Music";
        String url = escape(origin + "/gift/" + token);
        String image = escape(origin + (gift.hasCover() ? "/api/public/gifts/" + token + "/cover?proxy=true"
                : "/brand/share-default-cover.png"));
        String metadata = "<title>" + title + " | Cheerful Music</title>"
                + "<meta name=\"description\" content=\"" + description + "\">"
                + "<meta property=\"og:type\" content=\"music.song\">"
                + "<meta property=\"og:site_name\" content=\"Cheerful Music\">"
                + "<meta property=\"og:title\" content=\"" + title + "\">"
                + "<meta property=\"og:description\" content=\"" + description + "\">"
                + "<meta property=\"og:url\" content=\"" + url + "\">"
                + "<meta property=\"og:image\" content=\"" + image + "\">"
                + "<meta name=\"twitter:card\" content=\"summary_large_image\">"
                + "<meta name=\"twitter:title\" content=\"" + title + "\">"
                + "<meta name=\"twitter:description\" content=\"" + description + "\">"
                + "<meta name=\"twitter:image\" content=\"" + image + "\">"
                + "<meta name=\"robots\" content=\"noindex, nofollow, noarchive\">";
        String shell;
        try (var input = index.getInputStream()) { shell = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        shell = shell.replaceAll("(?is)<title[^>]*>.*?</title>", "")
                .replaceAll("(?is)<meta\\s+name=[\"']description[\"'][^>]*>", "");
        // 字符串拼接避免 replaceAll 将用户标题里的 $ 或反斜线解释为替换表达式。
        int head = shell.toLowerCase(java.util.Locale.ROOT).indexOf("</head>");
        if (head < 0) throw new IOException("前端入口缺少 head");
        return html(HttpStatus.OK, shell.substring(0, head) + metadata + shell.substring(head));
    }

    private String escape(String value) { return HtmlUtils.htmlEscape(value, "UTF-8"); }
    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow, noarchive").body(body);
    }
}
