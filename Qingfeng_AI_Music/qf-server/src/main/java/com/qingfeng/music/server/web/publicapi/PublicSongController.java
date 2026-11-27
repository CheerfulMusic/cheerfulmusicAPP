package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.media.api.SharedMediaAccess;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.lyrics.application.LyricsFormatting;
import com.qingfeng.music.lyrics.playback.PlaybackLyrics;
import com.qingfeng.music.lyrics.playback.PlaybackLyricsRepository;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.server.web.publicapi.PublicSongAccess.Grant;
import com.qingfeng.music.server.web.util.SongShareTitle;
import jakarta.servlet.http.HttpServletRequest;
import static com.qingfeng.music.server.web.publicapi.PublicSongAccess.missing;
import static java.util.Map.entry;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

@RestController
@Profile({"mysql","test"})
public class PublicSongController {
    static final String DESCRIPTION = "我在 Cheerful Music 创作了一首歌曲，邀请你来听听";
    private static final int X_PLAYER_WIDTH = 480;
    private static final int X_PLAYER_HEIGHT = 270;
    private static final String X_CARD_IMAGE_QUERY = "?card=x";
    private static final Pattern TEMPLATE_SLOT = Pattern.compile("\\{\\{(\\w+)}}");
    private final PublicSongAccess access;
    private final SharedMediaAccess media;
    private final SharedCoverAccess covers;
    private final PlaybackLyricsRepository timelines;
    private final JdbcTemplate db;
    private final String origin;
    private final String xPlayerSite;
    private final Resource template, xPlayerTemplate, fallback;
    public PublicSongController(PublicSongAccess access, SharedMediaAccess media, SharedCoverAccess covers, PlaybackLyricsRepository timelines, JdbcTemplate db,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin,
            @Value("${qingfeng.sharing.x-player.site:}") String xPlayerSite,
            @Value("classpath:/song.html") Resource template,
            @Value("classpath:/song-x-player.html") Resource xPlayerTemplate,
            @Value("classpath:/static/brand/share-default-cover.png") Resource fallback) {
        this.access = access;
        this.media = media;
        this.covers = covers;
        this.timelines = timelines;
        this.db = db;
        this.origin = origin.replaceAll("/+$", "");
        this.xPlayerSite = xPlayerSite.strip();
        if (!this.xPlayerSite.isEmpty() && !this.xPlayerSite.matches("@[A-Za-z0-9_]{1,15}")) {
            throw new IllegalArgumentException("qingfeng.sharing.x-player.site must be an X account handle beginning with @");
        }
        this.template = template;
        this.xPlayerTemplate = xPlayerTemplate;
        this.fallback = fallback;
    }

    public record Song(String title, String creator, String url, String image, String thumbnail, String audio,
                       String lyrics, Double durationSeconds, String artistId) {}

    private Song song(String id, Grant gift) {
        String base = origin + "/api/songs/" + id;
        return db.queryForObject("""
                SELECT s.lyrics,m.duration_s FROM share_link s
                JOIN media_asset m ON m.id=s.asset_id AND m.work_id=s.work_id WHERE s.id=?
                """, (row,n) -> new Song(gift.title(),gift.creator(),origin+"/song/"+id,
                    gift.cover()!=null ? base+"/cover" : origin+"/brand/share-default-cover.png",
                    base+"/thumbnail.png",base+"/audio",row.getString(1),row.getObject(2,Double.class),gift.artist()), gift.id());
    }
    @GetMapping("/api/songs/{id}/public")
    public ResponseEntity<Song> info(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(song(id,access.require(id)));
    }
    @GetMapping(value="/song/{id}", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String id, HttpServletRequest request) throws IOException {
        return renderSongPage(id, origin + "/song/" + id, request);
    }

    @GetMapping(value="/s/{code}", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> shortPage(@PathVariable String code, HttpServletRequest request) throws IOException {
        return renderSongPage(access.resolveShortCode(code), origin + "/s/" + code, request);
    }

    private ResponseEntity<String> renderSongPage(String id, String publicUrl, HttpServletRequest request) throws IOException {
        Grant gift = access.require(id);
        Song song = song(id,gift);
        Double duration = song.durationSeconds();
        boolean knownDuration = duration != null && Double.isFinite(duration) && duration > 0;
        boolean englishShare = "en".equals(request.getParameter("lang"));
        boolean xCard = "x".equals(request.getParameter("card")) && origin.startsWith("https://");
        String languageQuery = englishShare ? "?lang=en" : "";
        String shareUrl = publicUrl + languageQuery;
        String canonical = xCard ? shareUrl + (englishShare ? "&card=x" : X_CARD_IMAGE_QUERY) : shareUrl;
        String timelineTitle = SongShareTitle.forTimeline(song.title(), song.creator());
        boolean hasArtist = song.artistId() != null;
        String credit = hasArtist ? " by " + song.creator() : "";
        String shareTitle = hasArtist ? song.title() + " · " + song.creator() : song.title();
        var fields = java.util.Map.ofEntries(
                entry("title", song.title()), entry("creator", song.creator()),
                entry("siteName", SongShareTitle.SITE_NAME),
                entry("timelineTitle", timelineTitle),
                entry("shareSiteName", englishShare ? "Cheerful Music" : SongShareTitle.SITE_NAME),
                entry("shareTitle", englishShare ? shareTitle : timelineTitle),
                entry("canonical", canonical), entry("shareUrl", shareUrl),
                entry("shareLocale", englishShare ? "en_US" : "zh_CN"),
                entry("wechatUrl", publicUrl), entry("wechatDescription", DESCRIPTION),
                entry("image", song.image().substring(origin.length())),
                entry("thumbnail", song.thumbnail()), entry("audio", song.audio().substring(origin.length())),
                entry("twitterCard", xCard ? "player" : "summary"),
                entry("twitterImage", xCard ? song.thumbnail() + X_CARD_IMAGE_QUERY : song.thumbnail()),
                entry("artistId", hasArtist ? song.artistId() : ""),
                entry("description", englishShare ? "Come listen to “" + song.title() + "”" + credit + " on Cheerful Music." : DESCRIPTION),
                entry("duration", knownDuration ? duration.toString() : ""),
                entry("durationLabel", knownDuration ? timeLabel(duration.longValue()) : "–:––"));
        String playerMeta = xCard ? xPlayerMetadata(song.url() + "/x-player" + languageQuery) : "";
        String page = render(template, fields, Map.of("lyricLines", lyricMarkup(gift, song.lyrics()), "xPlayerMeta", playerMeta));
        if ("GET".equals(request.getMethod())) db.update("UPDATE share_link SET page_views=page_views+1 WHERE id=?",gift.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(page);
    }

    private String xPlayerMetadata(String url) {
        String site = xPlayerSite.isEmpty() ? "" : "<meta name=\"twitter:site\" content=\"" + xPlayerSite + "\">\n";
        return site + """
                <meta name="twitter:player" content="%s">
                <meta name="twitter:player:width" content="%d">
                <meta name="twitter:player:height" content="%d">
                """.formatted(HtmlUtils.htmlEscape(url, "UTF-8"), X_PLAYER_WIDTH, X_PLAYER_HEIGHT);
    }

    @GetMapping(value="/song/{id}/x-player", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> xPlayer(@PathVariable String id, @RequestParam(required=false) String lang) throws IOException {
        Grant grant = access.require(id);
        boolean english = "en".equals(lang);
        String mediaPath = "/api/songs/" + id;
        var fields = Map.ofEntries(
                entry("lang", english ? "en" : "zh-CN"),
                entry("title", grant.title()),
                entry("artist", grant.artist() == null && english ? "" : grant.creator()),
                entry("cover", mediaPath + "/thumbnail.png"),
                entry("audio", mediaPath + "/audio"),
                entry("play", mediaPath + "/play"),
                entry("songUrl", "/song/" + id + (english ? "?lang=en" : "")),
                entry("listenLabel", english ? "Preview song" : "播放试听"),
                entry("openLabel", english ? "Open song page" : "打开歌曲页"),
                entry("errorLabel", english ? "Preview unavailable. Open the song page to try again." : "暂时无法试听，打开歌曲页后重试。"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .body(render(xPlayerTemplate, fields, Map.of()));
    }

    private static String render(Resource template, Map<String, String> fields, Map<String, String> markup) throws IOException {
        var slots = TEMPLATE_SLOT.matcher(template.getContentAsString(StandardCharsets.UTF_8));
        return slots.replaceAll(match -> {
            String key = match.group(1);
            String trustedMarkup = markup.get(key);
            return Matcher.quoteReplacement(trustedMarkup != null ? trustedMarkup : HtmlUtils.htmlEscape(fields.get(key), "UTF-8"));
        });
    }

    private static final java.util.regex.Pattern SECTION_LABEL = java.util.regex.Pattern.compile(
            "[\\[【](?:(?:final |instrumental )?(?:verse|pre[- ]?chorus|chorus|bridge|interlude|intro|outro|instrumental|break|hook)|主歌|预副歌|副歌|桥段|前奏|尾奏|间奏)\\s*\\d*[\\]】]",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private String lyricMarkup(Grant grant, String snapshot) {
        var lines = timelines.find(grant.asset()).filter(t -> "READY".equals(t.status()))
                .map(t -> PlaybackLyrics.lines(t.words())).orElseGet(java.util.List::of);
        var html = new StringBuilder();
        for (var line : lines) {
            html.append("<p data-start=\"").append(line.start()).append("\" data-end=\"").append(line.end()).append("\">")
                    .append(HtmlUtils.htmlEscape(line.text(), "UTF-8")).append("</p>");
        }
        if (html.isEmpty() && snapshot != null) {
            boolean newVerse = false;
            for (String raw : LyricsFormatting.unwrap(snapshot).lines().toList()) {
                String line = raw.strip().replaceFirst("^#{1,6}\\h+", "");
                if (line.length() >= 4 && ((line.startsWith("**") && line.endsWith("**"))
                        || (line.startsWith("__") && line.endsWith("__")))) line = line.substring(2, line.length()-2).strip();
                if (raw.stripLeading().startsWith("#") && (line.equals(grant.title()) || line.equals("《"+grant.title()+"》"))) continue;
                if (line.isEmpty() || SECTION_LABEL.matcher(line).matches()) { newVerse = !html.isEmpty(); continue; }
                html.append(newVerse ? "<p class=\"verse-start\">" : "<p>")
                        .append(HtmlUtils.htmlEscape(line,"UTF-8")).append("</p>");
                newVerse = false;
            }
        }
        return html.isEmpty() ? "<p class=\"empty-lyrics\">这首歌暂无公开歌词</p>" : html.toString();
    }

    private static String timeLabel(long seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    @GetMapping("/api/songs/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String id,@RequestHeader(value="Range",required=false) String range) {
        var gift = access.require(id);
        return AudioResponse.from(media.content(gift.work(),gift.asset()), false, range);
    }

    @PostMapping("/api/songs/{id}/play")
    public ResponseEntity<Void> play(@PathVariable String id) {
        var gift = access.require(id);
        int updated = db.update("""
                UPDATE share_link SET playback_starts=playback_starts+1
                WHERE id=? AND active=TRUE AND expires_at>CURRENT_TIMESTAMP
                """, gift.id());
        if (updated == 0) throw missing();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/songs/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable String id) {
        var gift = access.require(id);
        if (gift.cover()==null) throw missing();
        return AudioResponse.cover(covers.content(gift.work(),gift.cover()));
    }
    @GetMapping(value="/api/songs/{id}/thumbnail.png",produces="image/png")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id, @RequestParam(required=false) String card) throws IOException {
        var gift = access.require(id);
        boolean xCard = "x".equals(card);
        byte[] bytes = null;
        if (gift.cover()!=null) {
            try {
                var cover = covers.content(gift.work(),gift.cover());
                try (var stream = cover.source().open(0)) {
                    bytes = xCard ? thumbnailBytes(stream, X_PLAYER_WIDTH, X_PLAYER_HEIGHT) : thumbnailBytes(stream);
                }
            } catch (com.qingfeng.music.common.exception.DomainException missingCover) {
                if (!"COVER_NOT_FOUND".equals(missingCover.code())) throw missingCover;
            } catch (IOException badImage) { /* Keep the brand fallback for unreadable artwork. */ }
        }
        if (bytes == null && xCard) {
            try (var stream = fallback.getInputStream()) {
                bytes = thumbnailBytes(stream, X_PLAYER_WIDTH, X_PLAYER_HEIGHT);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bytes == null ? fallback.getContentAsByteArray() : bytes);
    }

    static byte[] thumbnailBytes(InputStream stream) throws IOException {
        return thumbnailBytes(stream, 240, 240);
    }

    private static byte[] thumbnailBytes(InputStream stream, int width, int height) throws IOException {
        try (var input = ImageIO.createImageInputStream(stream)) {
            if (input == null) return null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            BufferedImage source;
            try {
                reader.setInput(input);
                var params = reader.getDefaultReadParam();
                int sampleSize = Math.max(320, Math.max(width, height));
                int step = Math.max(1, Math.max(reader.getWidth(0), reader.getHeight(0)) / sampleSize);
                params.setSourceSubsampling(step,step,0,0);
                source = reader.read(0,params);
            } finally { reader.dispose(); }
            int cropWidth = Math.min(source.getWidth(), Math.max(1, Math.round(source.getHeight() * (float) width / height)));
            int cropHeight = Math.min(source.getHeight(), Math.max(1, Math.round(source.getWidth() * (float) height / width)));
            BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = thumb.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source.getSubimage((source.getWidth() - cropWidth) / 2, (source.getHeight() - cropHeight) / 2,
                        cropWidth, cropHeight), 0, 0, width, height, null);
            } finally { g.dispose(); }
            var buffer = new ByteArrayOutputStream();
            ImageIO.write(thumb,"png",buffer);
            return buffer.toByteArray();
        }
    }
}
