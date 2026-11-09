package com.qingfeng.music.settings;

import com.qingfeng.music.work.api.WorkApi.Work;
import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import java.util.regex.Pattern;

/** 编译已保存的设置与创作计划；不调用模型，不修改已确认歌词，不截断用户要求。 */
public final class CreativeDirection {
    private CreativeDirection(){}
    private static final Pattern MUSIC=Pattern.compile("(?i)(唱腔|戏腔|呼麦|美声|男声|女声|人声|和声|合唱|独唱|说唱|曲风|配器|伴奏|前奏|间奏|副歌|主歌|桥段|旋律|节奏|律动|钢琴|吉他|鼓|贝斯|古筝|琵琶|笛|二胡|萨克斯|电子|摇滚|民谣|爵士|流行|[a-z]\\s*&\\s*b|bpm|vocals?|chorus|verse|tempo|piano|guitar|drums?|rap|instrument|synth|no sad|不要悲伤|不要苦情)");
    public static CreativePlan fallback(Work work){
        var clauses=new LinkedHashSet<String>();
        for(String part:(work.story()+"\n"+work.message()).split("[。；;！!\\n]"))if(MUSIC.matcher(part).find())clauses.add(part.trim());
        String raw=String.join("; ",clauses);
        if(raw.length()>600)throw new DomainException("MUSIC_REQUIREMENTS_TOO_LONG","音乐要求较长，请先生成歌词整理创作方案，或精简灵感中的制作要求。");
        String summary=raw.length()>180?raw.substring(0,180)+"…":raw;
        return new CreativePlan(raw,"","",raw.isBlank()?"按所选风格编配":summary,raw.length()>300?"Original music directions retained":raw,"AUTO");
    }
    public static MusicSettings.Direction compile(Work work,MusicSettings.Direction base,CreativePlan plan){
        return compile(work,base,plan,MusicPromptLimits.suno(),null);
    }
    public static MusicSettings.Direction compile(Work work,MusicSettings.Direction base,CreativePlan plan,MusicPromptLimits limits,String concise){
        return compile(work,base,plan,limits,concise,true);
    }
    public static MusicSettings.Direction compile(Work work,MusicSettings.Direction base,CreativePlan plan,MusicPromptLimits limits,String concise,boolean currentOverrides){
        if(plan==null)plan=fallback(work);
        if(base!=null&&plan.requirements().isBlank()&&plan.suggestedArrangement().isBlank()&&plan.negativeTags().isBlank()&&"AUTO".equals(plan.vocalGender()))return base;
        String explicit=base==null?work.style():base.style();
        String exclusions=uniqueExclusions(base==null?"":base.negativeTags(),plan.negativeTags());
        // 已明确排除的乐器不能仍作为默认主乐器提交；不强删包含段落变化的用户自由描述。
        if(base!=null){
            var remove=new HashSet<String>();
            var instruments=base.preferences().choices().getOrDefault("instruments",List.of());
            String avoid=exclusions.toLowerCase(Locale.ROOT);
            for(String instrument:instruments){
                String term=switch(instrument){case "guitar"->"acoustic guitar";case "piano"->"piano";case "drums"->"drums";default->instrument;};
                if(avoid.contains(term)||avoid.contains(instrument))remove.add(term);
            }
            // 只处理系统默认，不能通过字符串替换改写用户的自由输入。
            String marker="User directions take precedence: ";int split=explicit.indexOf(marker);
            String defaults=split<0?explicit:explicit.substring(0,split),user=split<0?"":explicit.substring(split);
            for(String term:remove)defaults=defaults.replace("lead instrument: "+term,"").replace(term,"");
            explicit=defaults+user;
        }
        if(exclusions.length()>limits.negativeTags())throw new DomainException("INVALID_MUSIC_SETTINGS","需要避开的元素合并后过长，请精简排除项。原文已保留，尚未提交音乐生成。");
        String style=required(explicit,plan.requirements(),base!=null&&currentOverrides);
        boolean simplified=false;
        if(style.length()>limits.style()&&concise!=null){style=required(concise,plan.requirements(),currentOverrides);simplified=true;}
        if(style.length()>limits.style())throw new DomainException("INVALID_MUSIC_SETTINGS","保留你的明确要求后，音乐描述仍过长，请精简补充要求或减少音乐选项。原文和歌词已保留，尚未提交音乐生成。");
        // 可选编曲整段省略，不截断用户句子，也不破坏否定词。
        if(!plan.suggestedArrangement().isBlank()&&!style.contains(plan.suggestedArrangement())){
            String extended=style+". Optional: "+plan.suggestedArrangement();
            if(extended.length()<=limits.style())style=extended;else simplified=true;
        }
        var prefs=base==null?new MusicSettings.Preferences(1,null,Map.of(),work.vocalGender(),"",""):base.preferences();
        String zh=base==null?plan.summaryZh():base.summaryZh(),en=base==null?plan.summaryEn():base.summaryEn();
        return new MusicSettings.Direction(prefs,style,exclusions,zh,en,base==null?1:base.catalogVersion(),plan,
            new MusicSettings.Compilation(style.length(),limits.style(),exclusions.length(),limits.negativeTags(),simplified,
                simplified?"已精简系统编曲建议，你的明确要求和歌词完整保留。":""));
    }
    private static String required(String current,String original,boolean hasCurrent){
        if(original.isBlank()||current.contains(original))return current;
        return "Requirements: "+original+". Current: "+current+(hasCurrent?". Current settings take priority in conflicts.":". Requirements take priority over defaults.");
    }
    private static String uniqueExclusions(String... sources){
        var clauses=new LinkedHashMap<String,String>();
        for(String source:sources)for(String clause:source.split("[,，;；\\n]")){
            String text=clause.trim();if(!text.isBlank())clauses.putIfAbsent(text.toLowerCase(Locale.ROOT),text);
        }
        return String.join(", ",clauses.values());
    }
}
