package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

import java.util.List;

/**
 * set_style：把风格参数合并进会话「轻量快照」，不触碰作品标题 / 歌词 / 音频。
 * 用 current_song_json 的 revision 做乐观锁；并发或陈旧的写入会冲突（→ HTTP 409），由上层整体回滚。
 */
public class SetStyleHandler implements AgentToolHandler {

    private final SongCardProjector projector;

    public SetStyleHandler(WorkApi works, LyricsApi lyrics, MediaApi media) {
        this.projector = new SongCardProjector(works, lyrics, media);
    }

    @Override
    public AgentTool tool() {
        return AgentTool.SET_STYLE;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        var a = (com.qingfeng.music.agent.api.AgentTool.SetStyleArgs) args;
        String existing = ctx.state() == null ? null : ctx.state().currentSongJson();
        String merged = SongCardProjector.mergeSnapshot(existing, a.style(), a.mood(), a.bpm(),
                a.language(), a.instruments());
        String workId = ctx.current().currentWorkId();
        SongCard card = projector.project(ctx.creatorId(), workId, merged);
        long expectedRevision = ctx.state() == null ? 0L : ctx.state().revision();
        return new AgentToolResult(card, workId, merged, expectedRevision, null, false);
    }
}
