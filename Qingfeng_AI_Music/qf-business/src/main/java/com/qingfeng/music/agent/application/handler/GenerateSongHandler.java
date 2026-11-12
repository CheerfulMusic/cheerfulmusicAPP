package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.*;
import com.qingfeng.music.agent.model.StyleCatalog;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;

/** 参数先落入真实作品和音乐设置，再通过既有提交服务冻结、校验预算与幂等。 */
public class GenerateSongHandler implements AgentToolHandler {
    private final WorkApi works; private final LyricsApi lyrics;
    private final LyricsCompositionService composer; private final GenerationApi generation;
    private final SongCardProjector projector; private final MusicSettings settings;
    public GenerateSongHandler(WorkApi works, LyricsApi lyrics, LyricsCompositionService composer, GenerationApi generation, MediaApi media) {
        this(works, lyrics, composer, generation, media, null);
    }
    public GenerateSongHandler(WorkApi works, LyricsApi lyrics, LyricsCompositionService composer, GenerationApi generation, MediaApi media, MusicSettings settings) {
        this.works=works; this.lyrics=lyrics; this.composer=composer; this.generation=generation; this.settings=settings;
        projector=new SongCardProjector(works,lyrics,media).withGeneration(generation);
    }
    public AgentTool tool() { return AgentTool.GENERATE_SONG; }
    public AgentToolResult execute(ToolContext ctx, Object args) {
        var a=(AgentTool.GenerateSongArgs) args;
        String owner=ctx.creatorId();
        boolean newWork="new".equals(a.mode()) || ctx.current().currentWorkId()==null || ctx.current().currentWorkId().isBlank();
        String snapshot=newWork && ctx.current().currentWorkId()!=null ? null : AgentCreationSupport.snapshot(ctx);
        var work=newWork ? works.create(owner,new WorkApi.Draft("","",a.text()==null || a.text().isBlank()?"今天的心情":a.text(),"",
                StyleCatalog.safe(a.style()==null?AgentCreationSupport.value(snapshot,"style"):a.style()),"智能创作",AgentCreationSupport.vocal(a.vocal(),AgentCreationSupport.vocal(AgentCreationSupport.value(snapshot,"vocal"),"AUTO"))),ctx.requestKey()) : works.get(owner,ctx.current().currentWorkId());
        // A transport retry must return the original submitted task without mutating settings or spending again.
        var existing=generation.list(owner,work.id()).stream().filter(t->ctx.requestKey().equals(t.requestKey())).findFirst();
        if(existing.isPresent()) return result(ctx,work.id(),snapshot,existing.get());
        var revision=newWork ? null : AgentCreationSupport.selected(lyrics,owner,work.id(),a.revisionId(),snapshot);
        String language=AgentCreationSupport.language(a.language(),snapshot,revision);
        snapshot=SongCardProjector.mergeSnapshot(snapshot,a.style(),null,a.bpm(),language,a.instruments());
        var applied=AgentCreationSupport.apply(settings,works,owner,work,snapshot,a.vocal());
        work=applied.work(); snapshot=applied.snapshot();
        if(revision==null) {
            revision=composer.compose(owner,work.id(),work.version(),ctx.requestKey(),mapLanguage(language),
                    AgentCreationSupport.instructions("",language),null);
        } else if(revision.workVersion()!=work.version()) {
            // Preserve selected words and language when only music settings changed.
            revision=lyrics.save(owner,work.id(),work.version(),revision.title(),revision.lyrics(),revision.id());
        }
        revision=lyrics.confirm(owner,work.id(),revision.id());
        // A long lyric call may have exhausted the Agent turn's shared lease/deadline.
        com.qingfeng.music.common.concurrent.AgentRequestScope.check();
        var task=generation.submit(owner,work.id(),revision.id(),ctx.requestKey());
        return result(ctx,work.id(),snapshot,task);
    }
    private AgentToolResult result(ToolContext ctx,String workId,String snapshot,GenerationApi.Task task) {
        snapshot=SongCardProjector.withPointers(snapshot,task.revisionId(),task.id());
        var base=projector.project(ctx.creatorId(),workId,snapshot);
        // A just-submitted task may not yet be visible to a reader; expose its real task ID/status regardless.
        var card=new SongCard(workId,task.title(),base.style(),task.lyrics(),base.audioUrl(),task.status().name(),base.coverUrl(),base.revision(),task.revisionId(),base.assetId(),task.id(),base.audioOrigin());
        return new AgentToolResult(card,workId,snapshot,ctx.state()==null?0:ctx.state().revision(),task.id(),false);
    }
    static String mapLanguage(String language) { return AgentCreationSupport.lyricLanguage(language); }
}
