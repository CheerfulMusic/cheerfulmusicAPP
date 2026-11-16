package com.qingfeng.music.server.mv;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.common.concurrent.AgentRequestScope;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;
import com.qingfeng.music.server.agent.AgentAdmission;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Generates editable prompts only. Video generation and debit require a separate confirmation. */
public final class MusicVideoStoryboard implements AutoCloseable {
    private final MusicVideoService service;private final WorkApi works;private final MediaApi media;private final LyricsApi lyrics;private final MusicSettings settings;
    private final ObjectProvider<AgentChatModel> models;private final ObjectProvider<AgentAdmission> admissions;private final ConversationService conversations;
    private final ObjectMapper json=new ObjectMapper();
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mv-storyboard-admission");t.setDaemon(true);return t;});
    public MusicVideoStoryboard(MusicVideoService service,WorkApi works,MediaApi media,LyricsApi lyrics,MusicSettings settings,ObjectProvider<AgentChatModel> models,ObjectProvider<AgentAdmission> admissions,ConversationService conversations){this.service=service;this.works=works;this.media=media;this.lyrics=lyrics;this.settings=settings;this.models=models;this.admissions=admissions;this.conversations=conversations;}
    public MusicVideoService.Plan create(String owner,String work,String asset,String key,MusicVideoService.Plan requested){
        var model=models.getIfAvailable();var admission=admissions.getIfAvailable();
        if(model==null||admission==null)throw new DomainException("VIDEO_STORYBOARD_UNAVAILABLE","AI 分镜暂不可用，可以手动填写每段提示词。");
        var song=works.get(owner,work);var audio=media.get(owner,work,asset);var quote=service.quote(owner,work,asset,requested.resolution());
        String revision=media.generatedRevision(owner,work,asset).orElse(null);
        String text=revision==null?"":lyrics.get(owner,work,revision).lyrics();
        if(text.isBlank()){var revisions=lyrics.list(owner,work);if(!revisions.isEmpty())text=revisions.get(0).lyrics();}
        var direction=audio.generationTaskId()==null?settings.get(owner,work).direction():settings.task(owner,work,audio.generationTaskId()).orElseGet(()->settings.get(owner,work).direction());
        int count=(quote.seconds()+29)/30;
        String instruction="为这首歌曲设计完整剧情 MV，仅返回 create_music_video 的分镜草案，不生成视频。共 "+count+" 段，每段最多30秒，最后一段按余下时长。按完整歌曲的相对进度安排故事开场、人物与场景建立、发展、情绪冲突、高潮、结局与收束；不是每首歌机械套六段。结合歌词意象、叙事视角、音乐风格和已知节奏信息，不编造听音分析。每段 prompt 写清连续人物外观、场景、动作、镜头、光线和与上一段衔接；避免字幕、歌词文字或无关元素。每段50至120字。用户可稍后修改提示词和添加免费静态图片。只输出分镜，不能提出扣费或执行其它动作。";
        String conversation=conversations.current(owner).id();
        var ticket=admission.acquire(owner,conversation,key,instruction,"zh-CN");
        if(ticket.replay())throw new DomainException("VIDEO_STORYBOARD_REPLAY","这个分镜请求已处理，请查看已返回的分镜或明确重新生成。");
        boolean completed=false;ScheduledFuture<?> renewal=null;var scope=new AgentRequestScope(Duration.ofSeconds(55),65536);
        try(var ignored=scope.enter()){
            admission.start(ticket);renewal=heartbeat.scheduleWithFixedDelay(()->{try{admission.heartbeat(ticket);}catch(RuntimeException e){scope.cancel();}},10,10,TimeUnit.SECONDS);
            var capability=new AgentApplicationActions.Capability("create_music_video","只提供可编辑分镜草案，不执行视频生成","title,ratio,resolution,shots:[{prompt}]","read");
            var data=new LinkedHashMap<String,Object>();data.put("title",song.title());data.put("lyrics",text);data.put("musicDirection",direction);data.put("durationSeconds",quote.durationMillis()/1000.0);data.put("resolution",requested.resolution());data.put("ratio",requested.ratio());
            var result=model.respond(new AgentChatModel.Context(instruction,"zh-CN",List.of(),null,null,List.of(capability),json.writeValueAsString(data),new AgentChatModel.Execution(owner,conversation,key)));
            var args=result.orderedSteps().stream().filter(s->"create_music_video".equals(s.action())).findFirst().orElseThrow(()->new DomainException("VIDEO_STORYBOARD_INVALID","AI 未返回可用分镜，请重试或手动填写。")).args();
            var nodes=json.valueToTree(args.get("shots"));if(!nodes.isArray()||nodes.size()!=count)throw new DomainException("VIDEO_STORYBOARD_INVALID","AI 分镜段数与歌曲不一致，未生成视频，请重试或手动填写。");
            var shots=new ArrayList<MusicVideoService.Shot>();
            for(int i=0;i<count;i++){
                var old=requested.shots()!=null&&requested.shots().size()==count?requested.shots().get(i):null;
                shots.add(new MusicVideoService.Shot(nodes.get(i).path("prompt").asText(""),Math.min(30,quote.seconds()-i*30),old==null?null:old.imageId(),old==null?0:old.imageSeconds()));
            }
            var plan=service.prepare(owner,work,asset,new MusicVideoService.Plan(requested.title(),requested.ratio(),requested.resolution(),quote.durationMillis(),shots));
            admission.finish(ticket,true);completed=true;return plan;
        }finally{if(renewal!=null)renewal.cancel(false);if(!completed)admission.finish(ticket,false);}
    }
    public void close(){heartbeat.shutdownNow();}
}
