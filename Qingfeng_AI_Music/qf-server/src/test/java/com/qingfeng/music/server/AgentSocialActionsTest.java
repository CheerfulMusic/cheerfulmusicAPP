package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.server.agent.AgentWorkReactions;
import com.qingfeng.music.server.agent.DefaultAgentSocialActions;
import com.qingfeng.music.sharing.api.SharingApi;
import com.qingfeng.music.work.api.WorkApi;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentSocialActionsTest {
    private final WorkApi works = mock(WorkApi.class);
    private final SharingApi shares = mock(SharingApi.class);
    private final MediaApi media = mock(MediaApi.class);
    private final GenerationApi generation = mock(GenerationApi.class);
    private final AgentWorkReactions likes = mock(AgentWorkReactions.class);
    private final DefaultAgentSocialActions actions = new DefaultAgentSocialActions(works, shares, media, generation, likes);

    private SongCard ready() {
        return new SongCard("work-1", "毕业", "民谣", "真实歌词", "/api/app/works/work-1/audio/asset-2", "READY", null, 2, "revision-2", "asset-2", "task-2");
    }

    @Test void sharesTheExactVisibleAudioAndLyricRevision() {
        when(shares.create("owner", "work-1", "revision-2", "asset-2")).thenReturn(new SharingApi.Created("share-1", "opaque-token", Instant.now()));
        var result = actions.execute("owner", "conversation", "share", "分享当前作品", "key", ready());
        verify(works).get("owner", "work-1");
        verify(media).get("owner", "work-1", "asset-2");
        verify(shares).create("owner", "work-1", "revision-2", "asset-2");
        assertEquals("/gift/opaque-token", result.shareUrl());
        assertEquals("share", result.action());
    }

    @Test void aDraftCannotShareOrPlayASampleInPlaceOfGeneratedAudio() {
        SongCard draft = new SongCard("work-1", "毕业", "民谣", "歌词", "", "DRAFT", null, 1);
        for (String action : new String[]{"share", "play", "download"}) {
            assertEquals("AGENT_AUDIO_NOT_READY", assertThrows(DomainException.class,
                    () -> actions.execute("owner", "c", action, "x", "key", draft)).code());
        }
        verifyNoInteractions(shares, media);
    }

    @Test void deniedOwnershipStopsSharingBeforeAnySideEffect() {
        when(works.get("other", "work-1")).thenThrow(new DomainException("WORK_NOT_FOUND", "无权访问"));
        assertThrows(DomainException.class, () -> actions.execute("other", "c", "share", "分享", "key", ready()));
        verifyNoInteractions(shares, media, likes);
    }

    @Test void likeAndUnlikeSetTheRequestedStateOnTheCurrentWork() {
        when(likes.setLiked("owner", "work-1", true)).thenReturn(true);
        assertEquals("like", actions.execute("owner", "c", "like", "点赞", "key", ready()).action());
        assertEquals("unlike", actions.execute("owner", "c", "unlike", "取消点赞", "key2", ready()).action());
        verify(likes).setLiked("owner", "work-1", true);
        verify(likes).setLiked("owner", "work-1", false);
    }
}
