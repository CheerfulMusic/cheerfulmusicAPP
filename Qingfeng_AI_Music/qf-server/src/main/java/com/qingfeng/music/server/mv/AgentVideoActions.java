package com.qingfeng.music.server.mv;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

public final class AgentVideoActions implements AgentApplicationActions {
    private final MusicVideoService service;private final ConversationService conversations;private final ObjectMapper json=new ObjectMapper();
    public AgentVideoActions(MusicVideoService service,ConversationService conversations){this.service=service;this.conversations=conversations;}
    public List<Capability> capabilities(){return List.of(
        new Capability("video_capabilities","查询 AI 剧情 MV 可用性、价格和规格","","read"),
        new Capability("create_music_video","根据用户确认的剧情分镜制作 MV，视频模型异步生成镜头并配当前歌曲；费用 "+service.price()+" 积分，必须先展示分镜与费用再确认","workId?:作品ID,assetId?:音频ID,title:标题,ratio:16:9/9:16/1:1,shots:[{prompt:完整镜头描述含一致角色与风格,seconds:4至15}]，最多12镜头，总长最多120秒","paid"),
        new Capability("list_music_videos","查询作品的 MV 任务列表","workId?:作品ID","read"),
        new Capability("get_music_video","查询 MV 分镜、执行进度、视频成品","projectId:MV任务ID","read"),
        new Capability("retry_music_video_assembly","重试合成已完成镜头，不重新调用视频模型或扣费","projectId:MV任务ID","write"),
        new Capability("download_music_video","获取已完成 MV 的播放和下载卡片","projectId:MV任务ID","read"),
        new Capability("cancel_music_video","取消尚未开始的 MV 并退回积分；已提交任务不能承诺退款","projectId:MV任务ID","write"));}
    private String value(Map<String,Object> args,String key,String fallback){Object value=args.get(key);return value instanceof String s&&!s.isBlank()?s:fallback;}
    public Map<String,Object> prepare(String owner,String conversation,String action,Map<String,Object> args,SongCard current){
        var frozen=new LinkedHashMap<>(args);
        if(action.equals("create_music_video")){if(!service.available())throw new DomainException("VIDEO_NOT_CONFIGURED","视频服务尚未配置，暂不生成或扣费。");frozen.put("quotedCredits",service.price());String work=value(args,"workId",current==null?null:current.songId()),asset=value(args,"assetId",current==null?null:current.assetId());if(work==null||asset==null)throw new DomainException("VIDEO_AUDIO_REQUIRED","请先选择已有音频的作品，再制作 MV。");frozen.put("workId",work);frozen.put("assetId",asset);frozen.put("title",value(args,"title",current==null?"音乐 MV":current.title()));MusicVideoService.validate(plan(frozen));var storyboard=plan(frozen);StringBuilder summary=new StringBuilder("费用 "+service.price()+" 积分；画幅 "+storyboard.ratio()+"。\n");for(int i=0;i<storyboard.shots().size();i++){var shot=storyboard.shots().get(i);summary.append(i+1).append(". ").append(shot.seconds()).append(" 秒：").append(shot.prompt()).append("\n");}summary.append("仅未开始的任务可取消退款；提交不确定时需核对，不自动重提。音频从歌曲开头截取，最长不超过分镜总时长。");frozen.put("confirmationSummary",summary.toString());}
        return frozen;
    }
    private MusicVideoService.Plan plan(Map<String,Object> args){return json.readValue(json.writeValueAsString(Map.of("title",value(args,"title","音乐 MV"),"ratio",value(args,"ratio","16:9"),"shots",args.getOrDefault("shots",List.of()))),MusicVideoService.Plan.class);}
    public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current){
        conversations.state(owner,conversation);
        if(action.equals("video_capabilities"))return new Result(service.available()?"AI 剧情 MV 已启用。最多 12 个镜头、总长 120 秒；先确认分镜，再生成并配乐。费用 "+service.price()+" 积分。":"视频模型、密钥或计费尚未配置，暂不提交或扣费。可以先讨论剧情分镜。","video",List.of(),List.of(),action);
        String work=value(args,"workId",current==null?null:current.songId());
        if(action.equals("list_music_videos")){if(work==null)throw new DomainException("VIDEO_WORK_REQUIRED","请先打开作品。");var rows=service.list(owner,work);return new Result(rows.isEmpty()?"还没有 MV 任务。":"这些是作品的 MV 任务。","video",rows.stream().map(r->new Item((String)r.get("id"),"MV",String.valueOf(r.get("state")),Map.of())).toList(),rows.stream().map(r->new Command("查看 MV 进度","查看 MV 任务 "+r.get("id"),"send")).toList(),action);}
        String id=value(args,"projectId",null);
        if(action.equals("create_music_video")){if(!(args.get("quotedCredits") instanceof Number quoted)||quoted.intValue()!=service.price())throw new DomainException("VIDEO_QUOTE_CHANGED","MV 费用配置已变化，请重新确认分镜和费用。");id=service.create(owner,work,value(args,"assetId",current==null?null:current.assetId()),key,plan(args));}
        if(action.equals("cancel_music_video"))service.cancel(owner,id);
        if(action.equals("retry_music_video_assembly"))service.retryAssembly(owner,id);
        var row=service.get(owner,id);var data=new LinkedHashMap<String,Object>();data.put("projectId",id);data.put("state",row.get("state"));if("READY".equals(row.get("state")))data.put("videoUrl","/api/app/music-videos/"+id+"/content");
        return new Result("MV 状态："+row.get("state")+"。"+(row.get("error_code")==null?"":("任务需处理："+row.get("error_code"))),"video",List.of(new Item(id,"剧情 MV",String.valueOf(row.get("state")),data)),List.of(new Command("查看进度","查看 MV 任务 "+id,"send")),action);
    }
}
