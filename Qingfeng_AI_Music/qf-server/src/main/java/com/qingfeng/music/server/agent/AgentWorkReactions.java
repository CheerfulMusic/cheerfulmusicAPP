package com.qingfeng.music.server.agent;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.work.api.WorkApi;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Private work reactions use a separate key namespace; public sample endpoints remain allowlisted. */
@Service
@Profile({"mysql", "test"})
public class AgentWorkReactions {
    private final JdbcTemplate db;
    private final WorkApi works;

    public AgentWorkReactions(JdbcTemplate db, WorkApi works) {
        this.db = db;
        this.works = works;
    }

    public boolean liked(String creatorId, String workId) {
        works.get(creatorId, workId);
        return count(creatorId, key(workId)) > 0;
    }

    @Transactional
    public boolean setLiked(String creatorId, String workId, boolean active) {
        works.get(creatorId, workId);
        Integer identities = db.queryForObject("SELECT (SELECT COUNT(*) FROM creator_google_identity WHERE creator_id=?)"
                + " + (SELECT COUNT(*) FROM creator_oidc_identity WHERE creator_id=?)", Integer.class, creatorId, creatorId);
        if (identities == null || identities == 0) {
            throw new DomainException("AGENT_LOGIN_REQUIRED", "请先登录账号，再给作品点赞。");
        }
        String trackId = key(workId);
        // Serialize a creator's mutations; retries set desired state rather than toggle it.
        db.queryForObject("SELECT id FROM creator WHERE id=? FOR UPDATE", String.class, creatorId);
        if (active && count(creatorId, trackId) == 0) {
            try {
                db.update("INSERT INTO hot_song_reaction(track_id,creator_id,kind,created_at) VALUES(?,?,'like',CURRENT_TIMESTAMP)", trackId, creatorId);
            } catch (DuplicateKeyException alreadyLiked) {
                // A concurrent request already stored the same desired state.
            }
        } else if (!active) {
            db.update("DELETE FROM hot_song_reaction WHERE track_id=? AND creator_id=? AND kind='like'", trackId, creatorId);
        }
        return count(creatorId, trackId) > 0;
    }

    private int count(String creatorId, String trackId) {
        Integer value = db.queryForObject("SELECT COUNT(*) FROM hot_song_reaction WHERE track_id=? AND creator_id=? AND kind='like'", Integer.class, trackId, creatorId);
        return value == null ? 0 : value;
    }

    private static String key(String workId) { return "work:" + workId; }
}
