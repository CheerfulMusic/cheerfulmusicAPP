package com.qingfeng.music.server.web.publicapi;

import com.qingfeng.music.server.artist.DiscoverSongService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("(mysql | test) & !sg-reader")
@ConditionalOnProperty(name = "qingfeng.wechat.official.self-card.enabled", havingValue = "true")
class WechatSelfCardService {
    private static final long MESSAGE_WINDOW = Duration.ofHours(48).toSeconds();
    private static final long CODE_LIFETIME = Duration.ofMinutes(10).toSeconds();
    private static final long RESULT_RETENTION = Duration.ofDays(7).toSeconds();
    private static final long PENDING_LIFETIME = Duration.ofSeconds(45).toSeconds();
    private static final Pattern SONG = Pattern.compile("/song/([A-Za-z0-9_-]{43})");
    private static final Pattern DISCOVER = Pattern.compile("(?:/en)?/discover/songs/([0-9a-f-]{36})/([1-9][0-9]{0,18})");
    private static final Pattern BIND_MESSAGE = Pattern.compile("绑定卡片[\\s　]+([A-Fa-f0-9]{16})");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final WechatOfficialClient client;
    private final PublicSongAccess songs;
    private final DiscoverSongService discover;
    private final Clock clock;
    private final String origin;
    private final String appId;
    private final SecureRandom random = new SecureRandom();

    record Challenge(String command, Instant expiresAt) {}
    enum State { BIND_REQUIRED, NEEDS_MESSAGE, SENDING, BUSY, SENT, REJECTED, UNAVAILABLE, UNKNOWN }
    private record Binding(byte[] openId, long lastMessageAt, long blockedMessageAt) {}
    private record Attempt(String songHash, State state, long at) {}
    private record Reservation(State state, Binding binding) {}

    @Autowired
    WechatSelfCardService(JdbcTemplate db, PlatformTransactionManager tm, WechatOfficialClient client,
            PublicSongAccess songs, DiscoverSongService discover,
            @Value("${qingfeng.sharing.public-base-url:https://cheerfulmusic.com}") String origin) {
        this(db, tm, client, songs, discover, origin, Clock.systemUTC());
    }

    WechatSelfCardService(JdbcTemplate db, PlatformTransactionManager tm, WechatOfficialClient client,
            PublicSongAccess songs, DiscoverSongService discover, String origin, Clock clock) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.client = client;
        this.songs = songs;
        this.discover = discover;
        this.origin = origin.replaceAll("/+$", "");
        this.clock = clock;
        this.appId = client.appId();
        var base = URI.create(this.origin);
        if (!client.configured() || !"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || !base.getPath().isEmpty() || base.getQuery() != null || base.getFragment() != null)
            throw new IllegalArgumentException("WeChat card delivery requires official-account credentials and an HTTPS public origin");
    }

    boolean bound(String creator) {
        var binding = binding(creator, false);
        return binding != null && binding.openId() != null;
    }

    Challenge challenge(String creator) {
        byte[] entropy = new byte[8];
        random.nextBytes(entropy);
        String code = HexFormat.of().withUpperCase().formatHex(entropy);
        String hash = hash(code);
        long expiry = now() + CODE_LIFETIME;
        if (updateChallenge(creator, hash, expiry) == 0) {
            try {
                db.update("INSERT INTO wechat_card_binding(creator_id,app_id,code_hash,code_expires_at) VALUES (?,?,?,?)",
                        creator, appId, hash, expiry);
            } catch (DuplicateKeyException exists) {
                if (updateChallenge(creator, hash, expiry) == 0) throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
        }
        return new Challenge("绑定卡片 " + code, Instant.ofEpochSecond(expiry));
    }

    private int updateChallenge(String creator, String hash, long expiry) {
        return db.update("""
                UPDATE wechat_card_binding SET code_hash=?,code_expires_at=? WHERE creator_id=? AND app_id=? AND open_id IS NULL
                """, hash, expiry, creator, appId);
    }

    void unlink(String creator) {
        db.update("DELETE FROM wechat_card_binding WHERE creator_id=? AND app_id=?", creator, appId);
    }

    void accept(WechatCardPush.Text text) {
        long now = now();
        if (text.createdAt() > now + 60 || text.createdAt() <= now - MESSAGE_WINDOW) return;
        long eventAt = Math.min(text.createdAt(), now);
        byte[] openId = text.openId().getBytes(StandardCharsets.US_ASCII);
        var message = BIND_MESSAGE.matcher(text.text());
        if (message.matches() && eventAt >= now - CODE_LIFETIME) {
            try {
                db.update("""
                        UPDATE wechat_card_binding SET open_id=?,code_hash=NULL,code_expires_at=NULL,last_message_at=?
                        WHERE app_id=? AND open_id IS NULL AND code_hash=? AND code_expires_at>?
                        """, openId, eventAt, appId, hash(message.group(1).toUpperCase(java.util.Locale.ROOT)), now);
            } catch (DuplicateKeyException alreadyLinked) {
                // This WeChat identity remains linked to its existing site account.
            }
        }
        db.update("""
                UPDATE wechat_card_binding SET last_message_at=? WHERE app_id=? AND open_id=? AND last_message_at<?
                """, eventAt, appId, openId, eventAt);
    }

    State send(String creator, UUID requestId, String path) {
        String id = requestId.toString(), songHash = hash(path);
        var prior = prior(creator, id);
        if (prior != null) return repeated(prior, songHash, now());
        if (!bound(creator)) return State.BIND_REQUIRED;
        var card = news(path);
        var reservation = tx.execute(status -> reserve(creator, id, songHash));
        if (reservation.binding() == null) return reservation.state();
        var binding = reservation.binding();
        var outcome = State.valueOf(client.sendNews(new String(binding.openId(), StandardCharsets.US_ASCII), card).name());
        tx.executeWithoutResult(status -> {
            db.update("UPDATE wechat_card_delivery SET state=? WHERE creator_id=? AND app_id=? AND request_id=?",
                    outcome.name(), creator, appId, id);
            if (outcome == State.NEEDS_MESSAGE) db.update("""
                    UPDATE wechat_card_binding SET blocked_message_at=? WHERE creator_id=? AND app_id=?
                      AND open_id=? AND last_message_at=?
                    """, binding.lastMessageAt(), creator, appId, binding.openId(), binding.lastMessageAt());
        });
        return outcome;
    }

    private Reservation reserve(String creator, String id, String songHash) {
        long now = now();
        var binding = binding(creator, true);
        var prior = prior(creator, id);
        if (prior != null) return new Reservation(repeated(prior, songHash, now), null);
        if (binding == null || binding.openId() == null) return new Reservation(State.BIND_REQUIRED, null);
        if (binding.lastMessageAt() <= now - MESSAGE_WINDOW || binding.blockedMessageAt() >= binding.lastMessageAt())
            return new Reservation(State.NEEDS_MESSAGE, null);
        boolean sending = Boolean.TRUE.equals(db.queryForObject("""
                SELECT COUNT(*)>0 FROM wechat_card_delivery WHERE creator_id=? AND app_id=? AND state='SENDING' AND requested_at>?
                """, Boolean.class, creator, appId, now - PENDING_LIFETIME));
        if (sending) return new Reservation(State.BUSY, null);
        db.update("DELETE FROM wechat_card_delivery WHERE creator_id=? AND app_id=? AND requested_at<?",
                creator, appId, now - RESULT_RETENTION);
        db.update("""
                INSERT INTO wechat_card_delivery(creator_id,app_id,request_id,song_hash,state,requested_at)
                VALUES (?,?,?,?,'SENDING',?)
                """, creator, appId, id, songHash, now);
        return new Reservation(State.SENDING, binding);
    }

    private Binding binding(String creator, boolean lock) {
        return db.query("""
                SELECT open_id,last_message_at,blocked_message_at FROM wechat_card_binding WHERE creator_id=? AND app_id=?
                """ + (lock ? " FOR UPDATE" : ""), (r, n) -> new Binding(r.getBytes(1), r.getLong(2), r.getLong(3)),
                creator, appId).stream().findFirst().orElse(null);
    }

    private Attempt prior(String creator, String id) {
        return db.query("SELECT song_hash,state,requested_at FROM wechat_card_delivery WHERE creator_id=? AND app_id=? AND request_id=?",
                (r, n) -> new Attempt(r.getString(1), State.valueOf(r.getString(2)), r.getLong(3)),
                creator, appId, id).stream().findFirst().orElse(null);
    }

    private static State repeated(Attempt attempt, String hash, long now) {
        if (!attempt.songHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        if (attempt.state() == State.SENDING && attempt.at() <= now - PENDING_LIFETIME) return State.UNKNOWN;
        return attempt.state();
    }

    private WechatOfficialClient.News news(String path) {
        var song = SONG.matcher(path);
        if (song.matches()) {
            String id = song.group(1);
            var grant = songs.require(id);
            return new WechatOfficialClient.News(grant.title() + " · " + grant.creator(), PublicSongController.DESCRIPTION,
                    origin + path, origin + "/api/songs/" + id + "/thumbnail.png");
        }
        var publicRelease = DISCOVER.matcher(path);
        if (!publicRelease.matches()) throw PublicSongAccess.missing();
        String id;
        long version;
        try {
            id = UUID.fromString(publicRelease.group(1)).toString();
            version = Long.parseLong(publicRelease.group(2));
        } catch (IllegalArgumentException invalid) { throw PublicSongAccess.missing(); }
        if (!id.equals(publicRelease.group(1))) throw PublicSongAccess.missing();
        var release = discover.sharingRelease(id, version);
        String thumbnail = origin + (release.song().hasCover()
                ? "/api/public/artists/" + release.artistId() + "/songs/" + id + "/" + version + "/cover"
                : "/brand/share-default-cover.png");
        return new WechatOfficialClient.News(release.song().title() + " · " + release.stageName(),
                "在青风音乐广场收听这首歌。", origin + path, thumbnail);
    }

    private long now() { return clock.instant().getEpochSecond(); }

    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
