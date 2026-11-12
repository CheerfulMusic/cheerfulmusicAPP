package com.qingfeng.music.server.avatar;

import com.qingfeng.music.cover.application.CoverFileStore;
import com.qingfeng.music.common.exception.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** One private avatar per creator. All image bytes are validated and re-encoded by the image store. */
@Service @Profile({"mysql", "test"})
public class AvatarService {
    public record View(String imageUrl, String version) {}
    public record Image(String key, long size) {}
    private record Row(String key, long size, String version) {}
    private record Saved(View view, String oldKey, boolean applied) {}
    private final JdbcTemplate db;
    private final CoverFileStore files;
    private final TransactionTemplate tx;

    public AvatarService(JdbcTemplate db, CoverFileStore files, PlatformTransactionManager manager) {
        this.db = db; this.files = files; this.tx = new TransactionTemplate(manager);
    }
    private Row row(String owner) {
        var rows = db.query("SELECT storage_key,byte_size,version FROM creator_avatar WHERE creator_id=?",
            (rs,n) -> new Row(rs.getString(1),rs.getLong(2),rs.getString(3)), owner);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private View view(Row row) {
        return row == null ? new View(null,null) : new View("/api/app/avatar/image",row.version());
    }
    public View current(String owner) { return view(row(owner)); }
    public Image image(String owner) {
        Row row = row(owner);
        if (row == null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return new Image(row.key(),row.size());
    }
    public InputStream open(Image image) throws IOException { return files.open(image.key()); }
    public View save(String owner, InputStream image) { return saveInternal(owner,null,image,false,UUID.randomUUID().toString()).orElseThrow(); }
    /** Compare version after taking the creator lock, so a late AI task cannot replace a newer avatar. */
    public Optional<View> saveIfVersion(String owner, String expectedVersion, InputStream image) { return saveInternal(owner,expectedVersion,image,true,UUID.randomUUID().toString()); }
    public Optional<View> saveIfVersion(String owner, String expectedVersion, InputStream image, String newVersion) {
        try { if (!UUID.fromString(newVersion).toString().equals(newVersion)) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid avatar version"); }
        return saveInternal(owner,expectedVersion,image,true,newVersion);
    }
    private Optional<View> saveInternal(String owner, String expected, InputStream image, boolean compare, String version) {
        CoverFileStore.Stored stored;
        try { stored = files.save(image); } catch (IOException e) { throw new DomainException("AVATAR_STORAGE_UNAVAILABLE","头像保存失败，请稍后重试"); }
        boolean applied = false;
        try {
            Saved result = tx.execute(status -> {
                var owners = db.queryForList("SELECT id FROM creator WHERE id=? FOR UPDATE",String.class,owner);
                if (owners.size() != 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
                Row previous = row(owner);
                if (compare && !Objects.equals(expected,previous == null ? null : previous.version())) return new Saved(null,null,false);
                if (previous == null) db.update("INSERT INTO creator_avatar (creator_id,storage_key,byte_size,version) VALUES (?,?,?,?)",owner,stored.key(),stored.size(),version);
                else db.update("UPDATE creator_avatar SET storage_key=?,byte_size=?,version=? WHERE creator_id=?",stored.key(),stored.size(),version,owner);
                return new Saved(view(new Row(stored.key(),stored.size(),version)),previous == null ? null : previous.key(),true);
            });
            if (result == null || !result.applied()) return Optional.empty();
            applied = true;
            if (result.oldKey() != null && !result.oldKey().equals(stored.key())) discardQuietly(result.oldKey());
            return Optional.of(result.view());
        } finally { if (!applied) discardQuietly(stored.key()); }
    }
    private void discardQuietly(String key) { try { files.discard(key); } catch (IOException | RuntimeException ignored) { /* retain inaccessible orphan rather than break committed update */ } }
}
