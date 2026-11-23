package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.artist.ArtistProfileService;
import com.qingfeng.music.server.security.CreatorPrincipal;
import com.qingfeng.music.server.security.CreatorSessionFilter;
import com.qingfeng.music.server.web.publicapi.PublicSongAccess.Grant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile({"mysql","test"})
public class SongCommunityController {
    private final JdbcTemplate db;
    private final PublicSongAccess access;
    private final ArtistProfileService artists;
    private final IdentityApi identity;
    private static final int PAGE_SIZE = 20;

    public SongCommunityController(JdbcTemplate db, PublicSongAccess access, ArtistProfileService artists, IdentityApi identity) {
        this.db = db;
        this.access = access;
        this.artists = artists;
        this.identity = identity;
    }

    public record Comment(String id, String author, String text, Instant createdAt, long likes, boolean liked, boolean removable) {}
    public record Community(String story, long plays, long likes, long favorites, long followers, long commentCount,
                            boolean liked, boolean favorited, boolean following, List<Comment> comments, boolean hasMore) {}
    public record CommentText(@NotNull @Size(min=1,max=400) String text) {}
    public record StoryText(@NotNull @Size(max=1500) String text) {}
    public record Selection(boolean active) {}

    @GetMapping("/api/songs/{id}/community")
    @Transactional(readOnly=true)
    public ResponseEntity<Community> community(@PathVariable String id, @RequestParam(defaultValue="hot") String sort,
            @RequestParam(defaultValue="0") int page, HttpServletRequest request) {
        Grant g = access.require(id);
        if (page < 0 || page > 10000 || !List.of("hot","new").contains(sort)) throw badRequest();
        String token = CreatorSessionFilter.token(request);
        String viewer = token == null ? "" : identity.resolve(token).orElse("");
        String ordering = sort.equals("hot") ? "votes DESC,c.created_at DESC,c.id DESC" : "c.created_at DESC,c.id DESC";
        var comments = db.query("""
                SELECT c.id,COALESCE(a.stage_name,'听众'),c.body,c.created_at,
                       (SELECT COUNT(*) FROM listening_comment_like v WHERE v.comment_id=c.id) votes,
                       EXISTS (SELECT 1 FROM listening_comment_like v WHERE v.comment_id=c.id AND v.creator_id=?),
                       c.creator_id
                FROM listening_comment c LEFT JOIN artist_profile a ON a.creator_id=c.creator_id AND a.published=TRUE
                WHERE c.share_id=? ORDER BY """ + " " + ordering + " LIMIT ? OFFSET ?",
                (r,n) -> new Comment(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),
                        r.getLong(5),r.getBoolean(6),viewer.equals(r.getString(7)) || viewer.equals(g.owner())),
                viewer,g.id(),PAGE_SIZE+1,page*PAGE_SIZE);
        String story = storyBody(g.id());
        Community result = db.queryForObject("""
                SELECT (SELECT COUNT(*) FROM listening_reaction WHERE share_id=? AND kind='like'),
                       (SELECT COUNT(*) FROM listening_reaction WHERE share_id=? AND kind='favorite'),
                       (SELECT COUNT(*) FROM listening_artist_follow WHERE artist_id=?),
                       (SELECT COUNT(*) FROM listening_comment WHERE share_id=?),
                       EXISTS (SELECT 1 FROM listening_reaction WHERE share_id=? AND kind='like' AND creator_id=?),
                       EXISTS (SELECT 1 FROM listening_reaction WHERE share_id=? AND kind='favorite' AND creator_id=?),
                       EXISTS (SELECT 1 FROM listening_artist_follow WHERE artist_id=? AND creator_id=?)
                """, (r,n) -> new Community(story,g.plays(),r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),
                        r.getBoolean(5),r.getBoolean(6),r.getBoolean(7),comments.stream().limit(PAGE_SIZE).toList(),
                        comments.size()>PAGE_SIZE),g.id(),g.id(),g.artist(),g.id(),g.id(),viewer,g.id(),viewer,g.artist(),viewer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private String storyBody(String shareId) {
        return db.query("SELECT body FROM listening_story WHERE share_id=?", (r,n)->r.getString(1), shareId)
                .stream().findFirst().orElse("");
    }

    @GetMapping("/api/app/song-listening/{id}/story")
    public ResponseEntity<StoryText> ownerStory(@PathVariable String id, @AuthenticationPrincipal CreatorPrincipal viewer) {
        Grant g = access.require(id);
        if (!g.owner().equals(viewer.id())) throw PublicSongAccess.missing();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new StoryText(storyBody(g.id())));
    }

    @PutMapping("/api/app/song-listening/{id}/story")
    @Transactional
    public StoryText story(@PathVariable String id, @AuthenticationPrincipal CreatorPrincipal viewer, @Valid @RequestBody StoryText story) {
        Grant g = access.require(id);
        if (!g.owner().equals(viewer.id())) throw PublicSongAccess.missing();
        String text = story.text().strip();
        if (text.isEmpty()) db.update("DELETE FROM listening_story WHERE share_id=?",g.id());
        else db.update("INSERT INTO listening_story(share_id,body) VALUES (?,?) ON DUPLICATE KEY UPDATE body=?",g.id(),text,text);
        return new StoryText(text);
    }

    @PutMapping("/api/app/song-listening/{id}/reactions/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void reaction(@PathVariable String id, @PathVariable String kind, @AuthenticationPrincipal CreatorPrincipal viewer,
                         @RequestBody Selection selected) {
        Grant g = access.require(id);
        requireAccount(viewer);
        if ("follow".equals(kind)) {
            if (g.artist() == null) throw PublicSongAccess.missing();
            if (selected.active()) insertOnce("INSERT INTO listening_artist_follow(artist_id,creator_id) VALUES (?,?)",g.artist(),viewer.id());
            else db.update("DELETE FROM listening_artist_follow WHERE artist_id=? AND creator_id=?",g.artist(),viewer.id());
        } else {
            if (!List.of("like","favorite").contains(kind)) throw badRequest();
            if (selected.active()) insertOnce("INSERT INTO listening_reaction(share_id,kind,creator_id) VALUES (?,?,?)",g.id(),kind,viewer.id());
            else db.update("DELETE FROM listening_reaction WHERE share_id=? AND kind=? AND creator_id=?",g.id(),kind,viewer.id());
        }
    }

    @PutMapping("/api/app/song-listening/{id}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void comment(@PathVariable String id, @PathVariable UUID commentId, @AuthenticationPrincipal CreatorPrincipal viewer,
                        @Valid @RequestBody CommentText input) {
        Grant g = access.require(id);
        requireAccount(viewer);
        String text = input.text().strip();
        if (text.isEmpty()) throw badRequest();
        try {
            db.update("INSERT INTO listening_comment(id,share_id,creator_id,body,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
                    commentId.toString(),g.id(),viewer.id(),text);
        } catch (DuplicateKeyException duplicate) {
            var saved = db.query("SELECT body FROM listening_comment WHERE id=? AND share_id=? AND creator_id=? FOR UPDATE",
                    (r,n)->r.getString(1),commentId.toString(),g.id(),viewer.id());
            if (saved.isEmpty() || !saved.get(0).equals(text)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    @PutMapping("/api/app/song-listening/{id}/comments/{commentId}/like")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void likeComment(@PathVariable String id, @PathVariable UUID commentId, @AuthenticationPrincipal CreatorPrincipal viewer,
                            @RequestBody Selection selected) {
        Grant g = access.require(id);
        requireAccount(viewer);
        String comment = commentId.toString();
        if (db.query("SELECT id FROM listening_comment WHERE id=? AND share_id=? FOR UPDATE",
                (r,n)->r.getString(1),comment,g.id()).isEmpty()) throw PublicSongAccess.missing();
        if (selected.active()) insertOnce("INSERT INTO listening_comment_like(comment_id,creator_id) VALUES (?,?)",comment,viewer.id());
        else db.update("DELETE FROM listening_comment_like WHERE comment_id=? AND creator_id=?",comment,viewer.id());
    }

    @DeleteMapping("/api/app/song-listening/{id}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@PathVariable String id, @PathVariable UUID commentId, @AuthenticationPrincipal CreatorPrincipal viewer) {
        Grant g = access.require(id);
        int removed = db.update("DELETE FROM listening_comment WHERE id=? AND share_id=? AND (creator_id=? OR ?=?)",
                commentId.toString(),g.id(),viewer.id(),g.owner(),viewer.id());
        if (removed == 0) throw PublicSongAccess.missing();
    }

    private void requireAccount(CreatorPrincipal viewer) {
        if (!artists.hasAccount(viewer.id())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录音乐账号");
    }
    private void insertOnce(String sql, Object... values) {
        try { db.update(sql,values); } catch (DuplicateKeyException alreadySelected) { /* The requested state is active. */ }
    }
    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
