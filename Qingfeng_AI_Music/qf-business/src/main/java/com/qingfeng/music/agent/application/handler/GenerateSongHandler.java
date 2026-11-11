package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.agent.model.StyleCatalog;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.api.LyricsApi.Revision;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

import java.util.List;
import java.util.Map;

/**
 * generate_song：确保有作品与已确认歌词，再提交生成任务。仅做适配：
 * - 无作品则按主题文案建草稿；
 * - 无已确认歌词则先创作并确认；
 * - style 越界回落站内白名单（注册表已校验，这里仅兜底）。
 * 返回「生成中」卡片（taskId + 任务状态），由 W3 进度推送补全。
 */
public class GenerateSongHandler implements AgentToolHandler {

    private final WorkApi works;
    private final LyricsApi lyrics;
    private final LyricsCompositionService composer;
    private final GenerationApi generation;
    private final SongCardProjector projector;

    public GenerateSongHandler(WorkApi works, LyricsApi lyrics, LyricsCompositionService composer,
                               GenerationApi generation, MediaApi media) {
        this.works = works;
        this.lyrics = lyrics;
        this.composer = composer;
        this.generation = generation;
        this.projector = new SongCardProjector(works, lyrics, media);
    }

    @Override
    public AgentTool tool() {
        return AgentTool.GENERATE_SONG;
    }

    @Override
    public AgentToolResult execute(ToolContext ctx, Object args) {
        var a = (AgentTool.GenerateSongArgs) args;
        String creatorId = ctx.creatorId();
        String workId = ctx.current().currentWorkId();
        var work = workId == null || workId.isBlank()
                ? works.create(creatorId, new WorkApi.Draft("", "",
                a.text() == null || a.text().isBlank() ? "智能创作" : a.text(), "",
                StyleCatalog.safe(a.style()), "智能创作", "AUTO"))
                : works.get(creatorId, workId);

        Revision revision = latestConfirmed(creatorId, work.id());
        if (revision == null) {
            String language = mapLanguage(a.language());
            revision = composer.compose(creatorId, work.id(), work.version(),
                    ctx.requestKey(), language, a.text(), null);
            revision = lyrics.confirm(creatorId, work.id(), revision.id());
        }

        GenerationApi.Task task = generation.submit(creatorId, work.id(), revision.id(), ctx.requestKey());

        SongCard base = projector.project(creatorId, work.id(), ctx.state() == null
                ? null : ctx.state().currentSongJson());
        SongCard card = new SongCard(work.id(), base.title(), base.style(), base.lyrics(), "",
                task.status().name(), base.coverUrl(), base.revision());

        String snapshot = SongCardProjector.mergeSnapshot(ctx.state() == null ? null
                : ctx.state().currentSongJson(), StyleCatalog.safe(a.style()), null, a.bpm(),
                a.language(), a.instruments());
        long expectedRevision = ctx.state() == null ? 0L : ctx.state().revision();
        return new AgentToolResult(card, work.id(), snapshot, expectedRevision, task.id(), false);
    }

    private Revision latestConfirmed(String creatorId, String workId) {
        List<Revision> revisions = lyrics.list(creatorId, workId);
        Revision found = null;
        for (Revision r : revisions) {
            if (r.confirmed()) found = r;
        }
        return found;
    }

    static String mapLanguage(String language) {
        if (language == null) return "zh-CN";
        return switch (language) {
            case "en" -> "en";
            case "zh", "yue" -> "zh-CN";
            default -> "zh-CN";
        };
    }
}
