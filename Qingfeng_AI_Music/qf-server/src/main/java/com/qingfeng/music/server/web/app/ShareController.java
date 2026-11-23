package com.qingfeng.music.server.web.app;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.media.api.MediaApi;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.web.util.SongShareTitle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile({"mysql","test"})
@RequestMapping("/api/app/works/{work}/shares")
public class ShareController {
 private final SharingApi sharing;
 private final MediaApi media;
 private final JdbcTemplate db;
 public ShareController(SharingApi sharing, MediaApi media, JdbcTemplate db) {
     this.sharing = sharing;
     this.media = media;
     this.db = db;
 }
 public record Request(@Pattern(regexp="\\S+") String revisionId,@NotBlank String assetId,@Size(max=100) String workTitle, Boolean publicSong){}
 public record Result(String id,String token,java.time.Instant expiresAt,String songId){}
 @org.springframework.transaction.annotation.Transactional
 @PostMapping public Result create(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work,@Valid @RequestBody Request request){
     var share = request.workTitle()==null ? sharing.create(creator.id(),work,request.revisionId(),request.assetId())
             : sharing.createWithWorkTitle(creator.id(),work,request.revisionId(),request.assetId(),request.workTitle());
     String songId = Boolean.TRUE.equals(request.publicSong()) ? sharing.enablePublicSong(share,request.assetId()) : null;
     return new Result(share.id(),share.token(),share.expiresAt(),songId);
 }

 public record Card(String songId, String title, String lyrics, boolean hasCover, Instant expiresAt, String timelineTitle) {}
 public record ShareStatus(String status, Card card, long views, long plays) {}

 @GetMapping("/{asset}")
 public ResponseEntity<ShareStatus> status(@AuthenticationPrincipal CreatorPrincipal creator,
         @PathVariable String work, @PathVariable String asset) {
     media.get(creator.id(), work, asset);
     var latest = db.query("""
         SELECT public_song_id, title, lyrics, cover_id, expires_at,
                COALESCE((SELECT stage_name FROM artist_profile WHERE creator_id=? AND published=TRUE),'匿名创作者') AS public_artist,
                CASE WHEN active=FALSE THEN 'REVOKED' WHEN expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED'
                     ELSE 'SHAREABLE' END AS share_status,
                SUM(page_views) OVER () AS total_views, SUM(playback_starts) OVER () AS total_plays
         FROM share_link
         WHERE work_id=? AND asset_id=? AND public_song_id IS NOT NULL
         ORDER BY CASE WHEN active=TRUE AND expires_at>CURRENT_TIMESTAMP THEN 0 ELSE 1 END, created_at DESC, id DESC
         LIMIT 1
         """, (row, n) -> {
             String status = row.getString("share_status");
             Card card = "SHAREABLE".equals(status) ? new Card(row.getString("public_song_id"), row.getString("title"),
                     row.getString("lyrics"), row.getString("cover_id") != null, row.getTimestamp("expires_at").toInstant(),
                     SongShareTitle.forTimeline(row.getString("title"), row.getString("public_artist"))) : null;
             return new ShareStatus(status, card, row.getLong("total_views"), row.getLong("total_plays"));
         }, creator.id(), work, asset);
     return ResponseEntity.ok().cacheControl(CacheControl.noStore())
             .body(latest.isEmpty() ? new ShareStatus("PRIVATE", null, 0, 0) : latest.get(0));
 }

 @DeleteMapping("/{asset}")
 public void revokeVersion(@AuthenticationPrincipal CreatorPrincipal creator, @PathVariable String work, @PathVariable String asset) {
     media.get(creator.id(), work, asset);
     db.update("""
         UPDATE share_link SET active=FALSE
         WHERE work_id=? AND asset_id=? AND public_song_id IS NOT NULL AND active=TRUE
         """, work, asset);
 }

 @DeleteMapping public void revoke(@AuthenticationPrincipal CreatorPrincipal creator,@PathVariable String work){sharing.revokeAll(creator.id(),work);}
}
