package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.application.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;

/** 显式 new 创建新作品；rewrite 使用选中的真实歌词版本作为模型输入。 */
public class GenerateLyricsHandler implements AgentToolHandler {
    private final WorkApi works; private final LyricsApi lyrics; private final LyricsCompositionService composer;
    private final SongCardProjector projector; private final MusicSettings settings;
    public GenerateLyricsHandler(WorkApi works,LyricsApi lyrics,LyricsCompositionService composer,MediaApi media) { this(works,lyrics,composer,media,null); }
    public GenerateLyricsHandler(WorkApi works,LyricsApi lyrics,LyricsCompositionService composer,MediaApi media,MusicSettings settings) {
        this.works=works;this.lyrics=lyrics;this.composer=composer;this.settings=settings;projector=new SongCardProjector(works,lyrics,media);
    }
    public AgentTool tool(){return AgentTool.GENERATE_LYRICS;}
    public AgentToolResult execute(ToolContext ctx,Object args) {
        var a=(AgentTool.GenerateLyricsArgs)args;
        boolean newWork="new".equals(a.mode()) || ctx.current().currentWorkId()==null || ctx.current().currentWorkId().isBlank();
        if(newWork && "rewrite".equals(a.mode())) throw new DomainException("NO_LYRICS","请先创作歌词，再指定修改要求。");
        String owner=ctx.creatorId(),snapshot=newWork && ctx.current().currentWorkId()!=null?null:AgentCreationSupport.snapshot(ctx);
        var work=newWork ? works.create(owner,new WorkApi.Draft("","",a.text()==null||a.text().isBlank()?"今天的心情":a.text(),"","","智能创作","AUTO"),ctx.requestKey()) : works.get(owner,ctx.current().currentWorkId());
        var previous=newWork?null:AgentCreationSupport.selected(lyrics,owner,work.id(),a.revisionId(),snapshot);
        if("rewrite".equals(a.mode()) && previous==null) throw new DomainException("NO_LYRICS","请先创作歌词，再指定修改要求。");
        String language=AgentCreationSupport.language(a.language(),snapshot,previous);
        snapshot=SongCardProjector.mergeSnapshot(snapshot,null,null,null,language,null);
        var applied=AgentCreationSupport.apply(settings,works,owner,work,snapshot,null);work=applied.work();snapshot=applied.snapshot();
        var revision=composer.compose(owner,work.id(),work.version(),ctx.requestKey(),GenerateSongHandler.mapLanguage(language),
                AgentCreationSupport.instructions(newWork?"":a.text(),language),previous==null?null:previous.id());
        revision=lyrics.confirm(owner,work.id(),revision.id());
        snapshot=SongCardProjector.withPointers(snapshot,revision.id(),null);
        return new AgentToolResult(projector.project(owner,work.id(),snapshot),work.id(),snapshot,ctx.state()==null?0:ctx.state().revision(),null,false);
    }
}
