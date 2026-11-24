package com.qingfeng.music.server.mv;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.application.AgentVideoResolution;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

public final class AgentVideoActions implements AgentApplicationActions {
    private static final Set<String> PREVIEWS=Set.of("video_capabilities","preview_music_video");
    private static final List<String> RESOLUTIONS=List.of("480P","720P","1080P");
    private final MusicVideoService service;private final ConversationService conversations;private final ObjectMapper json=new ObjectMapper();
    private final boolean previewsOnly;
    public AgentVideoActions(MusicVideoService service,ConversationService conversations){this(service,conversations,false);}
    AgentVideoActions(MusicVideoService service,ConversationService conversations,boolean previewsOnly){this.service=service;this.conversations=conversations;this.previewsOnly=previewsOnly;}
    public List<Capability> capabilities(){var all=List.of(
        new Capability("video_capabilities","读取当前歌曲真实时长，展示完整 MV 的三档分辨率、总费用及选择按钮；制作前先调用","workId?:作品ID,assetId?:音频ID","read"),
        new Capability("create_music_video","按用户选择的分辨率制作整首歌曲 MV；480P 每秒1积分、720P 每秒2积分、1080P 每秒3积分。必须先展示系统计算的完整歌曲费用及分镜再确认，不能替用户选择分辨率","workId?:作品ID,assetId?:音频ID,title:标题,ratio:16:9/9:16/1:1,resolution:用户选择的480P/720P/1080P,shots:[{prompt:完整镜头描述含一致角色与风格}]；系统按真实歌曲时长以每30秒一段分配镜头，不能自填歌曲时长；shots可带imageId与imageSeconds；imageId只能来自用户上传结果，图片停留时间免费且包含在本段时长内；先用upload_video_image让用户选图，支持逐段修改提示词和停留秒数","paid"),
        new Capability("preview_music_video","准备或修改MV分镜、图片和停留时间，返回服务器计算的完整时间轴与费用，不生成视频、不扣费；用户调整分镜后先调用","与create_music_video相同参数","read"),
        new Capability("upload_video_image","在对话内上传MV结尾图片；上传后返回真实imageId，再按用户指定段落和秒数加入分镜，上传和停留免费","workId?:作品ID,assetId?:音频ID","native"),
        new Capability("open_music_video_editor","在对话内打开完整MV分镜编辑器，支持AI分镜、图片、停留时间、费用确认及已有任务恢复","workId?:作品ID,assetId?:音频ID","native"),
        new Capability("list_music_videos","查询作品的 MV 任务列表","workId?:作品ID","read"),
        new Capability("get_music_video","查询 MV 分镜、执行进度、视频成品","projectId:MV任务ID","read"),
        new Capability("retry_music_video_shot","修改并重试明确失败的MV镜头，保留成功片段、不额外扣积分；先查询任务获取失败镜头与原提示词，必须展示修改内容并确认","projectId:MV任务ID,shotNumber:从1开始的失败镜头序号,prompt:修改后的完整提示词","write"),
        new Capability("retry_music_video_assembly","重试合成已完成镜头，不重新调用视频模型或扣费","projectId:MV任务ID","write"),
        new Capability("download_music_video","获取已完成 MV 的播放和下载卡片","projectId:MV任务ID","read"),
        new Capability("cancel_music_video","取消尚未开始的 MV 并退回积分；已提交任务不能承诺退款","projectId:MV任务ID","write"));
        return previewsOnly?all.stream().filter(c->PREVIEWS.contains(c.id())).toList():all;
    }
    private String value(Map<String,Object> args,String key,String fallback){Object value=args.get(key);return value instanceof String s&&!s.isBlank()?s:fallback;}
    public Map<String,Object> prepare(String owner,String conversation,String action,Map<String,Object> args,SongCard current){
        requireEnabled(action);
        var frozen=new LinkedHashMap<>(args);
        if("preview_music_video".equals(action)&&value(args,"resolution","").isBlank()) {
            frozen.put("resolution","480P");frozen.put("resolutionUnselected",true);
        }
        if(Set.of("create_music_video","preview_music_video").contains(action)){
            if("create_music_video".equals(action)&&!service.available())throw new DomainException("VIDEO_NOT_CONFIGURED","视频服务尚未配置，暂不生成或扣费。");
            String work=value(args,"workId",current==null?null:current.songId()),asset=value(args,"assetId",current==null?null:current.assetId());
            if(work==null||asset==null)throw new DomainException("VIDEO_AUDIO_REQUIRED","请先选择已有音频的作品，再制作 MV。");
            frozen.put("workId",work);frozen.put("assetId",asset);frozen.put("title",value(args,"title",current==null?"音乐 MV":current.title()));
            if("preview_music_video".equals(action)&&args.get("starterBrief") instanceof String brief&&!args.containsKey("shots"))
                frozen.put("shots",starterPrompts(brief).stream().map(prompt->Map.of("prompt",prompt)).toList());
            var storyboard=service.prepare(owner,work,asset,plan(frozen));
            frozen.put("resolution",storyboard.resolution());frozen.put("durationMillis",storyboard.durationMillis());frozen.put("shots",storyboard.shots());
            frozen.put("quotedCredits",MusicVideoService.price(storyboard));
            StringBuilder summary=new StringBuilder("完整歌曲时长 "+(storyboard.durationMillis()/1000.0)+" 秒；生成视频 "+MusicVideoService.generatedSeconds(storyboard)+" 秒计费，图片停留免费（歌曲不足一秒按一秒）。\n分辨率 "+storyboard.resolution()+"，每秒 "+MusicVideoService.rate(storyboard.resolution())+" 积分，总计 "+MusicVideoService.price(storyboard)+" 积分；画幅 "+storyboard.ratio()+"。\n");
            int elapsed=0;
            for(int i=0;i<storyboard.shots().size();i++){var shot=storyboard.shots().get(i);summary.append(i+1).append(". ").append(elapsed).append("–").append(elapsed+shot.seconds()).append(" 秒：").append(shot.prompt()).append("；生成视频 ").append(shot.seconds()-shot.imageSeconds()).append(" 秒，免费图片 ").append(shot.imageSeconds()).append(" 秒").append(shot.imageId()==null?"":"，图片 "+shot.imageId()).append("\n");elapsed+=shot.seconds();}
            summary.append("保留整首原歌曲音轨，成片时长与歌曲一致。仅未开始的任务可取消退款；提交不确定时需核对，不自动重提。");
            frozen.put("confirmationSummary",summary.toString());
            if(Boolean.TRUE.equals(frozen.get("resolutionUnselected"))) {
                int seconds=MusicVideoService.generatedSeconds(storyboard);
                frozen.put("confirmationSummary",summary+"\n以上480P仅为报价示例，尚未选择分辨率。三档报价：480P "+seconds+" 积分；720P "+seconds*2+" 积分；1080P "+seconds*3+" 积分。");
            }
        }
        if("retry_music_video_shot".equals(action)){
            var row=service.get(owner,value(args,"projectId",null));
            int index=shotIndex(args);String prompt=value(args,"prompt","");
            if(!"FAILED".equals(row.get("state"))||prompt.isBlank()||prompt.length()>2000)throw new DomainException("INVALID_VIDEO_REQUEST","请先查询明确失败的任务，并填写新的镜头描述。");
            var shots=json.valueToTree(row.get("shots"));
            var shot=java.util.stream.StreamSupport.stream(shots.spliterator(),false).filter(n->n.path("shot_index").asInt(-1)==index&&"FAILED".equals(n.path("state").asText())).findFirst().orElseThrow(()->new DomainException("INVALID_VIDEO_REQUEST","所选镜头不是失败状态。"));
            frozen.put("expectedPrompt",shot.path("prompt").asText());
            frozen.put("confirmationSummary","仅重试第 "+(index+1)+" 个镜头，保留成功片段并继续剩余镜头，不额外扣积分。新画面："+prompt+"。生成仍需通过视频服务审核。");
        }
        return frozen;
    }
    private void requireEnabled(String action){
        if(previewsOnly&&!PREVIEWS.contains(action))throw new DomainException("VIDEO_NOT_CONFIGURED","当前仅开放分镜预览与报价。");
    }
    private int shotIndex(Map<String,Object> args){Object n=args.get("shotNumber");if(!(n instanceof Number number)||number.doubleValue()!=number.intValue()||number.intValue()<1||number.intValue()>120)throw new DomainException("INVALID_VIDEO_REQUEST","请指定1至120之间的镜头序号。");return number.intValue()-1;}
    private static List<String> starterPrompts(String brief) {
        String direction=brief.toLowerCase(Locale.ROOT);
        List<String> prompts;
        if(direction.contains("舞台")||direction.contains("live performance"))
            prompts=List.of("从台下的观众缓慢推向舞台，侧光勾出主唱的轮廓。","切到手拨琴弦和演唱的近景，再拉开带出乐手与观众的呼应。","镜头随最后的和弦退回观众席，舞台灯逐盏熄灭。");
        else if(direction.contains("旅行")||direction.contains("travel"))
            prompts=List.of("从列车窗边的车票切到窗外远景，交代这段旅程的起点。","跟随旅人穿过陌生的街道，用同一方向的运动衔接沿途风景。","旅人停在开阔处回望来路，镜头拉远，把最后的画面留给远方。");
        else
            prompts=List.of("清晨，人物在窗边收起一张旧照片，镜头从照片特写推向人物的脸。","跟拍人物带着照片走过街口，在熟悉的转角停下，切到迟疑的手部特写。","人物把照片递给等候的人，镜头从两人的目光缓缓拉远。");
        return prompts;
    }
    private MusicVideoService.Plan plan(Map<String,Object> args){
        var nodes=json.valueToTree(args.getOrDefault("shots",List.of()));var shots=new ArrayList<MusicVideoService.Shot>();
        if(!nodes.isArray())throw new DomainException("INVALID_VIDEO_REQUEST","请提供分镜列表。");
        for(var node:nodes)shots.add(new MusicVideoService.Shot(node.path("prompt").asText(""),node.path("seconds").asInt(0),node.path("imageId").asText(null),node.path("imageSeconds").asInt(0)));
        long duration=args.get("durationMillis") instanceof Number n?n.longValue():0;
        return new MusicVideoService.Plan(value(args,"title","音乐 MV"),value(args,"ratio","16:9"),value(args,"resolution",""),duration,shots);
    }
    private List<Command> resolutionChoices(String work,String asset,int seconds){
        if(!service.available())return List.of();
        return RESOLUTIONS.stream().map(resolution->new Command("选择 "+resolution+" · "+seconds*MusicVideoService.rate(resolution)+" 积分",
                new AgentVideoResolution(resolution,work,asset).command(),"send")).toList();
    }
    public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current){
        requireEnabled(action);
        conversations.state(owner,conversation);
        if(action.equals("preview_music_video")){
            var prepared=prepare(owner,conversation,action,args,current);
            String intro=prepared.containsKey("starterBrief")?"以下是可编辑的基础分镜草案。\n":"";
            var storyboard=plan(prepared);
            String work=(String)prepared.get("workId"),asset=(String)prepared.get("assetId");
            var item=new Item("plan","完整 MV 分镜","可继续调整分镜。",Map.of("workId",work,"assetId",asset,"plan",storyboard,"quotedCredits",prepared.get("quotedCredits")));
            var choices=resolutionChoices(work,asset,MusicVideoService.generatedSeconds(storyboard));
            String next=choices.isEmpty()?"目前可以继续调整分镜，视频生成服务暂不可用。":"请选择分辨率，核对费用后再确认生成。";
            return new Result(intro+prepared.get("confirmationSummary")+"\n这是分镜预览，尚未生成或扣费。"+next,"video_plan",List.of(item),choices,action);
        }
        if(action.equals("video_capabilities")){
            if(com.qingfeng.music.agent.application.AgentChatPolicy.previousVideoPrice(text))return previousVideoQuote(owner,current);
            String w=value(args,"workId",current==null?null:current.songId()),a=value(args,"assetId",current==null?null:current.assetId());
            if(w==null)return new Result("尚未选择作品，请先从作品列表打开歌曲。","video",List.of(),List.of(new Command("查看作品","查看我的作品","send")),action);
            if(a==null){
                var items=new ArrayList<Item>();
                if(com.qingfeng.music.agent.application.AgentChatPolicy.videoPreviewOnly(text))
                    for(String prompt:starterPrompts(text))items.add(new Item("shot-"+(items.size()+1),"分镜 "+(items.size()+1),prompt,Map.of()));
                String draft=items.isEmpty()?"":"下面是可编辑的基础分镜草案。";
                String rates="按生成的视频时长计费，每秒 480P "+MusicVideoService.rate("480P")+" 积分、720P "+MusicVideoService.rate("720P")+" 积分、1080P "+MusicVideoService.rate("1080P")+" 积分。";
                return new Result(draft+"这首作品尚未选用音频。"+rates+"选用或上传音频后，就能按实际时长排分镜并计算总价。","video",items,List.of(new Command("查看音频","查看作品 "+w+" 的音频列表","send")),action);
            }
            var quote=service.quote(owner,w,a,"480P");
            var commands=resolutionChoices(w,a,quote.seconds());
            var items=new ArrayList<Item>();
            for(String resolution:RESOLUTIONS){
                int cost=quote.seconds()*MusicVideoService.rate(resolution);
                items.add(new Item(resolution,resolution,cost+" 积分（"+quote.seconds()+" 秒 × "+MusicVideoService.rate(resolution)+" 积分/秒）",Map.of()));
            }
            String next=commands.isEmpty()?"当前可以预览分镜和报价，视频生成服务暂不可用。":"请选择分辨率，核对分镜和总费用后再确认生成。";
            return new Result("歌曲时长 "+(quote.durationMillis()/1000.0)+" 秒，按 "+quote.seconds()+" 秒计费（不足一秒按一秒）。"+next,"video",items,commands,action);
        }
        String work=value(args,"workId",current==null?null:current.songId());
        if(Set.of("upload_video_image","open_music_video_editor").contains(action)){
            String asset=value(args,"assetId",current!=null&&Objects.equals(work,current.songId())?current.assetId():null);
            if(work==null||asset==null)throw new DomainException("VIDEO_AUDIO_REQUIRED","请先选择已有音频的作品。");
            service.quote(owner,work,asset,"480P");
            String target=json.writeValueAsString(Map.of("feature",action.equals("upload_video_image")?"mv_image":"mv","workId",work,"assetId",asset,"title",current==null?"音乐 MV":current.title()));
            return new Result("请在对话内选择图片或编辑分镜，尚未提交视频或扣费。","video",List.of(),List.of(new Command("打开 MV 操作面板",target,"feature")),action);
        }
        if(action.equals("list_music_videos")){if(work==null)throw new DomainException("VIDEO_WORK_REQUIRED","请先打开作品。");var rows=service.list(owner,work);return new Result(rows.isEmpty()?"还没有 MV 任务。":"这些是作品的 MV 任务。","video",rows.stream().map(r->new Item((String)r.get("id"),"MV",String.valueOf(r.get("state")),Map.of("projectId",r.get("id")))).toList(),rows.stream().map(r->new Command("查看 MV 进度","查看 MV 任务 "+r.get("id"),"send")).toList(),action);}
        String id=value(args,"projectId",null);
        if(action.equals("create_music_video")){
            var storyboard=plan(args);
            if(!(args.get("quotedCredits") instanceof Number quoted)||quoted.intValue()!=MusicVideoService.price(storyboard))throw new DomainException("VIDEO_QUOTE_CHANGED","MV 费用已变化，请重新确认分镜和费用。");
            id=service.create(owner,work,value(args,"assetId",current==null?null:current.assetId()),key,storyboard);
        }
        if(action.equals("retry_music_video_shot")){
            var before=service.get(owner,id);int index=shotIndex(args);
            var shots=json.valueToTree(before.get("shots"));
            var shot=java.util.stream.StreamSupport.stream(shots.spliterator(),false).filter(n->n.path("shot_index").asInt(-1)==index).findFirst().orElseThrow(()->new DomainException("INVALID_VIDEO_REQUEST","找不到该镜头。"));
            if(!Objects.equals(args.get("expectedPrompt"),shot.path("prompt").asText()))throw new DomainException("INVALID_VIDEO_REQUEST","镜头内容已变化，请重新确认。");
            service.retryShot(owner,id,index,value(args,"prompt",""));
        }
        if(action.equals("cancel_music_video"))service.cancel(owner,id);
        if(action.equals("retry_music_video_assembly"))service.retryAssembly(owner,id);
        var row=service.get(owner,id);var data=new LinkedHashMap<String,Object>();data.put("projectId",id);data.put("state",row.get("state"));data.put("shots",row.get("shots"));data.put("plan",json.readTree(String.valueOf(row.get("plan_json"))));if(row.get("error_code")!=null)data.put("errorCode",row.get("error_code"));if("READY".equals(row.get("state")))data.put("videoUrl","/api/app/music-videos/"+id+"/content");
        return new Result("MV 状态："+row.get("state")+"。"+(row.get("error_code")==null?"":("任务需处理："+row.get("error_code"))),"video",List.of(new Item(id,"剧情 MV",String.valueOf(row.get("state")),data)),List.of(new Command("查看进度","查看 MV 任务 "+id,"send")),action);
    }
    private Result previousVideoQuote(String owner,SongCard current){
        String work=current==null?null:current.songId();
        var previous=work==null||current.assetId()==null?Optional.<com.qingfeng.music.media.api.MediaApi.Asset>empty():service.previousAudio(owner,work,current.assetId());
        if(previous.isEmpty()){
            var select=work==null?new Command("查看作品","列出我的作品","send"):new Command("查看音频版本","查看作品 "+work+" 的音频列表","send");
            return new Result("请先选择要报价的音频版本，目前无法确定上一版。","video",List.of(),List.of(select),null);
        }
        var asset=previous.get();
        var quote=service.quote(owner,work,asset.id(),"480P");
        long balance=service.availableCredits(owner);
        var items=new ArrayList<Item>();
        for(String resolution:List.of("480P","720P","1080P")){
            int cost=quote.seconds()*MusicVideoService.rate(resolution);
            String funds=balance>=cost?"积分足够":"还差 "+(cost-balance)+" 积分";
            items.add(new Item(resolution,resolution,cost+" 积分 · "+funds,Map.of("workId",work,"assetId",asset.id(),"cost",cost,"balance",balance)));
        }
        return new Result("《"+current.title()+"》音频 V"+asset.versionNumber()+"，按 "+quote.seconds()+" 秒报价。你的可用余额为 "+balance+" 积分。","video",items,List.of(),"video_capabilities");
    }
}
