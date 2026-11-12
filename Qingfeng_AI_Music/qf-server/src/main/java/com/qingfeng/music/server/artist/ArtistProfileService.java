package com.qingfeng.music.server.artist;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.api.SharedMediaAccess;
import com.qingfeng.music.server.security.LogtoIdentityService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile({"mysql", "test"})
public class ArtistProfileService {
    public record MyProfile(String publicId, String stageName, String tagline, String avatarKey,
                            boolean published, List<Release> songs) {}
    public record Release(String id, long version, String title, String workId, String assetId, String coverId) {}
    public record PublicProfile(String stageName, String tagline, String avatarKey, List<PublicSong> songs) {}
    public record PublicSong(String id, long version, String title, boolean hasCover) {}
    public record ReleaseSelection(boolean profileReady, boolean published, Release song) {}
    private record ProfileRow(String creatorId, String publicId, String stageName, String tagline,
                              String avatarKey, boolean published) {}

    private static final String PROFILE_COLUMNS = "creator_id,public_id,stage_name,tagline,avatar_key,published";
    private static final RowMapper<ProfileRow> PROFILE_ROW = (rs, row) -> new ProfileRow(
            rs.getString("creator_id"), rs.getString("public_id"), rs.getString("stage_name"),
            rs.getString("tagline"), rs.getString("avatar_key"), rs.getBoolean("published"));
    private static final RowMapper<Release> RELEASE_ROW = (rs, row) -> new Release(
            rs.getString("id"), rs.getLong("version"), rs.getString("title"), rs.getString("work_id"),
            rs.getString("asset_id"), rs.getString("cover_id"));
    private static final String PROFILE_RELEASES = """
            SELECT r.id,r.version,r.title,r.work_id,r.asset_id,r.cover_id FROM artist_song_release r
            JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
            WHERE r.creator_id=? AND r.show_on_profile=TRUE
            """;

    private final JdbcTemplate db;
    private final IdentityApi identity;
    private final LogtoIdentityService logto;
    private final MediaApi media;
    private final SharedMediaAccess audio;
    private final SharedCoverAccess covers;

    public ArtistProfileService(JdbcTemplate db, IdentityApi identity, LogtoIdentityService logto,
                                MediaApi media, SharedMediaAccess audio, SharedCoverAccess covers) {
        this.db = db;
        this.identity = identity;
        this.logto = logto;
        this.media = media;
        this.audio = audio;
        this.covers = covers;
    }

    public boolean hasAccount(String creator) {
        return identity.googleIdentity(creator).isPresent() || logto.profile(creator).isPresent();
    }

    @Transactional(readOnly = true)
    public MyProfile mine(String creator) {
        return findOwner(creator, false).map(this::ownerView)
                .orElseGet(() -> new MyProfile(null, "", "", null, false, List.of()));
    }

    @Transactional
    public MyProfile save(String creator, String name, String tagline, MultipartFile file, boolean removeAvatar) {
        String stageName = text(name, 30, "INVALID_ARTIST_NAME", "艺名最多 30 个字符。", false);
        String signature = text(tagline, 120, "INVALID_ARTIST_TAGLINE", "个性签名最多 120 个字符。", true);
        if (file != null && removeAvatar) {
            throw new DomainException("INVALID_ARTIST_AVATAR", "请重新选择头像后保存。");
        }
        byte[] avatar = file == null ? null : ArtistAvatar.jpeg(file);
        db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, creator);
        var existing = findOwner(creator, true);
        String key = avatar == null ? null : uuid();
        if (existing.isEmpty()) {
            db.update("""
                    INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,avatar_key,avatar_jpeg,published)
                    VALUES (?,?,?,?,?,?,FALSE)
                    """, creator, uuid(), stageName, signature, key, avatar);
        } else {
            if (existing.get().published()) requireStageName(stageName);
            db.update("UPDATE artist_profile SET stage_name=?,tagline=? WHERE creator_id=?", stageName, signature, creator);
            if (file != null || removeAvatar) {
                db.update("UPDATE artist_profile SET avatar_key=?,avatar_jpeg=? WHERE creator_id=?", key, avatar, creator);
            }
        }
        return ownerView(findOwner(creator, false).orElseThrow());
    }

    @Transactional
    public MyProfile setPublished(String creator, boolean publish) {
        var profile = requireOwnerProfile(creator);
        if (publish) requireStageName(profile.stageName());
        db.update("UPDATE artist_profile SET published=? WHERE creator_id=?", publish, creator);
        return ownerView(findOwner(creator, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ReleaseSelection selection(String creator, String work) {
        var profile = findOwner(creator, false);
        return new ReleaseSelection(profile.filter(row -> !row.stageName().isBlank()).isPresent(),
                profile.map(ProfileRow::published).orElse(false),
                ownerRelease(creator, work).orElse(null));
    }

    @Transactional
    public Release publishSong(String creator, String work, String assetId, String coverId, String title) {
        var profile = requireOwnerProfile(creator);
        requireStageName(profile.stageName());
        var source = media.locate(creator, work, assetId);
        if (source.size() <= 0) throw new DomainException("INVALID_ARTIST_AUDIO", "这首歌暂时无法试听。");
        if (coverId != null) covers.ref(work, coverId);
        String publicTitle = text(title, 100, "INVALID_ARTIST_SONG_TITLE", "歌名最多 100 个字符。", false);
        if (publicTitle.isBlank()) {
            throw new DomainException("INVALID_ARTIST_SONG_TITLE", "请先填写歌名。");
        }
        var previous = ownerRelease(creator, work);
        if (previous.filter(song -> song.assetId().equals(assetId) && song.title().equals(publicTitle)
                && Objects.equals(song.coverId(), coverId)).isPresent()) return previous.orElseThrow();

        var now = Timestamp.from(Instant.now());
        db.update("""
                INSERT INTO artist_song_release(id,creator_id,work_id,asset_id,cover_id,title,version,show_on_profile,published_at)
                VALUES (?,?,?,?,?,?,1,TRUE,?)
                ON DUPLICATE KEY UPDATE asset_id=?,cover_id=?,title=?,version=version+1,
                    published_at=CASE WHEN show_on_profile THEN published_at ELSE ? END,show_on_profile=TRUE
                """, uuid(), creator, work, assetId, coverId, publicTitle, now, assetId, coverId, publicTitle, now);
        return ownerRelease(creator, work).orElseThrow(ArtistProfileService::missing);
    }

    @Transactional
    public void removeSong(String creator, String work) {
        requireOwnerProfile(creator);
        db.update("""
                UPDATE artist_song_release SET show_on_profile=FALSE,version=version+1
                WHERE creator_id=? AND work_id=? AND show_on_profile=TRUE
                """, creator, work);
    }

    @Transactional(readOnly = true)
    public PublicProfile publishedProfile(String publicId) {
        var profile = db.query("SELECT " + PROFILE_COLUMNS + " FROM artist_profile WHERE public_id=? AND published=TRUE",
                PROFILE_ROW, publicId).stream().findFirst().orElseThrow(ArtistProfileService::missing);
        var songs = releases(profile.creatorId()).stream()
                .map(song -> new PublicSong(song.id(), song.version(), song.title(), song.coverId() != null)).toList();
        return new PublicProfile(profile.stageName(), profile.tagline(), profile.avatarKey(), songs);
    }

    public byte[] publicAvatar(String publicId, String key) {
        return avatar("public_id=? AND published=TRUE", publicId, key);
    }

    public byte[] ownerAvatar(String creator, String key) {
        return avatar("creator_id=?", creator, key);
    }

    public MediaApi.Content publicAudio(String publicId, String song, long version) {
        var release = publicRelease(publicId, song, version);
        var content = audio.content(release.workId(), release.assetId());
        String filename = "audio/wav".equals(content.contentType()) ? "song.wav" : "song.mp3";
        return new MediaApi.Content(content.size(), content.contentType(), filename, content.source());
    }

    public MediaApi.Content publicCover(String publicId, String song, long version) {
        var release = publicRelease(publicId, song, version);
        if (release.coverId() == null) throw missing();
        return covers.content(release.workId(), release.coverId());
    }

    private byte[] avatar(String ownerCondition, String identityKey, String avatarKey) {
        return db.query("SELECT avatar_jpeg FROM artist_profile WHERE " + ownerCondition + " AND avatar_key=?",
                (rs, row) -> rs.getBytes(1), identityKey, avatarKey).stream().findFirst()
                .orElseThrow(ArtistProfileService::missing);
    }

    private Release publicRelease(String publicId, String song, long version) {
        return db.query("""
                SELECT r.id,r.version,r.title,r.work_id,r.asset_id,r.cover_id FROM artist_song_release r
                JOIN artist_profile p ON p.creator_id=r.creator_id
                JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
                WHERE p.public_id=? AND p.published=TRUE AND r.id=? AND r.version=? AND r.show_on_profile=TRUE
                """, RELEASE_ROW, publicId, song, version).stream().findFirst()
                .orElseThrow(ArtistProfileService::missing);
    }

    private ProfileRow requireOwnerProfile(String creator) {
        return findOwner(creator, true).orElseThrow(() ->
                new DomainException("INVALID_ARTIST_NAME", "请先在我的资料中保存艺名。"));
    }

    private Optional<ProfileRow> findOwner(String creator, boolean lock) {
        return db.query("SELECT " + PROFILE_COLUMNS + " FROM artist_profile WHERE creator_id=?" + (lock ? " FOR UPDATE" : ""),
                PROFILE_ROW, creator).stream().findFirst();
    }

    private Optional<Release> ownerRelease(String creator, String work) {
        return db.query(PROFILE_RELEASES + " AND r.work_id=?", RELEASE_ROW, creator, work).stream().findFirst();
    }

    private List<Release> releases(String creator) {
        return db.query(PROFILE_RELEASES + " ORDER BY r.published_at DESC,r.id", RELEASE_ROW, creator);
    }

    private MyProfile ownerView(ProfileRow row) {
        return new MyProfile(row.publicId(), row.stageName(), row.tagline(), row.avatarKey(), row.published(),
                releases(row.creatorId()));
    }

    private static String text(String value, int max, String code, String message, boolean multiline) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.length() > max || trimmed.codePoints().anyMatch(c ->
                Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r')))) {
            throw new DomainException(code, message);
        }
        return trimmed;
    }

    private static void requireStageName(String name) {
        if (name.isBlank()) throw new DomainException("INVALID_ARTIST_NAME", "请先填写艺名。");
    }

    private static DomainException missing() {
        return new DomainException("ARTIST_NOT_FOUND", "该主页或作品暂不可访问。");
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
}
