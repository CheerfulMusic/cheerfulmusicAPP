package com.qingfeng.music.server.artist;

import static com.qingfeng.music.server.artist.ArtistImage.Kind.AVATAR;
import static com.qingfeng.music.server.artist.ArtistImage.Kind.COVER;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.cover.api.SharedCoverAccess;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.generation.api.GenerationApi;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.media.api.SharedMediaAccess;
import com.qingfeng.music.server.security.LogtoIdentityService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    public record PublicSong(String id, long version, String title, boolean hasCover, String coverVersion) {}
    public record ReleaseSelection(boolean profileReady, boolean published, String publicId, String stageName, Release song,
                                   boolean publicationPending, boolean reopensSongs) {}
    record PublicationJob(String id, String owner, String work, String asset, String title) {}
    private static final String PUBLICATION_TOPIC = "music.publish";
    // The worker or the owner's result page can finish this event once audio is archived.
    private static final String WAITING_FOR_AUDIO = "WAITING_AUDIO";
    private static String publicCoverVersion(String id) {
        if (id == null) return null;
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record DiscoverSong(String artistId, String stageName, PublicSong song, Instant publishedAt,
                               String genre, long plays) {}
    public record DiscoverArtist(String artistId, String stageName, String avatarKey, long songs, long plays) {}
    public record DiscoverPage(List<DiscoverSong> songs, boolean hasMore, List<DiscoverArtist> artists) {}
    public record DiscoverShelves(boolean listeningDataAvailable, List<DiscoverSong> today,
                                 List<DiscoverSong> vocal, Map<String, List<DiscoverSong>> genres) {}
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
    private static final RowMapper<PublicationJob> PUBLICATION_ROW = (rs, row) -> new PublicationJob(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
    private static final String READY_PUBLICATIONS = """
            SELECT e.id,w.owner_id,w.id,t.asset_id,t.title
            FROM task_event_outbox e JOIN generation_task t ON t.id=e.event_key
            JOIN music_work w ON w.id=t.work_id
            WHERE e.topic=? AND e.state=? AND e.next_attempt_at<=CURRENT_TIMESTAMP
              AND t.status='SUCCEEDED' AND t.asset_id IS NOT NULL AND w.deleted_at IS NULL
            """;
    private static final String PROFILE_RELEASES = """
            SELECT r.id,r.version,r.title,r.work_id,r.asset_id,r.cover_id,
                (r.discover_published_at IS NOT NULL) AS on_discover FROM artist_song_release r
            JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
            WHERE r.creator_id=? AND r.show_on_profile=TRUE
              AND NOT EXISTS (SELECT 1 FROM media_asset_annotation v WHERE v.asset_id=r.asset_id AND v.deleted_at IS NOT NULL)
            """;
    private static final String DISCOVER_JOINS = """
            FROM artist_song_release r
            JOIN artist_profile p ON p.creator_id=r.creator_id AND p.published=TRUE
            JOIN music_work w ON w.id=r.work_id AND w.owner_id=r.creator_id AND w.deleted_at IS NULL
            LEFT JOIN hot_song_metric m ON m.track_id=CONCAT('discover:',r.id)
            """;
    private static final String DISCOVER_VISIBLE = """
            WHERE r.show_on_profile=TRUE AND r.discover_published_at IS NOT NULL
            """;
    private static final String DISCOVER_RELEASES = DISCOVER_JOINS + DISCOVER_VISIBLE;
    // Publish only known genres. The original style text can contain private creation instructions.
    private static final String DISCOVER_GENRE = """
            CASE
              WHEN w.style LIKE '%民谣%' OR LOWER(w.style) LIKE '%folk%' THEN 'folk'
              WHEN w.style LIKE '%古风%' OR w.style LIKE '%国风%' THEN 'guofeng'
              WHEN w.style LIKE '%摇滚%' OR LOWER(w.style) LIKE '%rock%' THEN 'rock'
              WHEN w.style LIKE '%电子%' OR LOWER(w.style) LIKE '%electronic%' THEN 'electronic'
              WHEN w.style LIKE '%纯音乐%' OR LOWER(w.style) LIKE '%instrumental%' THEN 'instrumental'
              WHEN w.style LIKE '%欧美流行%' OR LOWER(w.style) LIKE '%western pop%'
                OR LOWER(w.style) LIKE '%global pop%' OR LOWER(w.style) LIKE '%western-pop%' THEN 'global-pop'
              WHEN w.style LIKE '%爵士%' OR w.style LIKE '%灵魂乐%' OR LOWER(w.style) LIKE '%jazz%'
                OR LOWER(w.style) LIKE '%soul%' OR LOWER(w.style) LIKE '%r&b%' THEN 'jazz-soul'
              WHEN w.style LIKE '%流行%' OR LOWER(w.style) LIKE '%pop%' THEN 'pop'
              ELSE 'other'
            END
            """;
    private static final ZoneId DISCOVER_DAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String DISCOVER_ORDER_NEWEST = " ORDER BY r.discover_published_at DESC,r.id DESC";
    private static final RowMapper<DiscoverSong> DISCOVER_SONG_ROW = (rs, row) ->
            new DiscoverSong(rs.getString("public_id"), rs.getString("stage_name"),
                    new PublicSong(rs.getString("id"), rs.getLong("version"), rs.getString("title"),
                            rs.getString("cover_id") != null, publicCoverVersion(rs.getString("cover_id"))),
                    rs.getTimestamp("discover_published_at").toInstant(), rs.getString("genre"), rs.getLong("plays"));

    private final JdbcTemplate db;
    private final IdentityApi identity;
    private final LogtoIdentityService logto;
    private final MediaApi media;
    private final SharedMediaAccess audio;
    private final SharedCoverAccess covers;
    private final com.qingfeng.music.lyrics.playback.PlaybackLyricsRepository playbackLyrics;
    private volatile Boolean discoverShelfStorageReady;

    public ArtistProfileService(JdbcTemplate db, IdentityApi identity, LogtoIdentityService logto,
                                MediaApi media, SharedMediaAccess audio, SharedCoverAccess covers,
                                com.qingfeng.music.lyrics.playback.PlaybackLyricsRepository playbackLyrics) {
        this.db = db;
        this.identity = identity;
        this.logto = logto;
        this.media = media;
        this.audio = audio;
        this.covers = covers;
        this.playbackLyrics = playbackLyrics;
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
        lockCreator(creator);
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
        lockCreator(creator);
        var profile = requireOwnerProfile(creator);
        if (publish) requireStageName(profile.stageName());
        db.update("UPDATE artist_profile SET published=? WHERE creator_id=?", publish, creator);
        if (!publish) {
            cancelPendingPublications(creator, null);
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
        boolean published = profile.map(ProfileRow::published).orElse(false);
        boolean pending = db.queryForObject("""
                SELECT COUNT(*) FROM task_event_outbox e JOIN generation_task t ON t.id=e.event_key
                JOIN music_work w ON w.id=t.work_id
                WHERE e.topic=? AND e.state=? AND w.owner_id=? AND w.id=? AND w.deleted_at IS NULL
                  AND t.status IN ('QUEUED','SUBMITTING','RUNNING','SAVING','SUBMISSION_UNKNOWN','SUCCEEDED')
                """, Long.class, PUBLICATION_TOPIC, WAITING_FOR_AUDIO, creator, work) > 0;
        boolean reopensSongs = !published && profile.isPresent()
                && db.queryForObject("SELECT COUNT(*) FROM (" + PROFILE_RELEASES + ") releases",
                        Long.class, creator) > 0;
        return new ReleaseSelection(profile.filter(row -> !row.stageName().isBlank()).isPresent(), published,
                profile.map(ProfileRow::publicId).orElse(null), profile.map(ProfileRow::stageName).orElse(""),
                ownerRelease(creator, work).orElse(null), pending, reopensSongs);
    }

    /** Called only for a newly accepted generation and in that same database transaction. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void queuePublication(String creator, GenerationApi.Task task, String name) {
        if (!hasAccount(creator)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先登录音乐账号。");
        lockCreator(creator);
        if (ownerRelease(creator, task.workId()).isPresent())
            throw new DomainException("SONG_ALREADY_PUBLISHED", "请试听后手动发布新版本。");
        prepareStageName(creator, name);
        cancelPendingPublications(creator, task.workId());
        db.update("""
                INSERT INTO task_event_outbox(id,topic,event_key,payload,state,next_attempt_at,created_at)
                VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, uuid(), PUBLICATION_TOPIC, task.id(), task.id(), WAITING_FOR_AUDIO);
    }

    @Transactional
    public ReleaseSelection publishEverywhere(String creator, String work, String asset, String cover, String title,
                                              String stageName) {
        lockCreator(creator);
        prepareStageName(creator, stageName);
        publishEverywhere(creator, work, asset, cover, title);
        cancelPendingPublications(creator, work);
        return selection(creator, work);
    }

    @Transactional
    public ReleaseSelection makePrivate(String creator, String work) {
        lockCreator(creator);
        cancelPendingPublications(creator, work);
        if (ownerRelease(creator, work).isPresent()) removeSong(creator, work);
        return selection(creator, work);
    }

    List<PublicationJob> readyPublications() {
        return db.query(READY_PUBLICATIONS + " ORDER BY e.next_attempt_at,e.id LIMIT 16",
                PUBLICATION_ROW, PUBLICATION_TOPIC, WAITING_FOR_AUDIO);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReleaseSelection completePublication(String creator, String work) {
        db.query(READY_PUBLICATIONS + " AND w.owner_id=? AND w.id=? ORDER BY e.next_attempt_at,e.id LIMIT 1",
                PUBLICATION_ROW, PUBLICATION_TOPIC, WAITING_FOR_AUDIO, creator, work)
                .stream().findFirst().ifPresent(this::publishQueuedSong);
        return selection(creator, work);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void finishPublication(PublicationJob job) {
        publishQueuedSong(job);
    }

    private void publishQueuedSong(PublicationJob job) {
        lockCreator(job.owner());
        if (db.queryForList("SELECT id FROM task_event_outbox WHERE id=? AND state=? FOR UPDATE",
                job.id(), WAITING_FOR_AUDIO).isEmpty()) return;
        if (hasAccount(job.owner()) && ownerRelease(job.owner(), job.work()).isEmpty()) {
            publishEverywhere(job.owner(), job.work(), job.asset(), covers.current(job.work()), job.title());
        }
        db.update("UPDATE task_event_outbox SET state='SENT',published_at=CURRENT_TIMESTAMP WHERE id=?", job.id());
    }

    void publicationFailed(PublicationJob job, boolean permanent) {
        db.update("""
                UPDATE task_event_outbox SET state=?,attempts=attempts+1,
                    next_attempt_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP),last_error='PUBLICATION_FAILED'
                WHERE id=? AND state=?
                """, permanent ? "DEAD" : WAITING_FOR_AUDIO, job.id(), WAITING_FOR_AUDIO);
    }

    private void publishEverywhere(String creator, String work, String asset, String cover, String title) {
        var profile = requireOwnerProfile(creator);
        var release = publishSong(creator, work, asset, cover, title);
        if (!profile.published())
            db.update("UPDATE artist_profile SET published=TRUE WHERE creator_id=?", creator);
        publishToDiscover(creator, work, release.version());
    }

    private void prepareStageName(String creator, String name) {
        var profile = findOwner(creator, true);
        if (profile.filter(row -> !row.stageName().isBlank()).isPresent()) return;
        String stageName = text(name, 30, "INVALID_ARTIST_NAME", "艺名最多 30 个字符。", false);
        requireStageName(stageName);
        if (profile.isEmpty()) {
            db.update("INSERT INTO artist_profile(creator_id,public_id,stage_name,tagline,published) VALUES(?,?,?,'',FALSE)",
                    creator, uuid(), stageName);
        } else {
            db.update("UPDATE artist_profile SET stage_name=? WHERE creator_id=?", stageName, creator);
        }
    }

    private void lockCreator(String creator) {
        db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, creator);
    }

    private void cancelPendingPublications(String creator, String work) {
        String jobs = """
                SELECT t.id FROM generation_task t JOIN music_work w ON w.id=t.work_id WHERE w.owner_id=?
                """ + (work == null ? "" : " AND w.id=?");
        db.update("UPDATE task_event_outbox SET state='CANCELLED' WHERE topic=? AND state=? AND event_key IN (" + jobs + ")",
                work == null ? new Object[]{PUBLICATION_TOPIC, WAITING_FOR_AUDIO, creator}
                             : new Object[]{PUBLICATION_TOPIC, WAITING_FOR_AUDIO, creator, work});
    }

    @Transactional
    public Release publishSong(String creator, String work, String assetId, String coverId, String title) {
        var profile = requireOwnerProfile(creator);
        requireStageName(profile.stageName());
        var source = media.locate(creator, work, assetId);
        // Serialize publishing with version removal, after media.locate has verified ownership.
        if (db.queryForList("SELECT m.id FROM media_asset m LEFT JOIN media_asset_annotation v ON v.asset_id=m.id WHERE m.work_id=? AND m.id=? AND v.deleted_at IS NULL FOR UPDATE", work, assetId).isEmpty())
            throw new DomainException("MEDIA_NOT_FOUND", "歌曲版本已删除，请选择其他版本。");
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
            throw new DomainException("ARTIST_HOME_REQUIRED", "请先公开歌手主页，再发布到广场。");
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

    @Transactional
    public void setDiscoverVocal(String releaseId, long version, boolean approved) {
        if (!discoverShelfStorageReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!isDiscoverRelease(releaseId, version)) throw missing();
        if (approved) {
            db.update("""
                    INSERT INTO discover_vocal_approval(release_id,release_version) VALUES (?,?)
                    ON DUPLICATE KEY UPDATE release_id=release_id
                    """, releaseId, version);
        } else {
            db.update("DELETE FROM discover_vocal_approval WHERE release_id=? AND release_version=?", releaseId, version);
        }
    }

    @Transactional(readOnly = true)
    public DiscoverPage discover(boolean featured, boolean popular, String genre, String query, int offset) {
        int pageSize = 24;
        String keyword = "%" + query.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String order = popular ? " ORDER BY plays DESC, r.discover_published_at DESC,r.id DESC"
                : DISCOVER_ORDER_NEWEST;
        // Keep legacy 'pop' searches inclusive of international pop.
        String genreFilter = "pop".equals(genre) ? " IN (?, 'global-pop')" : "=?";
        var songs = discoverSongs("COALESCE(m.plays,0)", (featured ? " AND r.discover_featured=TRUE" : "") + """
                 AND (LOWER(r.title) LIKE ? ESCAPE '!' OR LOWER(p.stage_name) LIKE ? ESCAPE '!')
                 AND (?='' OR
                """ + DISCOVER_GENRE + genreFilter + ")" + order + " LIMIT ? OFFSET ?",
                keyword, keyword, genre, genre, pageSize + 1, offset);
        var artists = offset == 0 ? db.query("""
                SELECT p.public_id,p.stage_name,p.avatar_key,COUNT(*) AS songs,COALESCE(SUM(m.plays),0) AS plays
                """ + DISCOVER_RELEASES + """
                GROUP BY p.public_id,p.stage_name,p.avatar_key
                ORDER BY plays DESC,songs DESC,p.public_id LIMIT 12
                """, (rs, row) -> new DiscoverArtist(rs.getString("public_id"), rs.getString("stage_name"),
                rs.getString("avatar_key"), rs.getLong("songs"), rs.getLong("plays"))) : List.<DiscoverArtist>of();
        return new DiscoverPage(songs.stream().limit(pageSize).toList(), songs.size() > pageSize, artists);
    }

    @Transactional(readOnly = true)
    public DiscoverShelves discoverShelves() {
        var genres = new LinkedHashMap<String, List<DiscoverSong>>();
        for (String genre : List.of("electronic", "global-pop", "folk", "rock", "jazz-soul")) {
            genres.put(genre, discoverSongs("COALESCE(m.plays,0)", " AND " + DISCOVER_GENRE + "=?"
                    + DISCOVER_ORDER_NEWEST + " LIMIT 12", genre));
        }
        if (!discoverShelfStorageReady()) return new DiscoverShelves(false, List.of(), List.of(), genres);

        var today = queryDiscoverSongs("d.plays", """
                JOIN discover_daily_play d ON d.release_id=r.id AND d.release_version=r.version AND d.play_day=?
                """, " ORDER BY d.plays DESC,r.discover_published_at DESC,r.id DESC LIMIT 12",
                LocalDate.now(DISCOVER_DAY_ZONE));
        var vocal = discoverSongs("COALESCE(m.plays,0)", """
                 AND EXISTS (SELECT 1 FROM discover_vocal_approval a
                             WHERE a.release_id=r.id AND a.release_version=r.version)
                """ + DISCOVER_ORDER_NEWEST + " LIMIT 12");
        return new DiscoverShelves(true, today, vocal, genres);
    }

    private List<DiscoverSong> discoverSongs(String playExpression, String tail, Object... args) {
        return queryDiscoverSongs(playExpression, "", tail, args);
    }

    private List<DiscoverSong> queryDiscoverSongs(String playExpression, String join, String tail, Object... args) {
        return db.query("""
                SELECT p.public_id,p.stage_name,r.id,r.version,r.title,r.cover_id,r.discover_published_at,
                """ + playExpression + " AS plays," + DISCOVER_GENRE + " AS genre " + DISCOVER_JOINS + join + DISCOVER_VISIBLE + tail,
                DISCOVER_SONG_ROW, args);
    }

    // The app also runs against installations whose Flyway target predates these shelves.
    private boolean discoverShelfStorageReady() {
        Boolean ready = discoverShelfStorageReady;
        if (ready != null) return ready;
        try {
            db.queryForObject("SELECT COUNT(*) FROM discover_daily_play WHERE 1=0", Long.class);
            db.queryForObject("SELECT COUNT(*) FROM discover_vocal_approval WHERE 1=0", Long.class);
            discoverShelfStorageReady = true;
        } catch (org.springframework.jdbc.BadSqlGrammarException missingTable) {
            String state = missingTable.getSQLException().getSQLState();
            if (!"42S02".equals(state) && !"42S04".equals(state)) throw missingTable;
            discoverShelfStorageReady = false;
        }
        return discoverShelfStorageReady;
    }

    private boolean isDiscoverRelease(String song, long version) {
        return !db.query("SELECT r.id " + DISCOVER_RELEASES + " AND r.id=? AND r.version=?",
                (rs, row) -> rs.getString(1), song, version).isEmpty();
    }

    @Transactional
    public void recordDiscoverPlay(String song, long version) {
        if (!isDiscoverRelease(song, version)) throw missing();
        String track = "discover:" + song;
        db.update("""
                INSERT INTO hot_song_metric(track_id,plays,updated_at) VALUES (?,1,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE plays=plays+1,updated_at=CURRENT_TIMESTAMP
                """, track);
        if (discoverShelfStorageReady()) {
            db.update("""
                    INSERT INTO discover_daily_play(play_day,release_id,release_version,plays) VALUES (?,?,?,1)
                    ON DUPLICATE KEY UPDATE plays=plays+1
                    """, LocalDate.now(DISCOVER_DAY_ZONE), song, version);
        }
    }

    @Transactional(readOnly = true)
    public PublicProfile publishedProfile(String publicId) {
        var profile = db.query("SELECT " + PROFILE_COLUMNS + " FROM artist_profile WHERE public_id=? AND published=TRUE",
                PROFILE_ROW, publicId).stream().findFirst().orElseThrow(ArtistProfileService::missing);
        var visible = releases(profile.creatorId());
        var songs = visible.stream()
                .map(song -> new PublicSong(song.id(), song.version(), song.title(), song.coverId() != null, publicCoverVersion(song.coverId()))).toList();
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

    public MediaApi.MediaRef publicAudioRef(String publicId, String song, long version) {
        var release = publicRelease(publicId, song, version);
        return audio.ref(release.workId(), release.assetId());
    }

    /** Only archived timing for the exact visible release; never expose editable drafts or create provider jobs. */
    @Transactional(readOnly = true)
    public com.qingfeng.music.lyrics.playback.PlaybackLyrics.Snapshot publicPlaybackLyrics(String publicId, String song, long version) {
        var release = publicRelease(publicId, song, version);
        var stored = playbackLyrics.find(release.assetId());
        var lines = stored.filter(s -> "READY".equals(s.status()))
                .map(s -> com.qingfeng.music.lyrics.playback.PlaybackLyrics.lines(s.words())).orElse(List.of());
        String text = lines.stream().map(com.qingfeng.music.lyrics.playback.PlaybackLyrics.Line::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        return new com.qingfeng.music.lyrics.playback.PlaybackLyrics.Snapshot(
                lines.isEmpty() ? stored.map(s -> java.util.Set.of("PREPARING","FETCHING","UNKNOWN").contains(s.status()) ? s.status() : "EMPTY").orElse("EMPTY") : "READY", text, lines, false);
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

    public MediaApi.MediaRef publicCoverRef(String publicId, String song, long version) {
        var release = publicRelease(publicId, song, version);
        if (release.coverId() == null) throw missing();
        return covers.ref(release.workId(), release.coverId());
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
                  AND NOT EXISTS (SELECT 1 FROM media_asset_annotation v WHERE v.asset_id=r.asset_id AND v.deleted_at IS NOT NULL)
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
