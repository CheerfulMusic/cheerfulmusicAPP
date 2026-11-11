package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

/**
 * generate_lyrics：确保有作品后创作歌词并确认为当前版本，返回已写入歌词的卡片。
 * 歌词创作在请求线程同步完成（端口自身决定是否需要模型），本处理器不轮询。
 */
public class GenerateLyricsHandler implements AgentToolHandler {

    private final WorkApi works;
    private final LyricsApi lyrics;
    private final LyricsCompositionService composer;
    private final SongCardProjector projector;

    public GenerateLyricsHandler(WorkApi works, LyricsApi lyrics, LyricsCompositionService composer,
                                 MediaApi media) {
        this.works = works;
        this.lyrics = lyrics;
        this.composer = composer;
        this.projector = new SongCardProjector(works, lyrics, media);
    }

    @Override
    public AgentTool tool() {
        return AgentTool.GENERATE_LYRICS;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        var a = (com.qingfeng.music.agent.api.AgentTool.GenerateLyricsArgs) args;
        String creatorId = ctx.creatorId();
        String workId = ctx.current().currentWorkId();
        var work = workId == null || workId.isBlank()
                ? works.create(creatorId, new WorkApi.Draft("", "",
                a.text() == null || a.text().isBlank() ? "智能创作" : a.text(), "",
                "", "智能创作", "AUTO"))
                : works.get(creatorId, workId);

        var revision = composer.compose(creatorId, work.id(), work.version(),
                ctx.requestKey(), GenerateSongHandler.mapLanguage(a.language()), a.text(), null);
        revision = lyrics.confirm(creatorId, work.id(), revision.id());

        SongCard card = projector.project(creatorId, work.id(), ctx.state() == null
                ? null : ctx.state().currentSongJson());
        long expectedRevision = ctx.state() == null ? 0L : ctx.state().revision();
        return new AgentToolResult(card, work.id(), ctx.state() == null
                ? null : ctx.state().currentSongJson(), expectedRevision, null, false);
    }
}
