package com.qingfeng.music.server.web.publicapi;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile({"mysql","test"})
class PublicSongAccess {
    record Grant(String id, String title, String creator, String work, String asset, String cover,
                 String owner, String artist, long plays) {}
    private final JdbcTemplate db;

    PublicSongAccess(JdbcTemplate db) { this.db = db; }

    Grant require(String publicId) {
        if (!publicId.matches("[A-Za-z0-9_-]{43}")) throw missing();
        return db.query("""
                SELECT s.id,s.title,COALESCE(a.stage_name,'匿名创作者'),s.work_id,s.asset_id,s.cover_id,
                       w.owner_id,a.public_id,s.playback_starts
                FROM share_link s JOIN music_work w ON w.id=s.work_id
                LEFT JOIN media_asset_annotation v ON v.asset_id=s.asset_id
                LEFT JOIN artist_profile a ON a.creator_id=w.owner_id AND a.published=TRUE
                WHERE s.public_song_id=? AND s.active=TRUE AND s.expires_at>CURRENT_TIMESTAMP
                  AND w.deleted_at IS NULL AND v.deleted_at IS NULL
                ORDER BY s.created_at DESC,s.id DESC LIMIT 1
                """, (r,n) -> new Grant(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                    r.getString(6),r.getString(7),r.getString(8),r.getLong(9)), publicId)
                .stream().findFirst().orElseThrow(PublicSongAccess::missing);
    }

    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
