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
        String surface=surface(text);
        if(has(surface,"(?:只|仅)(?:要|帮我|给我)?(?:写|生成|创作|改|改写)?(?:一(?:首|段|版)|这首|这段)?歌词|lyrics only|only (?:write|generate).*lyrics"))return true;
        return has(surface,"先(?:帮我|给我)?(?:写|生成|创作|改|改写)?(?:一(?:首|段|版)|这首|这段)?歌词")
                && !requested(surface,MUSIC);
    }
    public static boolean deniesAction(String text) {
        return has(text, "(?:不要|别|禁止|不想|不需要|先不|暂不|不用)[^，。；,;]{0,10}(?:生成|写歌|写一首|创作|音乐|歌曲|歌词|改词|修改|改写|重写|风格|分享|公开|发布|点赞|下载|延长)|(?:don.t|do not|never)[^,;.]{0,18}(?:generat|creat|writ|edit|chang|shar|publish|like|extend)");
    }
    public static boolean chatOnly(String text) {
        if(has(text,"只想聊|只是聊|先聊|今天只想|什么是|你是什么|你是谁|你能.*做什么|如何|怎么|how (?:do|can)|what (?:is|are)|who are you"))return true;
        // Polite requests to act for the user may enter the concrete confirmation flow.
        // Capability questions without a requested task remain conversation only.
        return has(text,"能不能|可以吗")&&!has(surface(text),"(?:帮我|给我|为我|替我)(?:来|先|再|把这首歌|把当前作品)?(?:写|创作|生成|改|制作|分享|下载|播放|删除|润色|保存)");
    }
    public static boolean musicRequested(String text) {
        return !lyricsOnly(text)&&requested(text,MUSIC);
    }
    public static boolean lyricsRequested(String text) {
        return requested(text,"(?:写|创作|生成|编|改|修改|改写|重写|优化|润色|续写).{0,12}(?:歌词|词|段|一句)|(?:写|创作)(?:一|这)?首歌|(?:主歌|副歌).{0,10}(?:改|短|长|温柔|力量)|(?:write|rewrite|change|edit|generate).{0,15}(?:lyrics|chorus|verse)|write.{0,10}song");
    }
    public static boolean settingsRequested(String text) {
        return requested(text,"(?:改|换|设|调|加|减少|增加|去掉|用).{0,12}(?:风格|声|bpm|拍|钢琴|吉他|乐器|语|配器|民谣|摇滚|爵士|流行|电子)|\\d{2,3}\\s*(?:bpm|拍)|change.{0,12}(?:style|voice|tempo|instrument)");
    }
    public static boolean confirmed(String text) {
        return text != null && text.trim().matches("(?i)(确认生成|确认开始|确认执行|确认删除|确认处理|继续执行|确认|开始生成|生成吧|yes|confirm|go ahead)[。！! ]*");
    }
    public static boolean explicitAction(String action, String text) {
        return switch (action) {
            case "revoke_share" -> requested(text, "撤销分享|取消分享|关闭分享|revoke.*share");
            case "share" -> requested(text, "分享|\\bshare\\b") && !has(surface(text),"撤销分享|取消分享|关闭分享|revoke");
            case "unlike" -> requested(text, "取消(?:点赞|喜欢)|不再喜欢|\\bunlike\\b");
            case "like" -> requested(text, "点赞|喜欢这首|\\blike (?:it|this|the song)") && !has(surface(text), "取消(?:点赞|喜欢)|不再喜欢|unlike");
            case "cancel" -> requested(text, "取消生成|停止生成|停止任务|取消任务|\\bcancel\\b|stop generat");
            case "download" -> requested(text, "下载|\\bdownload\\b");
            case "play" -> requested(text, "播放|试听|听一下|\\bplay\\b");
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
    private static final String MUSIC="(?:生成|制作|开始做|做成).{0,10}(?:音乐|歌曲|歌|音频)|(?:生成|开始生成)(?:吧|音乐|歌曲|这首|一首|$)|(?:写|来|做|唱)(?:一|个|这)?首歌|generate.{0,12}(?:music|song|audio)|make.{0,12}(?:music|song)";
    private static final Set<String> READ_ACTIONS=Set.of("list_works","list_versions","list_audio","list_covers","discover_music","list_tasks",
            "get_credits","list_credit_entries","get_usage","get_account","get_referral","list_orders","get_order","payment_catalog","get_current_song",
            "get_music_settings","music_settings_catalog","preview_music_settings","get_playback_lyrics","list_section_edits","section_context");
    private static String surface(String text) {
        if(text==null)return "";
        // Quoted commands are data. A quoted song title can still follow an explicit verb.
        return text.replaceAll("“[^”]*”|‘[^’]*’|「[^」]*」|\"[^\"]*\"|'[^']*'", " ");
    }
    private static boolean discussion(String text) {
        return chatOnly(text)||has(text,"[？?]|歌词(?:里|中)|介绍|解释|讨论|举例|功能|含义|是否|怎样|什么|为何|为什么|我想知道|explain|describe|example|meaning|\\bwhy\\b|\\bwhat\\b");
    }
    /** A denial only blocks the action it names; '不要分享，生成音乐' still permits generation. */
    private static boolean requested(String text,String expression) {
        String value=surface(text);
        if(discussion(value))return false;
        boolean positive=false;
        for(String clause:value.split("[，。；,;\\n]|(?:然后|接着)")) {
            var matcher=Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(clause);
            while(matcher.find()) {
                String prefix=clause.substring(0,matcher.start());
                if(has(prefix,"(?:不|别|禁止|没有|未要求|已经|刚才)[^，。；,;]{0,18}$|(?:don.t|do not|never|not to|already)[^,;.]{0,24}$"))return false;
                positive=true;
            }
        }
        return positive;
    }
    /** Fast path is restricted to one current-work operation; numbered/named targets require planning. */
    public static String currentSocial(String text) {
        if(text==null||has(text,"[，。；,;]|然后|接着|再|第|上(?:一|个|首|版)|下(?:一|个|首|版)|《|》|[“”\"‘’「」]|workId|assetId"))return null;
        if(!has(text,"^\\s*(?:(?:请(?:你|帮我)?|帮我|给我|给|麻烦(?:你)?|现在|我想|我要|把|please)\\s*)*(?:(?:这首(?:歌|作品)?|当前(?:歌曲|作品)|它)\\s*)?(?:分享|创建分享|播放|试听|听一下|下载|点赞|喜欢这首|取消|停止|撤销|关闭分享|share|play|download|like|unlike|cancel|stop|revoke)"))return null;
        // Names after verbs should be resolved via the model, not silently redirected to current work.
        String compact=text.replaceAll("\\s+","").replaceAll("^(请你|请帮我|请|帮我|给我|给|麻烦你|现在|我想|我要|把)+","");
        if(!has(compact,"^(?:(?:当前(?:歌曲|作品)|这首(?:歌|作品)?|它))?(?:分享|创建分享|播放|试听|听一下|下载|点赞|喜欢这首|取消点赞|取消喜欢|取消分享|停止生成|取消生成|停止任务|取消任务|撤销分享|关闭分享)(?:(?:当前(?:歌曲|作品)|这首(?:歌|作品)?|它))?[！!。]*$"))return null;
        return explicitSocial(text);
    }
    public static boolean requiresConfirmation(String action) {
        return Set.of("delete_work","erase_analytics","close_checking").contains(action);
    }
    public static boolean actionRequested(String action,String text) {
        if(action==null)return false;
        return switch(action) {
            case "chat" -> true;
            case "generate_song" -> musicRequested(text);
            case "generate_lyrics" -> lyricsRequested(text);
            case "set_style" -> settingsRequested(text);
            case "modify_section" -> requested(text,"(?:修改|替换|重做|重唱|改).{0,16}(?:音频|片段|秒)|(?:音频|片段).{0,10}(?:修改|替换)|(?:edit|replace).{0,15}(?:audio|section)");
            case "extend_song" -> false;
            case "play","download","share","revoke_share","like","unlike","cancel" -> explicitAction(action,text);
            default -> applicationAllowed(action,text);
        };
    }
    /** Writes require independent intent, even when a model supplies a valid catalog ID. */
    public static boolean applicationAllowed(String action,String text) {
        if(action==null)return false;
        if(READ_ACTIONS.contains(action))return true;
        return switch(action) {
            case "create_work" -> requested(text,"(?:新建|创建|建立|开).{0,12}(?:草稿|作品|空白歌曲|空白项目)|(?:create|new).{0,12}(?:draft|work|blank song)")
                    ||applicationAllowed("save_lyrics",text)||applicationAllowed("upload_audio",text);
            case "select_work","select_version","select_audio","select_cover" -> requested(text,"选择|选中|选|打开|切换|切回|用|第[一二三四五六七八九十0-9]+|上一版|下一版|上一首|下一首|播放|下载|分享|改名|重命名|删除|修改|继续|查看|撤销|点赞|\\bselect\\b|\\bopen\\b|\\bplay\\b|\\bprevious\\b");
            case "rename_work" -> requested(text,"改名|重命名|(?:名字|名称|歌名|标题)(?:改|换|设)|命名|rename");
            case "update_work" -> requested(text,"(?:修改|更新|设置|保存|更改|改|换).{0,15}(?:收礼人|收件人|场景|故事|寄语|标题|歌名|送给)|(?:收礼人|收件人|场景|故事|寄语|标题|歌名).{0,10}(?:改|换|设)|(?:update|set|save).{0,15}(?:recipient|occasion|story|message|title)");
            case "delete_work" -> requested(text,"删除|删掉|移除作品|delete|remove.{0,12}(?:song|work)");
            case "edit_cover" -> requested(text,"(?:生成|画|绘制|制作|修改|换|重做|设计).{0,15}(?:封面|cover)|(?:封面|cover).{0,12}(?:改|换)|(?:generate|edit|create).{0,12}cover");
            case "upload_cover" -> requested(text,"(?:上传|导入|选择文件).{0,12}(?:封面|图片)|upload.{0,12}(?:cover|image)");
            case "upload_audio" -> requested(text,"(?:上传|导入|选择文件).{0,12}(?:音频|音乐|歌曲|伴奏)|upload.{0,12}(?:audio|music)");
            case "favorite_music" -> requested(text,"收藏|加入收藏|favorite|bookmark")&&!has(surface(text),"取消收藏|移出收藏|删除收藏|unfavorite|unbookmark");
            case "unfavorite_music" -> requested(text,"取消收藏|移出收藏|删除收藏|unfavorite|unbookmark");
            case "like_sample" -> explicitAction("like",text);
            case "unlike_sample" -> explicitAction("unlike",text);
            case "rewrite_inspiration" -> requested(text,"(?:改写|润色|优化|整理|扩写).{0,15}(?:灵感|创意|想法|提示词)|(?:灵感|创意|想法).{0,12}(?:改写|润色|优化)|rewrite.{0,15}(?:idea|inspiration|prompt)");
            case "retry_archive" -> requested(text,"(?:重试|重新|恢复).{0,12}(?:归档|保存|下载)|retry.{0,12}archiv");
            case "close_checking" -> requested(text,"(?:关闭|结束|终止).{0,12}(?:核查|核对|检查)|close.{0,12}check");
            case "save_lyrics" -> requested(text,"(?:保存|使用|用|采用|确认).{0,15}(?:歌词|这些词)|save.{0,12}lyrics");
            case "update_music_settings" -> settingsRequested(text)||requested(text,"(?:更新|修改|设置|保存|更换|用).{0,15}(?:音乐参数|创作参数|参数|模板|预设|配方|负面提示|提示词)|(?:update|save|set).{0,12}(?:settings|recipe|prompt)");
            case "simplify_music_brief" -> requested(text,"(?:精简|简化|压缩|整理).{0,15}(?:描述|创作要求|音乐要求|提示词|brief)|simplify.{0,15}(?:brief|prompt)");
            case "rewrite_lyric_line" -> requested(text,"(?:改写|重写|润色|修改|改短|缩短|押韵|口语化).{0,15}(?:这句|这一句|一句|这行|歌词)|(?:这句|这一句|这行).{0,15}(?:改|重写|润色|押韵|短|口语)|(?:rewrite|shorten|rhyme).{0,15}(?:line|sentence)");
            case "sync_playback_lyrics" -> requested(text,"(?:同步|对齐|生成|更新|修复).{0,15}(?:滚动歌词|播放歌词|时间轴|时间戳|歌词时间)|(?:sync|align).{0,15}(?:lyrics|timestamps)");
            case "accept_referral" -> requested(text,"(?:绑定|使用|填入|接受).{0,12}(?:邀请码|邀请|推荐码)|(?:accept|use).{0,12}(?:referral|invite)");
            case "recharge" -> requested(text,"充值|购买.{0,12}(?:积分|点数|套餐)|recharge|top.?up|buy.{0,12}(?:credit|plan)");
            case "close_order" -> requested(text,"(?:关闭|取消|撤销).{0,12}(?:订单|支付)|(?:close|cancel).{0,12}(?:order|payment)");
            case "login" -> requested(text,"登录|登入|log.?in|sign.?in");
            case "logout" -> requested(text,"退出登录|登出|注销登录|log.?out|sign.?out");
            case "account_settings" -> requested(text,"(?:打开|修改|设置|更改|绑定|解绑|关联|恢复).{0,12}(?:账户|账号|邮箱|手机|身份)|(?:账户|账号)设置|account settings");
            case "privacy_settings" -> requested(text,"(?:打开|修改|设置|查看|撤回|撤销|关闭).{0,12}(?:隐私|分析|同意)|隐私设置|privacy settings");
            case "erase_analytics" -> requested(text,"(?:清除|清空|删除|擦除).{0,12}(?:分析|行为|统计|隐私|个人数据)|(?:erase|delete).{0,12}(?:analytics|data)");
            default -> false;
        };
    }
}
