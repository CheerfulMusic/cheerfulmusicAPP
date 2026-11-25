package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.media.api.SharedMediaAccess;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.lyrics.application.LyricsFormatting;
import com.qingfeng.music.lyrics.playback.PlaybackLyrics;
import com.qingfeng.music.lyrics.playback.PlaybackLyricsRepository;
import com.qingfeng.music.server.web.AudioResponse;
import com.qingfeng.music.server.web.publicapi.PublicSongAccess.Grant;
import com.qingfeng.music.server.web.util.SongShareTitle;
import static com.qingfeng.music.server.web.publicapi.PublicSongAccess.missing;
import static java.util.Map.entry;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
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
    private final PublicSongAccess access;
    private final SharedMediaAccess media;
    private final SharedCoverAccess covers;
    private final PlaybackLyricsRepository timelines;
    private final JdbcTemplate db;
    private final String origin;
    private final Resource template, fallback;
    public PublicSongController(PublicSongAccess access, SharedMediaAccess media, SharedCoverAccess covers, PlaybackLyricsRepository timelines, JdbcTemplate db,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin,
            @Value("classpath:/song.html") Resource template,
            @Value("classpath:/static/brand/share-default-cover.png") Resource fallback) {
        this.access = access;
        this.media = media;
        this.covers = covers;
        this.timelines = timelines;
        this.db = db;
        this.origin = origin.replaceAll("/+$", "");
        this.template = template;
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
    public ResponseEntity<String> page(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) throws IOException {
        Grant gift = access.require(id);
        Song song = song(id,gift);
        String page = template.getContentAsString(StandardCharsets.UTF_8);
        Double duration = song.durationSeconds();
        boolean knownDuration = duration != null && Double.isFinite(duration) && duration > 0;
        var fields = java.util.Map.ofEntries(
                entry("title", song.title()), entry("creator", song.creator()),
                entry("siteName", SongShareTitle.SITE_NAME),
                entry("timelineTitle", SongShareTitle.forTimeline(song.title(), song.creator())),
                entry("url", song.url()), entry("image", song.image().substring(origin.length())),
                entry("thumbnail", song.thumbnail()), entry("audio", song.audio().substring(origin.length())),
                entry("artistId", song.artistId() == null ? "" : song.artistId()), entry("description", DESCRIPTION),
                entry("duration", knownDuration ? duration.toString() : ""),
                entry("durationLabel", knownDuration ? timeLabel(duration.longValue()) : "–:––"));
        String lyrics = lyricMarkup(gift,song.lyrics());
        var slots = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(page);
        page = slots.replaceAll(m -> java.util.regex.Matcher.quoteReplacement("lyricLines".equals(m.group(1))
                ? lyrics : HtmlUtils.htmlEscape(fields.get(m.group(1)),"UTF-8")));
        if ("GET".equals(request.getMethod())) db.update("UPDATE share_link SET page_views=page_views+1 WHERE id=?",gift.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(page);
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
        return AudioResponse.from(covers.content(gift.work(),gift.cover()),false);
    }
    @GetMapping(value="/api/songs/{id}/thumbnail.png",produces="image/png")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id) throws IOException {
        var gift = access.require(id);
        byte[] bytes = null;
        if (gift.cover()!=null) {
            try {
                var cover = covers.content(gift.work(),gift.cover());
                try (var stream = cover.source().open(0)) { bytes = thumbnailBytes(stream); }
            } catch (com.qingfeng.music.common.exception.DomainException missingCover) {
                if (!"COVER_NOT_FOUND".equals(missingCover.code())) throw missingCover;
            } catch (IOException badImage) { /* Keep the brand fallback for unreadable artwork. */ }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bytes==null?fallback.getContentAsByteArray():bytes);
    }

    static byte[] thumbnailBytes(InputStream stream) throws IOException {
        try (var input = ImageIO.createImageInputStream(stream)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            BufferedImage source;
            try {
                reader.setInput(input);
                var params = reader.getDefaultReadParam();
                int step = Math.max(1,Math.max(reader.getWidth(0),reader.getHeight(0))/320);
                params.setSourceSubsampling(step,step,0,0);
                source = reader.read(0,params);
            } finally { reader.dispose(); }
            int side = Math.min(source.getWidth(),source.getHeight());
            BufferedImage thumb = new BufferedImage(240,240,BufferedImage.TYPE_INT_RGB);
            var g = thumb.createGraphics();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(source.getSubimage((source.getWidth()-side)/2,(source.getHeight()-side)/2,side,side),0,0,240,240,null);
            } finally { g.dispose(); }
            var buffer = new ByteArrayOutputStream();
            ImageIO.write(thumb,"png",buffer);
            return buffer.toByteArray();
        }
    }
}
