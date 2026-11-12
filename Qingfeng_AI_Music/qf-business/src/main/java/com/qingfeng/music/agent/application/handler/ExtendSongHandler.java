package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.application.AgentToolHandler;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.lyrics.application.LyricsCompositionService;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

/** 音频延长端口尚未接入，不能悄悄扣费重新生成来冒充延长。 */
public class ExtendSongHandler implements AgentToolHandler {
    public ExtendSongHandler(WorkApi works,LyricsApi lyrics,LyricsCompositionService composer,GenerationApi generation,MediaApi media) {}
    public AgentTool tool(){return AgentTool.EXTEND_SONG;}
    public AgentToolResult execute(ToolContext ctx,Object args){
        throw new DomainException("EXTEND_UNAVAILABLE","音频延长尚未接入，未提交任务或扣除积分。可以先续写歌词，再明确要求生成新的完整版本。");
    }
}
