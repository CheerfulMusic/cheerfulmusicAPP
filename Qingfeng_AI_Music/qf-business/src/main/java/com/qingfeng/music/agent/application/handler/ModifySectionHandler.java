package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.application.SectionEditingService;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

import java.util.List;

/**
 * modify_section：局部改词。适配器职责——定位选中音频与歌词段落索引后，交由 SectionEditingService 提交。
 * 段落文本索引在该作品当前已确认歌词中定位（best-effort）；找不到即报错，不静默改写。
 */
public class ModifySectionHandler implements AgentToolHandler {

    private final WorkApi works;
    private final LyricsApi lyrics;
    private final MediaApi media;
    private final SectionEditingService editing;
    private final SongCardProjector projector;

    public ModifySectionHandler(WorkApi works, LyricsApi lyrics, MediaApi media,
                               SectionEditingService editing) {
        this.works = works;
        this.lyrics = lyrics;
        this.media = media;
        this.editing = editing;
        this.projector = new SongCardProjector(works, lyrics, media);
    }

    @Override
    public AgentTool tool() {
        return AgentTool.MODIFY_SECTION;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        var a = (com.qingfeng.music.agent.api.AgentTool.ModifySectionArgs) args;
        String creatorId = ctx.creatorId();
        String workId = ctx.current().currentWorkId();
        if (workId == null || workId.isBlank()) {
            throw new DomainException("NO_WORK", "请先创作一首歌，再局部修改。");
        }
        var work = works.get(creatorId, workId);
        var selection = media.selection(creatorId, workId);
        if (selection == null || selection.assetId() == null || selection.assetId().isBlank()) {
            throw new DomainException("NO_AUDIO", "该作品还没有可编辑的原曲音频。");
        }
        Revision revision = latestConfirmed(creatorId, workId);
        if (revision == null) {
            throw new DomainException("NO_LYRICS", "请先确认歌词，再局部修改。");
        }
        String section = a.section();
        int textStart = revision.lyrics().indexOf(section);
        if (textStart < 0) {
            throw new DomainException("SECTION_NOT_FOUND", "未在歌词中找到该段落，请提供更准确的文本。");
        }
        int textEnd = textStart + section.length();
        double start = a.startS() == null ? 0.0 : a.startS();
        double end = a.endS() == null ? 30.0 : a.endS();

        var request = new SectionEditingService.Request(selection.assetId(), work.version(),
                ctx.requestKey(), start, end, textStart, textEnd, a.replacementLyrics());
        var task = editing.submit(creatorId, workId, request);

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
