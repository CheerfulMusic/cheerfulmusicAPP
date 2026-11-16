package com.qingfeng.music.server.mv;
import com.qingfeng.music.agent.api.AgentApplicationActions;
import com.qingfeng.music.agent.api.SongCard;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="qingfeng.video.enabled",havingValue="false",matchIfMissing=true)
public class DisabledVideoConfiguration {
 @Bean AgentApplicationActions disabledVideoActions(){return new AgentApplicationActions(){
  public List<Capability> capabilities(){return List.of(new Capability("video_capabilities","查询 AI 剧情 MV 是否启用；制作前先查询。尚未配置时只讨论分镜，不提交、不扣费。","","read"));}
  public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current){return new Result("AI 剧情 MV 尚未启用。可以先设计剧情分镜；接入视频模型并配置费用后才能生成动态视频。","video",List.of(),List.of(),action);}
 };}
}
