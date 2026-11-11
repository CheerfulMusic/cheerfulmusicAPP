package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

import java.util.List;

/**
 * extend_song：延长歌曲。
 *
 * 供应商能力核实（2026-11-11，KIE 官方 docs.kie.ai/suno-api/extend-music）：
 * KIE 确有 ai-music-api/extend（audio_id + model 必填），但本仓库 MusicProvider 端口
 * 未接入 extend 方法（见 agent/application/port/MusicProvider.java，仅 submit/replace）。
 * 按「不重写 KIE 接入」约束，W2 落地 C5 降级方案：续写歌词 + 原风格快照重新生成「新版本」，
 * 由 W3 在 UI 明确标注「延长（降级为新版本）」，不伪装为片段拼接延长。
 * 真正的 KIE extend 接入作为独立后续项，不在本次改造范围。
 */
public class ExtendSongHandler implements AgentToolHandler {

    private final WorkApi works;
    private final LyricsApi lyrics;
    private final LyricsCompositionService composer;
    private final GenerationApi generation;
    private final SongCardProjector projector;

    public ExtendSongHandler(WorkApi works, LyricsApi lyrics, LyricsCompositionService composer,
                             GenerationApi generation, MediaApi media) {
        this.works = works;
        this.lyrics = lyrics;
        this.composer = composer;
        this.generation = generation;
        this.projector = new SongCardProjector(works, lyrics, media);
    }

    @Override
    public AgentTool tool() {
        return AgentTool.EXTEND_SONG;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        String creatorId = ctx.creatorId();
        String workId = ctx.current().currentWorkId();
        if (workId == null || workId.isBlank()) {
            throw new DomainException("NO_WORK", "请先创作一首歌，再延长。");
        }
        var work = works.get(creatorId, workId);
        Revision revision = latestConfirmed(creatorId, workId);
        if (revision == null) {
            throw new DomainException("NO_LYRICS", "请先确认歌词，再延长。");
        }

        // C5 降级：基于当前版本续写并重新生成新版本（非片段拼接延长）。
        String instructions = "在此基础上继续延伸，扩充段落与情绪推进，保持原有主题与风格。";
        var extended = composer.compose(creatorId, workId, work.version(), ctx.requestKey(),
                revision.language(), instructions, revision.id());
        extended = lyrics.confirm(creatorId, workId, extended.id());
        var task = generation.submit(creatorId, workId, extended.id(), ctx.requestKey());

        SongCard base = projector.project(creatorId, workId, ctx.state() == null
                ? null : ctx.state().currentSongJson());
        SongCard card = new SongCard(workId, base.title(), base.style(), base.lyrics(), "",
                task.status().name(), base.coverUrl(), base.revision());
        long expectedRevision = ctx.state() == null ? 0L : ctx.state().revision();
        return new AgentToolResult(card, workId, ctx.state() == null
                ? null : ctx.state().currentSongJson(), expectedRevision, task.id(), false);
    }

    private Revision latestConfirmed(String creatorId, String workId) {
        List<Revision> revisions = lyrics.list(creatorId, workId);
        Revision found = null;
        for (Revision r : revisions) {
            if (r.confirmed()) found = r;
        }
        return found;
    }
}
