package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentApplicationActions.Capability;
import java.util.List;
import java.util.regex.Pattern;

/** Strict whole-message lookup, shared by the normal entry point and planner outage fallback. */
public final class AgentReadOnlyLookup {
    private AgentReadOnlyLookup() {}

    private static final Pattern WORKS = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查看|查询|列出|显示|看看|看一下|看下)?我的(?:作品|歌曲)(?:列表|清单)?"
            + "|(?:请)?(?:查看|查询|查一下|查|列出|显示)作品(?:列表|清单)?"
            + "|我(?:有|创作了)(?:哪些|什么)(?:作品|歌曲)"
            + "|(?:please )?(?:show|list)(?: me)? my (?:songs|works))", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDITS = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查询|查看|查一下|查|显示|看看|看一下|看下)(?:我的|我|当前账号的)?(?:积分|积分余额|剩余积分)"
            + "|(?:我的)?(?:积分余额|剩余积分)"
            + "|我(?:还)?有多少积分"
            + "|(?:我的)?积分(?:还)?(?:剩余|剩下|剩)?多少"
            + "|(?:please )?(?:show|check)(?: me)? my (?:credits|credit balance)"
            + "|how many credits do i have)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAPABILITIES = Pattern.compile(
            "(?:(?:请|麻烦)?(?:帮我)?(?:查看|列出|显示|介绍)(?:全部|所有|可用|已接入)?(?:功能|能力)(?:清单|列表)?"
            + "|能力清单|你(?:能|可以)(?:为我|帮我)?做什么|有什么功能"
            + "|(?:please )?(?:show|list)(?: me)? (?:available )?(?:features|capabilities)"
            + "|what can you do)", Pattern.CASE_INSENSITIVE);

    public static String match(String message,List<Capability> capabilities,boolean includeCatalog) {
        if(message==null||message.length()>160)return null;
        String text=message.strip().replaceFirst("[。.!！?？]+$", "").strip();
        String action=WORKS.matcher(text).matches()?"list_works":CREDITS.matcher(text).matches()?"get_credits":
            includeCatalog&&CAPABILITIES.matcher(text).matches()?"list_capabilities":null;
        if(action==null)return null;
        var matching=capabilities.stream().filter(c->action.equals(c.id())).toList();
        return matching.size()==1&&"read".equals(matching.get(0).effect())?action:null;
    }
}
