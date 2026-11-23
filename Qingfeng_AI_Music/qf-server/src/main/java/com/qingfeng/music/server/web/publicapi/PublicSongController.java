package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.media.api.SharedMediaAccess;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.server.web.AudioResponse;
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
    private final SharedMediaAccess media;
    private final SharedCoverAccess covers;
    private final JdbcTemplate db;
    private final String origin;
    private final Resource template, fallback;
    public PublicSongController(SharedMediaAccess media, SharedCoverAccess covers, JdbcTemplate db,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin,
            @Value("classpath:/song.html") Resource template,
            @Value("classpath:/static/brand/share-default-cover.png") Resource fallback) {
        this.media = media;
        this.covers = covers;
        this.db = db;
        this.origin = origin.replaceAll("/+$", "");
        this.template = template;
        this.fallback = fallback;
    }

    public record Song(String title,String creator,String url,String image,String thumbnail,String audio) {}
    private record Grant(String id,String title,String creator,String work,String asset,String cover) {}
    private Grant grant(String id) {
        if (!id.matches("[A-Za-z0-9_-]{43}")) throw missing();
        return db.query("""
                SELECT s.id,s.title,COALESCE(a.stage_name,'匿名创作者'),s.work_id,s.asset_id,s.cover_id
                FROM share_link s JOIN music_work w ON w.id=s.work_id
                LEFT JOIN media_asset_annotation v ON v.asset_id=s.asset_id
                LEFT JOIN artist_profile a ON a.creator_id=w.owner_id AND a.published=TRUE
                WHERE s.public_song_id=? AND s.active=TRUE AND s.expires_at>CURRENT_TIMESTAMP AND w.deleted_at IS NULL AND v.deleted_at IS NULL
                ORDER BY s.created_at DESC,s.id DESC LIMIT 1
                """,(r,n)->new Grant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)),id)
                .stream().findFirst().orElseThrow(PublicSongController::missing);
    }
    private static org.springframework.web.server.ResponseStatusException missing() {
        return new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    private Song song(String id,Grant gift) {
        String base = origin + "/api/songs/" + id;
        return new Song(gift.title(),gift.creator(),origin+"/song/"+id,
                gift.cover()!=null ? base+"/cover" : origin+"/brand/share-default-cover.png",
                base+"/thumbnail.png",base+"/audio");
    }
    @GetMapping("/api/songs/{id}/public")
    public ResponseEntity<Song> info(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(song(id,grant(id)));
    }
    @GetMapping(value="/song/{id}", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> page(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) throws IOException {
        Grant gift = grant(id);
        Song song = song(id,gift);
        String page = template.getContentAsString(StandardCharsets.UTF_8);
        var fields = java.util.Map.of("title",song.title(),"creator",song.creator(),"url",song.url(),
                "image",song.image().substring(origin.length()),"thumbnail",song.thumbnail(),"audio",song.audio().substring(origin.length()),"description",DESCRIPTION);
        var slots = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(page);
        page = slots.replaceAll(m -> java.util.regex.Matcher.quoteReplacement(HtmlUtils.htmlEscape(fields.get(m.group(1)),"UTF-8")));
        if ("GET".equals(request.getMethod())) db.update("UPDATE share_link SET page_views=page_views+1 WHERE id=?",gift.id());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").body(page);
    }
    @GetMapping("/api/songs/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String id,@RequestHeader(value="Range",required=false) String range,jakarta.servlet.http.HttpServletRequest request) {
        var gift = grant(id);
        var response = AudioResponse.from(media.content(gift.work(),gift.asset()), false, range);
        if ("GET".equals(request.getMethod()) && response.getStatusCode().is2xxSuccessful() && (range==null || range.startsWith("bytes=0-")))
            db.update("UPDATE share_link SET playback_starts=playback_starts+1 WHERE id=?",gift.id());
        return response;
    }
    @GetMapping("/api/songs/{id}/cover")
    public ResponseEntity<Resource> cover(@PathVariable String id) {
        var gift = grant(id);
        if (gift.cover()==null) throw missing();
        return AudioResponse.from(covers.content(gift.work(),gift.cover()),false);
    }
    @GetMapping(value="/api/songs/{id}/thumbnail.png",produces="image/png")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String id) throws IOException {
        var gift = grant(id);
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

    private static byte[] thumbnailBytes(InputStream stream) throws IOException {
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
