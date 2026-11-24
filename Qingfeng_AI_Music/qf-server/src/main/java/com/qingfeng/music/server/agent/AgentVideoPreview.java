package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentChatModel;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentVideoResolution;
import com.qingfeng.music.conversation.api.AgentMessage;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AgentVideoPreview {
    private AgentVideoPreview() {}

    static JsonNode latestResult(List<AgentMessage> history,ObjectMapper json) {
        for(int i=history.size()-1;i>=0;i--) {
            var message=history.get(i);
            if(!AgentMessage.KIND_TEXT.equals(message.kind()))continue;
            if("user".equals(message.role()))break;
            if(!"assistant".equals(message.role()))continue;
            var body=json.readTree(message.contentJson());
            if("progress".equals(body.path("kind").asText()))continue;
            if(Set.of("video_capabilities","preview_music_video").contains(body.path("action").asText("")))return body;
            break;
        }
        return null;
    }

    static AgentChatModel.Plan select(AgentVideoResolution choice,SongCard current,JsonNode last,boolean canCreate,ObjectMapper json) {
        if(current==null||!choice.workId().equals(current.songId())||!choice.assetId().equals(current.assetId()))
            return new AgentChatModel.Plan("chat",Map.of(),"歌曲或音频版本已切换，请重新打开要制作 MV 的音频并查看报价。");
        if(!canCreate)return new AgentChatModel.Plan("chat",Map.of(),"当前可以预览分镜和报价，视频生成服务暂不可用。");
        if(last!=null&&"video_plan".equals(last.path("kind").asText())) {
            var data=last.path("items").path(0).path("data");
            var plan=data.path("plan");
            if(choice.workId().equals(data.path("workId").asText())&&choice.assetId().equals(data.path("assetId").asText())&&plan.path("shots").isArray()) {
                var args=Map.<String,Object>of("workId",choice.workId(),"assetId",choice.assetId(),"resolution",choice.resolution(),
                        "title",plan.path("title").asText(),"ratio",plan.path("ratio").asText(),"shots",json.convertValue(plan.path("shots"),List.class));
                return new AgentChatModel.Plan("create_music_video",args,"");
            }
        }
        // Older cards may carry only a price. Show a current storyboard before offering confirmation.
        return new AgentChatModel.Plan("video_capabilities",Map.of(),"");
    }

    static AgentChatModel.Plan storyboard(AgentChatModel.Plan proposal,SongCard target,String brief) {
        // Only the visual content can come from the planner. Action, asset and pricing remain server-owned.
        var steps=proposal.orderedSteps();
        var args=new LinkedHashMap<String,Object>();
        if(steps.size()==1&&"preview_music_video".equals(steps.get(0).action())){
            var suggested=steps.get(0).args();
            if((!suggested.containsKey("workId")||Objects.equals(target.songId(),suggested.get("workId")))
                    &&(!suggested.containsKey("assetId")||Objects.equals(target.assetId(),suggested.get("assetId")))
                    &&suggested.get("shots") instanceof List<?> shots&&!shots.isEmpty()&&shots.size()<=120){
                var visuals=new ArrayList<Map<String,Object>>();
                for(var shot:shots) {
                    if(!(shot instanceof Map<?,?> fields)||!(fields.get("prompt") instanceof String prompt)||prompt.isBlank()||prompt.length()>2000)break;
                    var visual=new LinkedHashMap<String,Object>();visual.put("prompt",prompt);
                    if(brief.matches("(?is).*(?:图片|照片|image|photo).*")&&fields.get("imageId") instanceof String id&&fields.get("imageSeconds") instanceof Number seconds){
                        visual.put("imageId",id);visual.put("imageSeconds",seconds);
                    }
                    visuals.add(visual);
                }
                if(visuals.size()==shots.size())args.put("shots",visuals);
                if(suggested.get("ratio") instanceof String ratio&&Set.of("16:9","9:16","1:1").contains(ratio))args.put("ratio",ratio);
            }
        }
        if(!args.containsKey("shots"))args.put("starterBrief",brief);
        var requested=List.of("480P","720P","1080P").stream().filter(brief.toUpperCase(Locale.ROOT)::contains).toList();
        if(requested.size()==1)args.put("resolution",requested.get(0));
        args.put("workId",target.songId());args.put("assetId",target.assetId());
        return new AgentChatModel.Plan("preview_music_video",args,"");
    }
}
