package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.IntentClassifier;
import com.qingfeng.music.agent.api.IntentResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两级意图路由：规则层强制直出明确指令，LLM 只兜底开放输入。
 * 任何无法判定或低于置信度的情况都返回 needClarify=true，绝不猜测工具、绝不触发付费调用。
 */
public final class IntentRouter {

    private static final int TEXT_LIMIT = 4000;
    private static final Pattern BPM = Pattern.compile("(\\d{2,3})\\s*(?:bpm|BPM|拍)|每分钟\\s*(\\d{2,3})\\s*拍");
    private static final List<String> INSTRUMENTS = List.of(
            "钢琴", "吉他", "弦乐", "小提琴", "大提琴", "贝斯", "鼓", "打击乐",
            "合成器", "电子音色", "古筝", "二胡", "琵琶", "长笛", "萨克斯", "手风琴", "口琴");

    /** 有序规则表：操作类优先于创作类，避免「换个民谣风格」被当成写新歌。 */
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("换一版|再来一版|重新生成|重新写|重做一版|another version|remake|regenerate|one more version", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_SONG, "好的，我按刚才的想法再生成一版。"),
            new Rule(Pattern.compile("(?:换|改成|改成|调整为|切换)[^，。]{0,12}风格|change (?:the )?style|switch style|make it .*style", Pattern.CASE_INSENSITIVE),
                    AgentTool.SET_STYLE, "好的，我来调整这首歌的风格。"),
            new Rule(Pattern.compile("延长|加长|续写|再长一点|extend|make it longer|add .*seconds", Pattern.CASE_INSENSITIVE),
                    AgentTool.EXTEND_SONG, "好的，我来为这首歌续一段。"),
            new Rule(Pattern.compile("改歌词|改写歌词|重写歌词|rewrite (?:the )?lyrics|change (?:the )?lyrics", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_LYRICS, "好的，我来改写歌词。"),
            new Rule(Pattern.compile("局部|第[一二三四五六七八九十0-9]+段|改[^，。]{0,8}段|replace section|section edit", Pattern.CASE_INSENSITIVE),
                    AgentTool.MODIFY_SECTION, "好的，我来修改这一段。"),
            new Rule(Pattern.compile("播放|试听|放来听|play (?:the )?(?:song|track)|play it", Pattern.CASE_INSENSITIVE),
                    AgentTool.GET_CURRENT_SONG, "这就为你播放当前歌曲。"),
            new Rule(Pattern.compile("下载|download", Pattern.CASE_INSENSITIVE),
                    AgentTool.GET_CURRENT_SONG, "当前歌曲的下载地址在这里。"),
            new Rule(Pattern.compile("写一首|来一首|唱一首|创作|生成一首|做一首|make (?:me )?a song|write (?:me )?a song|compose|create a song", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_SONG, "好的，我来按这个想法写一首歌。"),
            new Rule(Pattern.compile("粤语|广东话|cantonese", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_SONG, "好的，用粤语来写。"),
            new Rule(Pattern.compile("\\d{2,3}\\s*(?:bpm|BPM|拍)|每分钟\\s*\\d{2,3}\\s*拍|加(?:上)?[一-龥]{0,4}(?:钢琴|吉他|鼓|弦乐|古筝|二胡)", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_SONG, "好的，我按你说的速度和配器来写。"),
            new Rule(Pattern.compile("治愈系|电影风格|夏日海岛|旅行|生日快乐|国风|古韵|摇滚|民谣|电子|轻音乐|情歌", Pattern.CASE_INSENSITIVE),
                    AgentTool.GENERATE_SONG, "好的，我按这个风格来写。")
    );

    private record Rule(Pattern pattern, AgentTool tool, String reply) {}

    private final IntentClassifier classifier;
    private final boolean llmEnabled;
    private final double confidenceThreshold;

    public IntentRouter(IntentClassifier classifier, boolean llmEnabled, double confidenceThreshold) {
        this.classifier = classifier;
        this.llmEnabled = llmEnabled;
        this.confidenceThreshold = confidenceThreshold;
    }

    public IntentResult route(String text, String locale) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty() || clean.length() > TEXT_LIMIT) {
            return IntentResult.clarify("请用一句话告诉我你想做什么，例如「写一首关于旅行的歌」。");
        }
        // Negations and conversation requests must be resolved before keyword rules.
        if (AgentChatPolicy.chatOnly(clean) || (AgentChatPolicy.deniesAction(clean) && !AgentChatPolicy.lyricsOnly(clean))
                || Pattern.compile("我喜欢|我想聊|什么是|解释一下|不要下载|不下载|don.t download",Pattern.CASE_INSENSITIVE).matcher(clean).find()) {
            return IntentResult.clarify("可以先聊聊创作想法，本次不会执行创作工具。");
        }
        if (AgentChatPolicy.lyricsOnly(clean) || Pattern.compile("(?:写|生成|创作).{0,4}歌词|(?:write|generate).{0,8}lyrics",Pattern.CASE_INSENSITIVE).matcher(clean).find()) {
            return IntentResult.rule(AgentTool.GENERATE_LYRICS.id(),extractArgs(clean),"好的，先为你创作歌词。");
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(clean).find()) {
                return IntentResult.rule(rule.tool().id(), extractArgs(clean), rule.reply());
            }
        }
        if (llmEnabled && classifier != null && classifier.isConfigured()) {
            IntentResult classified = classifier.classify(clean, locale);
            if (classified != null && !classified.needClarify()
                    && AgentTool.fromId(classified.tool()) != null
                    && classified.confidence() >= confidenceThreshold) {
                return classified;
            }
        }
        return IntentResult.clarify("我不太确定你的意思。可以说「写一首…」「换成…风格」「延长」或「换一版」。");
    }

    /** 规则层同时抽取明确说过的参数；抽不到绝不编造。 */
    private static Map<String, Object> extractArgs(String clean) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("text", clean);
        Matcher bpm = BPM.matcher(clean);
        if (bpm.find()) {
            String raw = bpm.group(1) != null ? bpm.group(1) : bpm.group(2);
            int value = Integer.parseInt(raw);
            if (value >= 40 && value <= 220) args.put("bpm", value);
        }
        if (clean.contains("粤语") || clean.contains("广东话") || clean.toLowerCase().contains("cantonese")) {
            args.put("language", "yue");
        } else if (clean.contains("英文") || clean.contains("英语") || clean.toLowerCase().contains("english")) {
            args.put("language", "en");
        } else if (clean.contains("中文") || clean.contains("普通话") || clean.contains("国语")) {
            args.put("language", "zh");
        }
        if (clean.contains("女声") || clean.contains("女歌手")) args.put("vocal", "FEMALE");
        else if (clean.contains("男声") || clean.contains("男歌手")) args.put("vocal", "MALE");
        List<String> instruments = new ArrayList<>();
        for (String name : INSTRUMENTS) if (clean.contains(name)) instruments.add(name);
        if (!instruments.isEmpty()) args.put("instruments", List.copyOf(instruments));
        return args;
    }
}
