package com.qingfeng.music.server;

import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.identity.api.IdentityApi;
import com.qingfeng.music.server.agent.AgentWorkReactions;
import com.qingfeng.music.server.hotsong.HotSongService;
import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:agent_work_reactions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AgentWorkReactionsTest {
    @Autowired AgentWorkReactions reactions;
    @Autowired IdentityApi identities;
    @Autowired WorkApi works;
    @Autowired HotSongService hotSongs;
    @Autowired JdbcTemplate db;

    private String creator(boolean signedIn) {
        String id = identities.create().creatorId();
        if (signedIn) db.update("INSERT INTO creator_oidc_identity(subject_hash,creator_id,created_at,updated_at) VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), id);
        return id;
    }

    @Test void workLikesArePersistentIdempotentAndExcludedFromPublicSamples() {
        String owner = creator(true);
        var work = works.create(owner, new WorkApi.Draft("", "", "毕业", "", "温暖民谣", "毕业歌"));
        assertFalse(reactions.liked(owner, work.id()));
        assertTrue(reactions.setLiked(owner, work.id(), true));
        assertTrue(reactions.setLiked(owner, work.id(), true));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM hot_song_reaction WHERE track_id=? AND creator_id=?", Integer.class, "work:" + work.id(), owner));
        assertFalse(hotSongs.reactions(owner).liked().contains("work:" + work.id()));
        assertTrue(hotSongs.stats().stream().noneMatch(stat -> stat.trackId().contains(work.id())));
        assertFalse(reactions.setLiked(owner, work.id(), false));
        assertFalse(reactions.setLiked(owner, work.id(), false));
    }

    @Test void anotherCreatorsPrivateWorkCannotBeReadOrLiked() {
        String owner = creator(true), other = creator(true);
        var work = works.create(owner, new WorkApi.Draft("", "", "私人创作", "", "温暖民谣", "我的歌"));
        assertThrows(DomainException.class, () -> reactions.liked(other, work.id()));
        assertThrows(DomainException.class, () -> reactions.setLiked(other, work.id(), true));
        assertFalse(reactions.liked(owner, work.id()));
    }

    @Test void guestCannotWriteReactions() {
        String owner = creator(false);
        var work = works.create(owner, new WorkApi.Draft("", "", "游客的歌", "", "温暖民谣", "草稿"));
        assertEquals("AGENT_LOGIN_REQUIRED", assertThrows(DomainException.class,
                () -> reactions.setLiked(owner, work.id(), true)).code());
        assertFalse(reactions.liked(owner, work.id()));
    }
}
