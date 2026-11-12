package com.qingfeng.music.server.avatar;

import com.qingfeng.music.agent.api.AgentApplicationActions;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.common.exception.DomainException;
import java.util.List;
import java.util.Map;

/** Generates only the authenticated user's own avatar after the Agent confirmation. */
public final class AgentAvatarActions implements AgentApplicationActions {
    private final AvatarGenerationService service;
    public AgentAvatarActions(AvatarGenerationService service) { this.service=service; }
    public List<Capability> capabilities() { return List.of(new Capability("generate_avatar","按描述生成并设置当前账号头像；需确认，每张消耗5积分，生成失败或被更新头像替代时退还，结果待核实时保留积分核对；每24小时最多"+service.dailyLimit()+"次","prompt:头像画面要求，最多1000字","paid")); }
    public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current) {
        if(!"generate_avatar".equals(action))throw new DomainException("AVATAR_ACTION_INVALID","不支持该头像操作。");
        Object value=args.get("prompt");
        if(!(value instanceof String prompt)||prompt.isBlank())throw new DomainException("AVATAR_PROMPT_REQUIRED","请先描述想要的头像。");
        var task=service.start(owner,key,prompt);
        String reply=switch(task.status()) {
            case "UNKNOWN" -> "头像任务结果尚待核实，请勿重复提交。";
            case "FAILED" -> "头像生成失败；如本次已扣积分，已退还。";
            case "SUCCEEDED" -> "头像已生成并设置。";
            case "SUPERSEDED" -> "头像任务完成前你已更换头像，已保留更新的头像；如本次已扣积分，已退还。";
            default -> "头像生成任务已提交，完成后会自动设置；生成失败或期间更换头像时，已扣积分会退还。";
        };
        return new Result(reply,"avatar",List.of(),List.of(),"generate_avatar");
    }
}
