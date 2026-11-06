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
        if(plan==null)plan=fallback(work);
        if(base!=null&&plan.requirements().isBlank()&&plan.suggestedArrangement().isBlank()&&plan.negativeTags().isBlank()&&"AUTO".equals(plan.vocalGender()))return base;
        String explicit=base==null?work.style():base.style();
        String exclusions=String.join(", ",java.util.stream.Stream.of(base==null?"":base.negativeTags(),plan.negativeTags()).filter(s->!s.isBlank()).distinct().toList());
        // 已明确排除的乐器不能仍作为默认主乐器提交；不强删包含段落变化的用户自由描述。
        if(base!=null){
            var remove=new HashSet<String>();
            var instruments=base.preferences().choices().getOrDefault("instruments",List.of());
            String avoid=exclusions.toLowerCase(Locale.ROOT);
            for(String instrument:instruments){
                String term=switch(instrument){case "guitar"->"acoustic guitar";case "piano"->"piano";case "drums"->"drums";default->instrument;};
                if(avoid.contains(term)||avoid.contains(instrument))remove.add(term);
            }
            for(String term:remove)explicit=explicit.replace("lead instrument: "+term,"").replace(term,"");
        }
        var parts=new ArrayList<String>();
        if(!plan.requirements().isBlank())parts.add("Original music directions: "+plan.requirements());
        parts.add("Current music settings: "+explicit);
        if(!plan.suggestedArrangement().isBlank())parts.add("Optional arrangement, only where compatible: "+plan.suggestedArrangement());
        String style=String.join(". ",parts);
        if(!plan.requirements().isBlank()&&base!=null)style+=". Current settings override earlier directions only where they conflict.";
        if(style.length()>1000||exclusions.length()>500)throw new DomainException("INVALID_MUSIC_SETTINGS","合并后的音乐要求过长，请精简补充要求后生成；系统未截断你的要求。");
        var prefs=base==null?new MusicSettings.Preferences(1,null,Map.of(),work.vocalGender(),"",""):base.preferences();
        return new MusicSettings.Direction(prefs,style,exclusions,plan.summaryZh(),plan.summaryEn(),base==null?1:base.catalogVersion(),plan);
    }
}
