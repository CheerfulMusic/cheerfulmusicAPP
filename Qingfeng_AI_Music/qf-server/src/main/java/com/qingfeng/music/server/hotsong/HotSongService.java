package com.qingfeng.music.server.hotsong;

import com.qingfeng.music.common.exception.DomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页「热歌推荐」的播放量、点赞、收藏、评论计数。
 * 曲目是内置试听样本，不来自作品库，因此按 track_id 单独计数；
 * 公开接口只能命中白名单曲目，避免任何人往表里写任意行。
 */
@Service @Profile({"mysql","test"})
public class HotSongService {
    /** 与前端 src/modules/home/model/tracks.ts 的 9 首内置试听曲目一一对应。 */
    public static final Set<String> TRACKS = Set.of(
            "love", "family", "travel", "red-guofeng", "rain-disco",
            "tomorrow-rock", "scenery-folk", "hometown-electronic", "river-instrumental");
    private static final String COLUMNS = "track_id,plays,likes,favorites,comments";
    private static final Map<String, String> REACTION_COLUMNS = Map.of("like", "likes", "favorite", "favorites");

    public record Stat(String trackId, long plays, long likes, long favorites, long comments) {}

    public record Toggle(Stat stat, boolean liked, boolean favorited) {}

    public record Reactions(List<String> liked, List<String> favorited) {}

    private final JdbcTemplate db;

    public HotSongService(JdbcTemplate db) { this.db = db; }

    /** 首页只需要聚合数字；没有记录时返回 0，不写空行。 */
    public List<Stat> stats() {
        Map<String, Stat> found = new LinkedHashMap<>();
        String placeholders = String.join(",", Collections.nCopies(TRACKS.size(), "?"));
        for (var row : db.queryForList("SELECT " + COLUMNS + " FROM hot_song_metric WHERE track_id IN (" + placeholders + ")",
                TRACKS.toArray())) {
            var stat = new Stat(text(row.get("track_id")), number(row.get("plays")),
                    number(row.get("likes")), number(row.get("favorites")), number(row.get("comments")));
            found.put(stat.trackId(), stat);
        }
        List<Stat> result = new ArrayList<>();
        for (var track : TRACKS) result.add(found.getOrDefault(track, new Stat(track, 0, 0, 0, 0)));
        return result;
    }

    public Stat stat(String trackId) {
        requireTrack(trackId);
        var rows = db.queryForList("SELECT " + COLUMNS + " FROM hot_song_metric WHERE track_id=?", trackId);
        if (rows.isEmpty()) return new Stat(trackId, 0, 0, 0, 0);
        var row = rows.get(0);
        return new Stat(trackId, number(row.get("plays")), number(row.get("likes")),
                number(row.get("favorites")), number(row.get("comments")));
    }

    /** 每次开始播放 +1；前端已按会话去重，这里只保证并发下不丢计数。 */
    public Stat play(String trackId) {
        requireTrack(trackId);
        if (db.update("UPDATE hot_song_metric SET plays=plays+1,updated_at=CURRENT_TIMESTAMP WHERE track_id=?", trackId) == 0) {
            try {
                db.update("INSERT INTO hot_song_metric(" + COLUMNS + ",updated_at) VALUES(?,1,0,0,0,CURRENT_TIMESTAMP)", trackId);
            } catch (DuplicateKeyException raced) {
                db.update("UPDATE hot_song_metric SET plays=plays+1,updated_at=CURRENT_TIMESTAMP WHERE track_id=?", trackId);
            }
        }
        return stat(trackId);
    }

    /** 点赞、收藏都要求已登录：游客 creator 会话不算登录。 */
    @Transactional
    public Toggle react(String creatorId, String trackId, String kind, boolean active) {
        requireLogin(creatorId);
        requireTrack(trackId);
        var column = REACTION_COLUMNS.get(kind);
        if (column == null) throw new DomainException("INVALID_REACTION_KIND", "不支持的互动类型");
        ensureRow(trackId);
        if (active) {
            try {
                db.update("INSERT INTO hot_song_reaction(track_id,creator_id,kind,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP)",
                        trackId, creatorId, kind);
                db.update("UPDATE hot_song_metric SET " + column + "=" + column + "+1,updated_at=CURRENT_TIMESTAMP WHERE track_id=?", trackId);
            } catch (DuplicateKeyException already) {
                // 重复点赞幂等：同一创作者只计一次。
            }
        } else if (db.update("DELETE FROM hot_song_reaction WHERE track_id=? AND creator_id=? AND kind=?", trackId, creatorId, kind) > 0) {
            db.update("UPDATE hot_song_metric SET " + column + "=GREATEST(" + column + "-1,0),updated_at=CURRENT_TIMESTAMP WHERE track_id=?", trackId);
        }
        var mine = reactions(creatorId);
        return new Toggle(stat(trackId), mine.liked().contains(trackId), mine.favorited().contains(trackId));
    }

    public Reactions reactions(String creatorId) {
        var liked = new ArrayList<String>();
        var favorited = new ArrayList<String>();
        if (creatorId != null && !creatorId.isBlank()) {
            for (var row : db.queryForList("SELECT track_id,kind FROM hot_song_reaction WHERE creator_id=?", creatorId)) {
                var track = text(row.get("track_id"));
                if (!TRACKS.contains(track)) continue;
                if ("like".equals(text(row.get("kind")))) liked.add(track);
                else if ("favorite".equals(text(row.get("kind")))) favorited.add(track);
            }
        }
        return new Reactions(liked, favorited);
    }

    /** 登录只认已绑定的 Google / OIDC 身份；与支付下单的前置校验口径一致。 */
    private void requireLogin(String creatorId) {
        Integer identities = db.queryForObject(
                "SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?) + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)",
                Integer.class, creatorId, creatorId);
        if (identities == null || identities == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先登录账号");
    }

    private void requireTrack(String trackId) {
        if (trackId == null || !TRACKS.contains(trackId)) throw new DomainException("HOT_SONG_NOT_FOUND", "这首歌不在热歌列表里");
    }

    private void ensureRow(String trackId) {
        if (db.update("UPDATE hot_song_metric SET updated_at=updated_at WHERE track_id=?", trackId) > 0) return;
        try {
            db.update("INSERT INTO hot_song_metric(" + COLUMNS + ",updated_at) VALUES(?,0,0,0,0,CURRENT_TIMESTAMP)", trackId);
        } catch (DuplicateKeyException raced) {
            // 另一个请求已建行，后续更新会用同一行。
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
}
