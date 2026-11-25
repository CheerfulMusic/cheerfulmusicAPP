package com.qingfeng.music.server.artist;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile({"mysql", "test"})
public class DiscoverSongService {
    private static final int PAGE_SIZE = 20;
    private final ArtistProfileService profiles;
    private final JdbcTemplate db;
    private volatile boolean communityReady;

    public DiscoverSongService(ArtistProfileService profiles, JdbcTemplate db) {
        this.profiles = profiles;
        this.db = db;
    }

    public record Likes(long count, boolean liked) {}
    public record Comment(String id, String author, String text, Instant createdAt, boolean removable) {}
    public record Comments(List<Comment> items, String nextCursor) {}
    public record CommentCount(long count) {}
    public record PostedComment(Comment comment, long count) {}
    public record Description(String text) {}
    public record PublicPage(ArtistProfileService.CatalogSong song, String description) {}
    public record Detail(ArtistProfileService.DiscoverSong release, Double durationSeconds, String lyrics, String description,
                         boolean signedIn, boolean communityAvailable, boolean canEditDescription,
                         Likes likes, long commentCount, Comments comments) {}

    private record CommentRow(String id, String creator, String text, Instant createdAt) {}
    private record Cursor(Instant createdAt, String id) {
        static Cursor parse(String value) {
            if (value.isEmpty()) return null;
            var parts = value.split("\\|", -1);
            if (parts.length != 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            try {
                return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]).toString());
            } catch (DateTimeParseException | IllegalArgumentException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
        }
    }

    @Transactional(readOnly = true)
    public Detail detail(String song, long version, String viewer) {
        var recording = profiles.discoverRecording(song, version);
        boolean signedIn = viewer != null && profiles.hasAccount(viewer);
        String lyrics = profiles.discoverLyrics(recording);
        if (!communityAvailable()) {
            return new Detail(recording.release(), recording.durationSeconds(), lyrics, "", signedIn, false, false,
                    new Likes(0, false), 0, new Comments(List.of(), null));
        }
        return new Detail(recording.release(), recording.durationSeconds(), lyrics, publishedDescription(song, version),
                signedIn, true, signedIn && recording.owner().equals(viewer), likes(song, version, viewer), commentCount(song, version),
                readComments(song, version, viewer, recording.owner(), null));
    }

    @Transactional(readOnly = true)
    public Optional<PublicPage> publicPage(String song, long version) {
        return profiles.catalogSong(song, version).map(publication ->
                new PublicPage(publication, communityAvailable() ? publishedDescription(song, version) : ""));
    }

    private String publishedDescription(String song, long version) {
        return db.query("SELECT body FROM discover_song_description WHERE release_id=? AND release_version=?",
                (rs, row) -> rs.getString(1), song, version).stream().findFirst().orElse("");
    }

    @Transactional(readOnly = true)
    public Comments comments(String song, long version, String viewer, String after) {
        var recording = profiles.discoverRecording(song, version);
        requireCommunity();
        return readComments(song, version, viewer, recording.owner(), Cursor.parse(after));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Likes like(String song, long version, String viewer, boolean active) {
        lockRecording(song, version, viewer);
        if (active) {
            db.update("""
                    INSERT INTO discover_song_like(release_id,release_version,creator_id) VALUES (?,?,?)
                    ON DUPLICATE KEY UPDATE creator_id=creator_id
                    """, song, version, viewer);
        } else {
            db.update("DELETE FROM discover_song_like WHERE release_id=? AND release_version=? AND creator_id=?",
                    song, version, viewer);
        }
        return likes(song, version, viewer);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PostedComment postComment(String song, long version, String viewer, String id, String input) {
        var recording = lockRecording(song, version, viewer);
        String text = input.strip();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            db.update("""
                    INSERT INTO discover_song_comment(id,release_id,release_version,creator_id,body,created_at)
                    VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, id, song, version, viewer, text);
        } catch (DuplicateKeyException retry) {
            // Read the original row below and verify its author and payload before accepting a retry.
        }
        var row = db.query("""
                SELECT id,creator_id,body,created_at FROM discover_song_comment
                WHERE id=? AND release_id=? AND release_version=? AND creator_id=? FOR UPDATE
                """, DiscoverSongService::commentRow, id, song, version, viewer).stream().findFirst();
        if (row.isEmpty() || !row.get().text().equals(text)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var item = toComment(row.get(), names(List.of(row.get())), viewer, recording.owner());
        return new PostedComment(item, commentCount(song, version));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentCount deleteComment(String song, long version, String viewer, String id) {
        var recording = lockRecording(song, version, viewer);
        int removed = db.update("""
                DELETE FROM discover_song_comment WHERE id=? AND release_id=? AND release_version=? AND (creator_id=? OR ?=?)
                """, id, song, version, viewer, viewer, recording.owner());
        if (removed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return new CommentCount(commentCount(song, version));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Description description(String song, long version, String viewer, String input) {
        var recording = lockRecording(song, version, viewer);
        if (!recording.owner().equals(viewer)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String text = input.strip();
        if (text.isEmpty()) {
            db.update("DELETE FROM discover_song_description WHERE release_id=? AND release_version=?", song, version);
        } else {
            db.update("""
                    INSERT INTO discover_song_description(release_id,release_version,body) VALUES (?,?,?)
                    ON DUPLICATE KEY UPDATE body=?
                    """, song, version, text, text);
        }
        return new Description(text);
    }

    private ArtistProfileService.DiscoverRecording lockRecording(String song, long version, String viewer) {
        if (viewer == null || !profiles.hasAccount(viewer)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        db.queryForList("SELECT id FROM artist_song_release WHERE id=? FOR UPDATE", song);
        var recording = profiles.discoverRecording(song, version);
        requireCommunity();
        return recording;
    }

    private void requireCommunity() {
        if (!communityAvailable()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private boolean communityAvailable() {
        if (communityReady) return true;
        try {
            db.queryForObject("""
                    SELECT COUNT(*) FROM discover_song_description,discover_song_like,discover_song_comment WHERE 1=0
                    """, Long.class);
        } catch (BadSqlGrammarException failure) {
            String state = failure.getSQLException().getSQLState();
            if ("42S02".equals(state) || "42S04".equals(state)) return false;
            throw failure;
        }
        communityReady = true;
        return true;
    }

    private Likes likes(String song, long version, String viewer) {
        return db.queryForObject("""
                SELECT COUNT(*),COUNT(CASE WHEN creator_id=? THEN 1 END)
                FROM discover_song_like WHERE release_id=? AND release_version=?
                """, (rs, row) -> new Likes(rs.getLong(1), rs.getLong(2) != 0), viewer, song, version);
    }

    private long commentCount(String song, long version) {
        return db.queryForObject("SELECT COUNT(*) FROM discover_song_comment WHERE release_id=? AND release_version=?",
                Long.class, song, version);
    }

    private Comments readComments(String song, long version, String viewer, String owner, Cursor cursor) {
        String sql = "SELECT id,creator_id,body,created_at FROM discover_song_comment WHERE release_id=? AND release_version=?";
        String order = " ORDER BY created_at DESC,id DESC LIMIT ?";
        List<CommentRow> rows;
        if (cursor == null) {
            rows = db.query(sql + order, DiscoverSongService::commentRow, song, version, PAGE_SIZE + 1);
        } else {
            var timestamp = Timestamp.from(cursor.createdAt());
            rows = db.query(sql + " AND (created_at < ? OR (created_at = ? AND id < ?))" + order,
                    DiscoverSongService::commentRow, song, version, timestamp, timestamp, cursor.id(), PAGE_SIZE + 1);
        }
        var page = rows.subList(0, Math.min(rows.size(), PAGE_SIZE));
        var authors = names(page);
        var items = page.stream().map(row -> toComment(row, authors, viewer, owner)).toList();
        if (rows.size() <= PAGE_SIZE) return new Comments(items, null);
        var last = page.get(PAGE_SIZE - 1);
        return new Comments(items, last.createdAt() + "|" + last.id());
    }

    private Map<String, String> names(List<CommentRow> comments) {
        var creators = comments.stream().map(CommentRow::creator).distinct().toList();
        if (creators.isEmpty()) return Map.of();
        var result = new HashMap<String, String>();
        String placeholders = String.join(",", Collections.nCopies(creators.size(), "?"));
        db.query("SELECT creator_id,stage_name FROM artist_profile WHERE published=TRUE AND creator_id IN (" + placeholders + ")",
                rs -> { result.put(rs.getString(1), rs.getString(2)); }, creators.toArray());
        return result;
    }

    private static CommentRow commentRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new CommentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant());
    }

    private static Comment toComment(CommentRow row, Map<String, String> names, String viewer, String owner) {
        return new Comment(row.id(), names.getOrDefault(row.creator(), ""), row.text(), row.createdAt(),
                row.creator().equals(viewer) || owner.equals(viewer));
    }
}
