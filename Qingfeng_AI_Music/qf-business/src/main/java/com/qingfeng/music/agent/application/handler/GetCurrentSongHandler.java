package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

/**
 * get_current_song：读取当前会话指针指向的作品，实时聚合成卡片。只读，不落库、不推进 revision。
 * 标题 / 歌词 / 音频一律来自 work / lyric_revision / media_asset，因此 set_style 不会改变这些字段。
 */
public class GetCurrentSongHandler implements AgentToolHandler {

    private final SongCardProjector projector;

    public GetCurrentSongHandler(WorkApi works, LyricsApi lyrics, MediaApi media) {
        this(works, lyrics, media, null);
    }

    public GetCurrentSongHandler(WorkApi works, LyricsApi lyrics, MediaApi media, com.qingfeng.music.generation.api.GenerationApi generation) {
        this.projector = new SongCardProjector(works, lyrics, media).withGeneration(generation);
    }
    public GetCurrentSongHandler withCovers(com.qingfeng.music.cover.api.CoverApi covers) { projector.withCovers(covers); return this; }

    @Override
    public AgentTool tool() {
        return AgentTool.GET_CURRENT_SONG;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        String workId = ctx.current().currentWorkId();
        String snapshotJson = ctx.state() == null ? null : ctx.state().currentSongJson();
        var card = projector.project(ctx.creatorId(), workId, snapshotJson);
        long revision = ctx.state() == null ? 0L : ctx.state().revision();
        return new AgentToolResult(card, workId, snapshotJson, revision, null, true);
    }
}
