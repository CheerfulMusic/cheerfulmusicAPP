package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentChatModel.Plan;
import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.application.AgentChatPolicy;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.credits.CreditsPricing;
import com.qingfeng.music.credits.LyricFreeAllowance;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Button choices narrow the available actions. Only a persisted quote can approve a debit. */
public record AgentCreationRequest(Action action, String brief, String choice) {
    AgentCreationRequest(Action action,String brief){this(action,brief,null);}
    String direction(){return choice==null||choice.isBlank()||choice.equals(brief)?brief:choice+"，"+brief;}
    public enum Action {
        POLISH_LYRICS("帮我修改当前作品的歌词。", "Rewrite the lyrics of my current song."),
        GENERATE_SONG("帮我生成音乐，我想要的曲风是", "Generate music in this style"),
        PREVIEW_MV("帮我设计当前歌曲的 MV，先给我分镜方案和费用，等我确认后再生成。",
                "Plan a music video for my current song. Show me the storyboard and cost and wait for my approval before generating the video.");
        final String zh, en;
        Action(String zh, String en) { this.zh=zh; this.en=en; }
    }

    /** The same quoted command has one turn identity across clients, transports and retries. */
    static String turnKey(String text, String transportKey) {
        if (text == null || !text.strip().startsWith("#approve:")) return transportKey;
        try {
            if (!UUID.fromString(transportKey).toString().equalsIgnoreCase(transportKey)) throw new IllegalArgumentException();
            String token = text.strip().substring("#approve:".length());
            if (UUID.fromString(token).toString().equals(token)) return token;
        } catch (RuntimeException invalid) {
            throw new DomainException("INVALID_REQUEST_KEY", "缺少有效的报价或消息标识。");
        }
        throw new DomainException("INVALID_REQUEST_KEY", "报价标识无效，请重新获取报价。");
    }

    static AgentCreationRequest from(Action action, String text, String choice) {
        if (action != null) {
            if(choice!=null && choice.length()>80)throw new DomainException("INVALID_AGENT_MESSAGE","创作选项过长，请重新选择。");
            return new AgentCreationRequest(action,text,choice);
        }
        for (var legacy : Action.values()) {
            for (var prefix : new String[]{legacy.zh, legacy.en}) {
                if (text.startsWith(prefix)) return new AgentCreationRequest(legacy, text.substring(prefix.length()).strip());
            }
        }
        return null;
    }

    private static final Pattern PRICE = Pattern.compile("只.{0,6}(?:价|费用|报价|积分|多少钱)|(?:看|查|问|了解|多少).{0,5}(?:价格|费用|报价|积分)|多少钱|price|quot(?:e|ation)|how much|cost",Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP = Pattern.compile("取消|算了|不用了|等一等|等等|等我确认|先缓|先不做|不做了|先别|暂缓|稍后再|停一下|(?:别|不|不要|不用|停止|暂不|先不)[^，。；,;.\\r\\n]{0,5}(?:生成|制作|打磨|改写|创作|执行)|cancel|stop|hold off|wait|don.t (?:generate|create|rewrite)|do not (?:generate|create|rewrite)|not yet",Pattern.CASE_INSENSITIVE);
    private static final Pattern REWRITE = Pattern.compile("(?:打磨|润色|改写|重写).{0,16}(?:词|主歌|副歌|桥段)|(?:歌词|主歌|副歌|桥段).{0,12}(?:打磨|润色|改写|重写)|(?:polish|refine|rewrite).{0,18}(?:lyrics|verse|chorus)", Pattern.CASE_INSENSITIVE);
    static boolean rewriteRequested(String text) { return REWRITE.matcher(text).find(); }
    static boolean priceOnly(String text) { return PRICE.matcher(text).find() || AgentChatPolicy.generalPriceOnly(text); }
    static boolean paused(String text) { return STOP.matcher(text).find(); }
    static Plan reply(String text) { return new Plan("chat",Map.of(),text); }

    String instruction() {
        return switch(action) {
            case PREVIEW_MV -> Action.PREVIEW_MV.zh+" "+direction();
            case POLISH_LYRICS -> "请打磨当前作品的歌词。"+direction();
            case GENERATE_SONG -> brief.matches("(?i)(?:用当前歌词生成音乐|生成歌曲|generate music from the current lyrics)[。.!！]*") && choice==null
                    ? "用当前歌词生成音乐" : "用当前歌词生成音乐，曲风是"+direction();
        };
    }

    Plan plan(SongCard target, CreditsPricing pricing, LyricFreeAllowance allowance, String owner) {
        if (AgentChatPolicy.songStatusRequested(brief)) return new Plan("get_current_song",Map.of(),"");
        boolean price=priceOnly(brief);
        if (paused(brief) && !price && !(action==Action.PREVIEW_MV && brief.matches("(?is).*(?:先看|只要|只看|给我|show|preview).*(?:分镜|storyboard).*"))) return reply("已停在这一步。你可以继续描述想法或查看报价。");
        if (action!=Action.PREVIEW_MV && price) {
            if(action==Action.POLISH_LYRICS && (target==null||target.revisionId()==null))
                return reply("请先选择歌词版本，我会按该作品的免费次数和当前价格报价。");
            return reply("本次"+(action==Action.GENERATE_SONG?"生成歌曲":"打磨歌词")+"的创作费用为 "+
                    amount(pricing,allowance,owner,target,action==Action.GENERATE_SONG?"generate_song":"generate_lyrics")+" 积分。");
        }
        if (action==Action.PREVIEW_MV) return new Plan("video_capabilities",Map.of(),"");
        if(target==null || target.revisionId()==null) return reply("请先打开要处理的作品和歌词版本，再确认本次创作。");
        if(AgentChatPolicy.chatOnly(brief) || brief.matches("(?is).*(?:[?？]|可能|也许|上一版|另一首|换一首|还是|maybe|another (?:song|version)).*"))
            return reply("你希望对哪首歌的哪一版执行什么操作？可以先选定版本，再告诉我。");
        if(action==Action.POLISH_LYRICS) return new Plan("generate_lyrics",Map.of("mode","rewrite","revisionId",target.revisionId(),"text",brief.isBlank()?"打磨当前歌词":direction()),"");
        return new Plan("generate_song",Map.of("mode","regenerate","revisionId",target.revisionId(),"text",instruction()),"");
    }

    static long amount(CreditsPricing pricing,LyricFreeAllowance allowance,String owner,SongCard target,String action) {
        if(pricing==null) throw new DomainException("AGENT_PRICE_UNAVAILABLE","暂时无法核实创作费用，请稍后重新报价。");
        if(!pricing.enabled()) return 0;
        if("generate_song".equals(action)) return pricing.cost(CreditsPricing.Kind.MUSIC);
        if(target==null || allowance==null) throw new DomainException("AGENT_PRICE_UNAVAILABLE","请先选择作品，再查看这版歌词的费用。");
        return allowance.freeAvailable(owner,target.songId())?0:pricing.cost(CreditsPricing.Kind.LYRICS);
    }
}
