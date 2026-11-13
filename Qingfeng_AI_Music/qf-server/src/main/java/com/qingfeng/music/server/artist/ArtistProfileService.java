package com.qingfeng.music.server.artist;

import static com.qingfeng.music.server.artist.ArtistImage.Kind.AVATAR;
import static com.qingfeng.music.server.artist.ArtistImage.Kind.COVER;

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
                            String coverKey, String featuredReleaseId, boolean published, List<Release> songs) {}
    public record Release(String id, long version, String title, String workId, String assetId, String coverId,
                          boolean onDiscover) {}
    public record PublicProfile(String stageName, String tagline, String avatarKey, String coverKey,
                                String featuredReleaseId, List<PublicSong> songs) {}
    public record PublicSong(String id, long version, String title, boolean hasCover) {}
    public record ReleaseSelection(boolean profileReady, boolean published, String publicId, String stageName, Release song) {}
    public record DiscoverSong(String artistId, String stageName, PublicSong song, Instant publishedAt,
                               String genre, long plays) {}
    public record DiscoverArtist(String artistId, String stageName, String avatarKey, long songs, long plays) {}
    public record DiscoverPage(List<DiscoverSong> songs, boolean hasMore, List<DiscoverArtist> artists) {}
    private record ProfileRow(String creatorId, String publicId, String stageName, String tagline,
                              String avatarKey, String coverKey, String featuredReleaseId, boolean published) {}

    private static final String PROFILE_COLUMNS =
            "creator_id,public_id,stage_name,tagline,avatar_key,cover_key,featured_release_id,published";
    private static final RowMapper<ProfileRow> PROFILE_ROW = (rs, row) -> new ProfileRow(
            rs.getString("creator_id"), rs.getString("public_id"), rs.getString("stage_name"),
            rs.getString("tagline"), rs.getString("avatar_key"), rs.getString("cover_key"),
            rs.getString("featured_release_id"), rs.getBoolean("published"));
    private static final RowMapper<Release> RELEASE_ROW = (rs, row) -> new Release(
            rs.getString("id"), rs.getLong("version"), rs.getString("title"), rs.getString("work_id"),
            rs.getString("asset_id"), rs.getString("cover_id"), rs.getBoolean("on_discover"));
    private static final String PROFILE_RELEASES = """
            SELECT r.id,r.version,r.title,r.work_id,r.asset_id,r.cover_id,
                (r.discover_published_at IS NOT NULL) AS on_discover FROM artist_song_release r
            JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
            WHERE r.creator_id=? AND r.show_on_profile=TRUE
            """;
    private static final String DISCOVER_RELEASES = """
            FROM artist_song_release r
            JOIN artist_profile p ON p.creator_id=r.creator_id AND p.published=TRUE
            JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
            LEFT JOIN hot_song_metric m ON m.track_id=CONCAT('discover:',r.id)
            WHERE r.show_on_profile=TRUE AND r.discover_published_at IS NOT NULL
            """;
    // Publish only known genres. The original style text can contain private creation instructions.
    private static final String DISCOVER_GENRE = """
            CASE
              WHEN w.style LIKE '%民谣%' OR LOWER(w.style) LIKE '%folk%' THEN 'folk'
              WHEN w.style LIKE '%古风%' OR w.style LIKE '%国风%' THEN 'guofeng'
              WHEN w.style LIKE '%摇滚%' OR LOWER(w.style) LIKE '%rock%' THEN 'rock'
              WHEN w.style LIKE '%电子%' OR LOWER(w.style) LIKE '%electronic%' THEN 'electronic'
              WHEN w.style LIKE '%纯音乐%' OR LOWER(w.style) LIKE '%instrumental%' THEN 'instrumental'
              WHEN w.style LIKE '%流行%' OR LOWER(w.style) LIKE '%pop%' THEN 'pop'
              ELSE 'other'
            END
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
                .orElseGet(() -> new MyProfile(null, "", "", null, null, null, false, List.of()));
    }

    @Transactional
    public MyProfile save(String creator, String name, String tagline, MultipartFile avatarFile, boolean removeAvatar,
                          MultipartFile coverFile, boolean removeCover, String featuredReleaseId) {
        String stageName = text(name, 30, "INVALID_ARTIST_NAME", "艺名最多 30 个字符。", false);
        String signature = text(tagline, 120, "INVALID_ARTIST_TAGLINE", "个性签名最多 120 个字符。", true);
        if (avatarFile != null && removeAvatar) throw AVATAR.invalid("请重新选择头像后保存。");
        if (coverFile != null && removeCover) throw COVER.invalid("请重新选择封面后保存。");
        byte[] avatar = avatarFile == null ? null : ArtistImage.jpeg(avatarFile, AVATAR);
        byte[] cover = coverFile == null ? null : ArtistImage.jpeg(coverFile, COVER);
        db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, creator);
        var existing = findOwner(creator, true);
        String featured = requestedFeature(creator, featuredReleaseId, existing.map(ProfileRow::featuredReleaseId).orElse(null));
        if (existing.isEmpty()) {
            db.update("""
                    INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published)
                    VALUES (?,?,?,?,FALSE)
                    """, creator, uuid(), stageName, signature);
        } else {
            if (existing.get().published()) requireStageName(stageName);
            db.update("UPDATE artist_profile SET stage_name=?,tagline=?,featured_release_id=? WHERE creator_id=?",
                    stageName, signature, featured, creator);
        }
        if (avatarFile != null || removeAvatar) updateImage(creator, AVATAR, avatar);
        if (coverFile != null || removeCover) updateImage(creator, COVER, cover);
        return ownerView(findOwner(creator, false).orElseThrow());
    }

    @Transactional
    public MyProfile setPublished(String creator, boolean publish) {
        var profile = requireOwnerProfile(creator);
        if (publish) requireStageName(profile.stageName());
        db.update("UPDATE artist_profile SET published=? WHERE creator_id=?", publish, creator);
        if (!publish) {
            db.update("""
                    UPDATE artist_song_release SET discover_published_at=NULL,discover_featured=FALSE
                    WHERE creator_id=? AND discover_published_at IS NOT NULL
                    """, creator);
        }
        return ownerView(findOwner(creator, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    public ReleaseSelection selection(String creator, String work) {
        var profile = findOwner(creator, false);
        return new ReleaseSelection(profile.filter(row -> !row.stageName().isBlank()).isPresent(),
                profile.map(ProfileRow::published).orElse(false),
                profile.map(ProfileRow::publicId).orElse(null), profile.map(ProfileRow::stageName).orElse(""),
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
                    published_at=CASE WHEN show_on_profile THEN published_at ELSE ? END,show_on_profile=TRUE,
                    discover_published_at=NULL,discover_featured=FALSE
                """, uuid(), creator, work, assetId, coverId, publicTitle, now, assetId, coverId, publicTitle, now);
        return ownerRelease(creator, work).orElseThrow(ArtistProfileService::missing);
    }

    @Transactional
    public void removeSong(String creator, String work) {
        var profile = requireOwnerProfile(creator);
        db.update("""
                UPDATE artist_song_release SET show_on_profile=FALSE,version=version+1,
                    discover_published_at=NULL,discover_featured=FALSE
                WHERE creator_id=? AND work_id=? AND show_on_profile=TRUE
                """, creator, work);
        if (profile.featuredReleaseId() != null) {
            db.update("""
                    UPDATE artist_profile SET featured_release_id=NULL WHERE creator_id=? AND featured_release_id=(
                        SELECT id FROM artist_song_release WHERE creator_id=? AND work_id=?
                    )
                    """, creator, creator, work);
        }
    }

    @Transactional
    public Release publishToDiscover(String creator, String work, long version) {
        var profile = requireOwnerProfile(creator);
        if (!profile.published()) {
            throw new DomainException("ARTIST_HOME_REQUIRED", "请先公开歌手主页，再发布到发现。");
        }
        var song = ownerRelease(creator, work).orElseThrow(ArtistProfileService::missing);
        if (song.version() != version) {
            throw new DomainException("ARTIST_RELEASE_CHANGED", "主页上的歌曲版本已更新，请重新确认后发布。");
        }
        if (song.onDiscover()) return song;
        db.update("UPDATE artist_song_release SET discover_published_at=? WHERE id=?",
                Timestamp.from(Instant.now()), song.id());
        return ownerRelease(creator, work).orElseThrow(ArtistProfileService::missing);
    }

    @Transactional
    public MyProfile withdrawFromDiscover(String creator, String work) {
        requireOwnerProfile(creator);
        var song = ownerRelease(creator, work).orElseThrow(ArtistProfileService::missing);
        db.update("UPDATE artist_song_release SET discover_published_at=NULL,discover_featured=FALSE WHERE id=?", song.id());
        return ownerView(findOwner(creator, false).orElseThrow());
    }

    @Transactional
    public void setDiscoverFeatured(String releaseId, boolean featured) {
        int updated = db.update("""
                UPDATE artist_song_release SET discover_featured=?
                WHERE id=? AND discover_published_at IS NOT NULL AND show_on_profile=TRUE
                """, featured, releaseId);
        if (updated == 0) throw missing();
    }

    @Transactional(readOnly = true)
    public DiscoverPage discover(boolean featured, boolean popular, String genre, String query, int offset) {
        int pageSize = 24;
        String keyword = "%" + query.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String order = popular ? " ORDER BY plays DESC, r.discover_published_at DESC,r.id DESC"
                : " ORDER BY r.discover_published_at DESC,r.id DESC";
        var songs = db.query("""
                SELECT p.public_id,p.stage_name,r.id,r.version,r.title,r.cover_id,r.discover_published_at,
                       COALESCE(m.plays,0) AS plays,
                """ + DISCOVER_GENRE + " AS genre " + DISCOVER_RELEASES
                + (featured ? " AND r.discover_featured=TRUE" : "") + """
                 AND (LOWER(r.title) LIKE ? ESCAPE '!' OR LOWER(p.stage_name) LIKE ? ESCAPE '!')
                 AND (?='' OR
                """ + DISCOVER_GENRE + "=?)" + order + " LIMIT ? OFFSET ?",
                (rs, row) -> new DiscoverSong(rs.getString("public_id"), rs.getString("stage_name"),
                        new PublicSong(rs.getString("id"), rs.getLong("version"), rs.getString("title"),
                                rs.getString("cover_id") != null), rs.getTimestamp("discover_published_at").toInstant(),
                        rs.getString("genre"), rs.getLong("plays")), keyword, keyword, genre, genre, pageSize + 1, offset);
        var artists = offset == 0 ? db.query("""
                SELECT p.public_id,p.stage_name,p.avatar_key,COUNT(*) AS songs,COALESCE(SUM(m.plays),0) AS plays
                """ + DISCOVER_RELEASES + """
                GROUP BY p.public_id,p.stage_name,p.avatar_key
                ORDER BY plays DESC,songs DESC,p.public_id LIMIT 12
                """, (rs, row) -> new DiscoverArtist(rs.getString("public_id"), rs.getString("stage_name"),
                rs.getString("avatar_key"), rs.getLong("songs"), rs.getLong("plays"))) : List.<DiscoverArtist>of();
        return new DiscoverPage(songs.stream().limit(pageSize).toList(), songs.size() > pageSize, artists);
    }

    public void recordDiscoverPlay(String song, long version) {
        if (db.query("SELECT r.id " + DISCOVER_RELEASES + " AND r.id=? AND r.version=?",
                (rs, row) -> rs.getString(1), song, version).isEmpty()) throw missing();
        String track = "discover:" + song;
        String increment = "UPDATE hot_song_metric SET plays=plays+1,updated_at=CURRENT_TIMESTAMP WHERE track_id=?";
        if (db.update(increment, track) > 0) return;
        try {
            db.update("INSERT INTO hot_song_metric(track_id,plays,updated_at) VALUES (?,1,CURRENT_TIMESTAMP)", track);
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            db.update(increment, track);
        }
    }

    @Transactional(readOnly = true)
    public PublicProfile publishedProfile(String publicId) {
        var profile = db.query("SELECT " + PROFILE_COLUMNS + " FROM artist_profile WHERE public_id=? AND published=TRUE",
                PROFILE_ROW, publicId).stream().findFirst().orElseThrow(ArtistProfileService::missing);
        var visible = releases(profile.creatorId());
        var songs = visible.stream()
                .map(song -> new PublicSong(song.id(), song.version(), song.title(), song.coverId() != null)).toList();
        return new PublicProfile(profile.stageName(), profile.tagline(), profile.avatarKey(), profile.coverKey(),
                visibleFeature(profile, visible), songs);
    }

    public byte[] publicAvatar(String publicId, String key) {
        return image(AVATAR, "public_id=? AND published=TRUE", publicId, key);
    }

    public byte[] ownerAvatar(String creator, String key) {
        return image(AVATAR, "creator_id=?", creator, key);
    }

    public byte[] publicProfileCover(String publicId, String key) {
        return image(COVER, "public_id=? AND published=TRUE", publicId, key);
    }

    public byte[] ownerProfileCover(String creator, String key) {
        return image(COVER, "creator_id=?", creator, key);
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

    private void updateImage(String creator, ArtistImage.Kind kind, byte[] data) {
        db.update("UPDATE artist_profile SET " + kind.column + "_key=?," + kind.column + "_jpeg=? WHERE creator_id=?",
                data == null ? null : uuid(), data, creator);
    }

    private byte[] image(ArtistImage.Kind kind, String ownerCondition, String identityKey, String imageKey) {
        return db.query("SELECT " + kind.column + "_jpeg FROM artist_profile WHERE " + ownerCondition
                        + " AND " + kind.column + "_key=?",
                (rs, row) -> rs.getBytes(1), identityKey, imageKey).stream().filter(Objects::nonNull).findFirst()
                .orElseThrow(ArtistProfileService::missing);
    }

    private Release publicRelease(String publicId, String song, long version) {
        return db.query("""
                SELECT r.id,r.version,r.title,r.work_id,r.asset_id,r.cover_id,
                    (r.discover_published_at IS NOT NULL) AS on_discover FROM artist_song_release r
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
        var songs = releases(row.creatorId());
        return new MyProfile(row.publicId(), row.stageName(), row.tagline(), row.avatarKey(), row.coverKey(),
                visibleFeature(row, songs), row.published(), songs);
    }

    private String requestedFeature(String creator, String requested, String saved) {
        if (requested == null) return saved;
        if (requested.isEmpty()) return null;
        if (requested.length() == 36 && db.query(PROFILE_RELEASES + " AND r.id=?", RELEASE_ROW, creator, requested)
                .stream().anyMatch(song -> song.id().equals(requested))) return requested;
        throw new DomainException("INVALID_ARTIST_FEATURED_RELEASE", "请选择仍在主页展示的歌曲。");
    }

    private static String visibleFeature(ProfileRow row, List<Release> songs) {
        return songs.stream().anyMatch(song -> song.id().equals(row.featuredReleaseId())) ? row.featuredReleaseId() : null;
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
