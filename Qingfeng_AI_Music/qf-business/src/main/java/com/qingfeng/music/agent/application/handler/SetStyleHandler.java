package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.application.*;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;

/** 有作品时写入真实音乐设置；没有作品时保留待创作要求，不伪造音频结果。 */
public class SetStyleHandler implements AgentToolHandler {
    private final SongCardProjector projector;private final WorkApi works;private final MusicSettings settings;
    public SetStyleHandler(WorkApi works,LyricsApi lyrics,MediaApi media){this(works,lyrics,media,null);}
    public SetStyleHandler(WorkApi works,LyricsApi lyrics,MediaApi media,MusicSettings settings){
        this.works=works;this.settings=settings;projector=new SongCardProjector(works,lyrics,media);
    }
    public AgentTool tool(){return AgentTool.SET_STYLE;}
    public AgentToolResult execute(ToolContext ctx,Object args){
        var a=(AgentTool.SetStyleArgs)args;
        String snapshot=SongCardProjector.mergeSnapshot(AgentCreationSupport.snapshot(ctx),a.style(),a.mood(),a.bpm(),a.language(),a.instruments());
        if(a.vocal()!=null){var map=AgentJson.read(snapshot);map.put("vocal",a.vocal().toUpperCase(java.util.Locale.ROOT));snapshot=AgentJson.write(map);}
        String workId=ctx.current().currentWorkId();
        if(workId!=null&&!workId.isBlank()) snapshot=AgentCreationSupport.apply(settings,works,ctx.creatorId(),works.get(ctx.creatorId(),workId),snapshot,a.vocal()).snapshot();
        return new AgentToolResult(projector.project(ctx.creatorId(),workId,snapshot),workId,snapshot,ctx.state()==null?0:ctx.state().revision(),null,false);
    }
}
