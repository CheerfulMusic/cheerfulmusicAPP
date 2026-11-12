package com.qingfeng.music.agent.application;

import java.util.Set;
import java.util.regex.Pattern;

/** Conservative execution policy, independent of model confidence and prompt content. */
public final class AgentChatPolicy {
    private AgentChatPolicy() {}
    private static boolean has(String text, String pattern) {
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text == null ? "" : text).find();
    }
    public static boolean lyricsOnly(String text) {
        return has(text, "(?:只|先|仅)(?:要|帮我|给我)?(?:写|生成|创作|改|改写)?(?:一(?:首|段|版)|这首|这段)?歌词|lyrics only|only (?:write|generate).*lyrics");
    }
    public static boolean deniesAction(String text) {
        return has(text, "(?:不要|别|禁止|不想|不需要|先不|暂不|不用)[^，。；,;]{0,10}(?:生成|写歌|写一首|创作|音乐|歌曲|歌词|改词|修改|改写|重写|风格|分享|公开|发布|点赞|下载|延长)|(?:don.t|do not|never)[^,;.]{0,18}(?:generat|creat|writ|edit|chang|shar|publish|like|extend)");
    }
    public static boolean chatOnly(String text) {
        return has(text, "只想聊|只是聊|先聊|今天只想|什么是|你是什么|你是谁|你能.*做什么|如何|怎么|能不能|可以吗|how (?:do|can)|what (?:is|are)|who are you");
    }
    public static boolean musicRequested(String text) {
        return !deniesAction(text) && !lyricsOnly(text) && !chatOnly(text)
                // Mentioning a command inside lyrics or asking about a feature does not authorize spending.
                // Ambiguous requests can still use the explicit confirmation flow.
                && !has(text, "[“”\"'‘’「」？?]|歌词(?:里|中)|介绍|解释|讨论|举例|功能|含义|explain|describe|example|meaning")
                && has(text, "(?:生成|制作|开始做|做成).{0,10}(?:音乐|歌曲|歌|音频)|(?:生成|开始生成)(?:吧|音乐|歌曲|这首|一首|$)|(?:写|来|做|唱)(?:一|个|这)?首歌|generate.{0,12}(?:music|song|audio)|make.{0,12}(?:music|song)");
    }
    public static boolean lyricsRequested(String text) {
        return !chatOnly(text) && (!deniesAction(text)||lyricsOnly(text))
                && has(text,"(?:写|创作|生成|编|改|修改|改写|重写|优化|润色|续写).{0,12}(?:歌词|词|歌|段|一句)|(?:主歌|副歌).{0,10}(?:改|短|长|温柔|力量)|(?:write|rewrite|change|edit|generate).{0,15}(?:lyrics|song|chorus|verse)");
    }
    public static boolean settingsRequested(String text) {
        return !chatOnly(text) && has(text,"(?:改|换|设|调|加|减少|增加|去掉|用).{0,12}(?:风格|声|bpm|BPM|拍|钢琴|吉他|乐器|语|配器)|\\d{2,3}\\s*(?:bpm|BPM|拍)|change.{0,12}(?:style|voice|tempo|instrument)");
    }
    public static boolean confirmed(String text) {
        return text != null && text.trim().matches("(?i)(确认生成|确认开始|确认|开始生成|生成吧|yes|confirm|go ahead)[。！! ]*");
    }
    public static boolean explicitAction(String action, String text) {
        if (deniesAction(text) || chatOnly(text) || has(text,"[“”\"'‘’「」？?]|功能|什么|是否|怎样|歌词里|我想知道|解释|讨论|举例")) return false;
        if(!has(text,"^\\s*(?:(?:请(?:你|帮我)?|帮我|给我|给|麻烦(?:你)?|现在|我想|我要|把|please)\\s*)*(?:(?:这首(?:歌|作品)|当前(?:歌曲|作品)|它)\\s*)?(?:分享|创建分享|播放|试听|听一下|下载|点赞|喜欢这首|取消|停止|撤销|关闭分享|share|play|download|like|unlike|cancel|stop|revoke)"))return false;
        return switch (action) {
            case "revoke_share" -> has(text, "撤销分享|取消分享|关闭分享|revoke.*share");
            case "share" -> has(text, "分享|share") && !has(text,"撤销|取消|关闭|revoke");
            case "unlike" -> has(text, "取消(?:点赞|喜欢)|不再喜欢|unlike");
            case "like" -> has(text, "点赞|喜欢这首|like (?:it|this|the song)") && !has(text, "取消|不再|unlike");
            case "cancel" -> has(text, "取消生成|停止生成|停止任务|取消任务|cancel|stop generat");
            case "download" -> has(text, "下载|download");
            case "play" -> has(text, "播放|试听|听一下|play");
            default -> false;
        };
    }
    public static String explicitSocial(String text) {
        for (String action : new String[]{"cancel", "unlike", "like", "revoke_share", "share", "download", "play"}) {
            if (explicitAction(action, text)) return action;
        }
        return null;
    }
    public static boolean paidMusicAction(String action) {
        return Set.of("generate_song", "extend_song", "modify_section").contains(action);
    }
}
