package com.qingfeng.music.server.artist;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile({"mysql","test"})
public class ArtistAlbumService {
    public record Track(String id, long version, String title, boolean hasCover) {}
    public record Album(String id, String title, String description, String coverKey, long version,
                        boolean published, List<Track> tracks) {}
    public record Edit(long version, String title, String description, boolean published, List<String> releases,
                       boolean removeCover, MultipartFile cover) {}
    private record Assets(String release, String asset) {}
    private final JdbcTemplate db;
    private final ArtistProfileService profiles;

    public ArtistAlbumService(JdbcTemplate db, ArtistProfileService profiles) { this.db=db; this.profiles=profiles; }

    private static final String VISIBLE_RELEASES = """
        FROM artist_song_release r JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id
        LEFT JOIN media_asset_annotation v ON v.asset_id=r.asset_id
        WHERE r.show_on_profile=TRUE AND w.deleted_at IS NULL AND v.deleted_at IS NULL
        """;

    public List<Album> mine(String owner) {
        account(owner);
        return list("b.creator_id=?",owner);
    }
    public List<Album> published(String artist) {
        return list("p.public_id=? AND p.published=TRUE AND b.published=TRUE AND t.release_id IS NOT NULL",artist);
    }
    public Album publicAlbum(String artist, String album) {
        return list("p.public_id=? AND b.id=? AND p.published=TRUE AND b.published=TRUE AND t.release_id IS NOT NULL",artist,album)
                .stream().findFirst().orElseThrow(ArtistAlbumService::missing);
    }
    private List<Album> list(String condition, Object... values) {
        String sql = """
            SELECT b.id,b.title,b.description,b.cover_key,b.version,b.published,
                   t.release_id,t.version,t.title,t.cover_id
            FROM listening_album b LEFT JOIN artist_profile p ON p.creator_id=b.creator_id
            LEFT JOIN (
              SELECT x.album_id,x.track_no,r.id AS release_id,r.version,r.title,r.cover_id,r.creator_id
              FROM listening_album_track x JOIN
            """ + "(SELECT r.* " + VISIBLE_RELEASES + ") r ON r.id=x.release_id AND r.asset_id=x.asset_id" +
            ") t ON t.album_id=b.id AND t.creator_id=b.creator_id WHERE " + condition +
            " ORDER BY b.updated_at DESC,b.id,t.track_no";
        return db.query(sql,(ResultSetExtractor<List<Album>>) rows -> {
            var albums = new LinkedHashMap<String,Album>();
            while (rows.next()) {
                String id=rows.getString(1);
                var album=albums.get(id);
                if (album==null) {
                    album=new Album(id,rows.getString(2),rows.getString(3),rows.getString(4),rows.getLong(5),
                            rows.getBoolean(6),new ArrayList<>());
                    albums.put(id,album);
                }
                if (rows.getString(7)!=null) album.tracks().add(new Track(rows.getString(7),rows.getLong(8),
                        rows.getString(9),rows.getString(10)!=null));
            }
            return List.copyOf(albums.values());
        },values);
    }

    @Transactional
    public Album save(String owner, String album, Edit edit) {
        account(owner);
        String title=edit.title().strip(), description=edit.description().strip();
        List<String> ids=edit.releases();
        if (title.isEmpty() || title.length()>100 || description.length()>1200 || edit.version()<0 ||
                ids.size()>100 || new HashSet<>(ids).size()!=ids.size() || (edit.cover()!=null && edit.removeCover()))
            throw bad("请检查专辑名称、封面和曲目。");
        byte[] cover=edit.cover()==null ? null : ArtistImage.jpeg(edit.cover(), ArtistImage.Kind.ALBUM);
        lockOwner(owner);
        var versions=db.query("SELECT version FROM listening_album WHERE id=? AND creator_id=? FOR UPDATE",
                (r,n)->r.getLong(1),album,owner);
        if (!versions.isEmpty() && versions.get(0)!=edit.version()) throw conflict();
        if (versions.isEmpty() && edit.version()!=0) throw missing();
        if (edit.published() && (ids.isEmpty() || db.queryForObject(
                "SELECT COUNT(*) FROM artist_profile WHERE creator_id=? AND published=TRUE",Integer.class,owner)==0))
            throw bad("公开专辑前请先公开音乐人主页，并至少选择一首主页作品。");

        var available = new LinkedHashMap<String,Assets>();
        if (!ids.isEmpty()) {
            db.query("SELECT r.id,r.asset_id " + VISIBLE_RELEASES + " AND r.creator_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> available.put(row.getString(1),
                        new Assets(row.getString(1),row.getString(2))), owner);
            if (!available.keySet().containsAll(ids)) throw bad("有曲目已下架或不属于你的主页，请重新选曲。");
        }
        if (versions.isEmpty()) {
            try {
                db.update("INSERT INTO listening_album(id,creator_id,title,description,published,version,updated_at) VALUES(?,?,?,?,?,1,CURRENT_TIMESTAMP)",
                        album,owner,title,description,edit.published());
            } catch (org.springframework.dao.DuplicateKeyException collision) { throw conflict(); }
        } else {
            db.update("UPDATE listening_album SET title=?,description=?,published=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    title,description,edit.published(),album);
            db.update("DELETE FROM listening_album_track WHERE album_id=?",album);
        }
        if (cover!=null || edit.removeCover()) db.update("UPDATE listening_album SET cover_key=?,cover_jpeg=? WHERE id=?",
                cover==null?null:UUID.randomUUID().toString(),cover,album);
        int position=1;
        for (String id:ids) {
            var track=available.get(id);
            db.update("INSERT INTO listening_album_track(album_id,release_id,asset_id,track_no) VALUES(?,?,?,?)",
                    album,track.release(),track.asset(),position++);
        }
        return list("b.id=? AND b.creator_id=?",album,owner).get(0);
    }

    @Transactional
    public void delete(String owner, String album, long version) {
        account(owner);
        lockOwner(owner);
        var found=db.query("SELECT version FROM listening_album WHERE id=? AND creator_id=?",
                (r,n)->r.getLong(1),album,owner).stream().findFirst().orElseThrow(ArtistAlbumService::missing);
        if (found!=version) throw conflict();
        db.update("DELETE FROM listening_album WHERE id=? AND creator_id=?",album,owner);
    }
    public byte[] ownerCover(String owner, String album, String key) {
        account(owner);
        return cover(album,key,owner);
    }
    @Transactional(readOnly=true)
    public byte[] publicCover(String artist, String album, String key) {
        if (!key.equals(publicAlbum(artist,album).coverKey())) throw missing();
        return cover(album,key,null);
    }
    private byte[] cover(String album, String key, String owner) {
        String sql="SELECT cover_jpeg FROM listening_album WHERE id=? AND cover_key=?";
        Object[] values=owner==null ? new Object[]{album,key} : new Object[]{album,key,owner};
        return db.query(sql+(owner==null?"":" AND creator_id=?"),(r,n)->r.getBytes(1),values)
                .stream().findFirst().orElseThrow(ArtistAlbumService::missing);
    }
    private void account(String owner) {
        if (!profiles.hasAccount(owner)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"请先登录音乐账号。");
    }
    private void lockOwner(String owner) { db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE",String.class,owner); }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"此专辑已更新，请重新载入。"); }
}
