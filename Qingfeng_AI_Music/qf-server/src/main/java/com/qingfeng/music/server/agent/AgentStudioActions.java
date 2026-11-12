package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.*;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.conversation.application.ConversationService;
import com.qingfeng.music.generation.application.SectionEditingService;
import com.qingfeng.music.lyrics.application.LyricLineRewriteService;
import com.qingfeng.music.lyrics.playback.*;
import com.qingfeng.music.settings.*;
import com.qingfeng.music.work.api.WorkApi;
import java.util.*;

/** Advanced studio features use the same validation, ownership, quotas and providers as the studio. */
public final class AgentStudioActions implements AgentApplicationActions {
    private final WorkApi works; private final MusicSettings settings; private final MusicBriefService briefs;
    private final LyricLineRewriteService lines; private final PlaybackLyricsService playback;
    private final SectionEditingService sections; private final ConversationService conversations;
    public AgentStudioActions(WorkApi works,MusicSettings settings,MusicBriefService briefs,LyricLineRewriteService lines,
                              PlaybackLyricsService playback,SectionEditingService sections,ConversationService conversations) {
        this.works=works;this.settings=settings;this.briefs=briefs;this.lines=lines;this.playback=playback;this.sections=sections;this.conversations=conversations;
    }
    public List<Capability> capabilities() { return List.of(
        cap("music_settings_catalog","查看音乐设置可用分类、选项和风格配方","","read"),
        cap("get_music_settings","查看作品当前音乐设置或某次生成实际使用的设置","workId?:作品ID,taskId?:生成任务ID","read"),
        cap("preview_music_settings","预览音乐设置，不保存、不生成","workId?:作品ID,freeText?,negativeText?,vocalGender?:AUTO/MALE/FEMALE,recipeId?,choices?:分类ID到选项ID数组","read"),
        cap("update_music_settings","保存完整音乐偏好；仅更新提供的字段，未提供的设置保持不变","workId?:作品ID,freeText?:补充要求,negativeText?:排除要求,vocalGender?:AUTO/MALE/FEMALE,recipeId?,choices?:分类ID到选项ID数组","write"),
        cap("simplify_music_brief","AI精简音乐补充要求，结果仅建议，不自动保存","text:1至300字","paid"),
        cap("rewrite_lyric_line","AI润色、押韵、口语化或缩短单句歌词，结果为预览不自动保存","workId?:作品ID,line:1至160字单句,context?:邻近歌词最多360字,mode?:REWRITE/RHYME/COLLOQUIAL/SHORTEN,language?:zh-CN/en/mixed","paid"),
        cap("get_playback_lyrics","查看所选音频的真实歌词时间轴","workId?:作品ID,assetId?:音频ID","read"),
        cap("sync_playback_lyrics","获取音频对应的同步歌词，遵循供应商可用性","workId?:作品ID,assetId?:音频ID","paid"),
        cap("list_section_edits","查看局部音频修改任务及结果","workId?:作品ID","read"),
        cap("section_context","查看音频可修改区间限制和歌词，便于局部改词","workId?:作品ID,assetId?:音频ID","read")
    ); }
    private static Capability cap(String id,String description,String parameters,String effect) { return new Capability(id,description,parameters,effect); }
    @Override public Result execute(String owner,String conversation,String action,Map<String,Object> args,String text,String key,SongCard current) {
        conversations.state(owner,conversation);
        if("music_settings_catalog".equals(action)) {
            var catalog=settings.catalog();var items=new ArrayList<Item>();
            for(var group:catalog.groups()) items.add(new Item(group.id(),group.zh(),"最多选择 "+group.max()+" 项："+String.join("、",group.options().stream().map(o->o.zh()+" ("+o.id()+")").toList()),Map.of("groupId",group.id())));
            for(var recipe:catalog.recipes()) items.add(new Item(recipe.id(),recipe.zh(),"风格配方",Map.of("recipeId",recipe.id(),"choices",recipe.choices())));
            return result(action,"settings","这些是项目当前可用的音乐选项。",items);
        }
        if("simplify_music_brief".equals(action)) return result(action,"text","精简建议（尚未保存）：\n"+briefs.suggest(owner,required(args,"text",300)),List.of());
        String work=value(args,"workId",current==null?null:current.songId());
        if(work==null) throw invalid("请先打开一首作品。");
        works.get(owner,work);
        return switch(action) {
            case "get_music_settings" -> {
                var task=value(args,"taskId",null);
                var direction=task==null?settings.get(owner,work).direction():settings.task(owner,work,task).orElseThrow(()->invalid("该任务没有可用的设置快照。"));
                if(direction==null)direction=settings.preview(new MusicSettings.Preferences(1,null,Map.of(),works.get(owner,work).vocalGender(),"",""));
                yield settingsResult(action,direction,task==null?"当前音乐设置":"任务实际使用的音乐设置");
            }
            case "preview_music_settings","update_music_settings" -> {
                var prior=settings.get(owner,work);
                var existing=prior.direction()==null?new MusicSettings.Preferences(1,null,Map.of(),prior.work().vocalGender(),"",""):prior.direction().preferences();
                var preferences=preferences(args,existing);
                if("preview_music_settings".equals(action)) yield settingsResult(action,settings.previewForWork(owner,work,preferences),"设置预览（未保存）");
                var saved=settings.save(owner,work,prior.work().version(),preferences);
                // The settings API is authoritative. Clear old Agent overrides so they cannot overwrite this save on the next generation.
                if(work.equals(conversations.current(owner).currentWorkId())) {
                    var state=conversations.state(owner,conversation);
                    var snapshot=state.currentSongJson()==null?new LinkedHashMap<String,Object>():new LinkedHashMap<>(AgentJson.read(state.currentSongJson()));
                    for(String field:List.of("style","mood","bpm","instruments","vocal","baseFreeText")) snapshot.remove(field);
                    snapshot.put("baseFreeText",saved.direction().preferences().freeText());
                    conversations.applyToolResult(owner,conversation,null,"{}",work,AgentJson.write(snapshot),null,state.revision());
                }
                yield settingsResult(action,saved.direction(),"音乐设置已保存，下次生成生效");
            }
            case "rewrite_lyric_line" -> {
                LyricLineRewriteService.Action mode;
                try { mode=LyricLineRewriteService.Action.valueOf(value(args,"mode","REWRITE").toUpperCase(Locale.ROOT)); }
                catch(IllegalArgumentException e) { throw invalid("请选择润色、押韵、口语化或缩短。"); }
                String context=value(args,"context","");if(context.length()>360) throw invalid("相邻歌词最多360字。");
                String language=value(args,"language","zh-CN");if(!Set.of("zh-CN","en","mixed").contains(language)) throw invalid("歌词语言参数无效。");
                String rewritten=lines.preview(owner,work,UUID.fromString(key),mode,required(args,"line",160),context,language);
                yield result(action,"text","单句修改建议（原歌词尚未改动）：\n"+rewritten,List.of());
            }
            case "get_playback_lyrics","sync_playback_lyrics" -> {
                String asset=asset(args,current,work);
                var state="sync_playback_lyrics".equals(action)?playback.sync(owner,work,asset,key):playback.get(owner,work,asset);
                var items=state.lines().stream().map(line->new Item(Double.toString(line.start()),line.text(),String.format(Locale.ROOT,"%.2f–%.2f 秒",line.start(),line.end()),Map.<String,Object>of("start",line.start(),"end",line.end(),"workId",work,"assetId",asset))).toList();
                var commands=state.canSync()&&!"READY".equals(state.status())?List.of(new Command("同步歌词","同步作品 "+work+" 音频 "+asset+" 的歌词时间轴","send")):List.<Command>of();
                yield new Result("歌词同步状态："+state.status()+(items.isEmpty()?"\n"+state.text():""),"timed_lyrics",items,commands,action);
            }
            case "list_section_edits" -> {
                var items=sections.list(owner,work).stream().map(edit->{var data=new LinkedHashMap<String,Object>();data.put("workId",work);data.put("taskId",edit.taskId());data.put("status",edit.status());if(edit.resultAssetId()!=null)data.put("assetId",edit.resultAssetId());return new Item(edit.taskId(),edit.title(),edit.start()+"–"+edit.end()+" 秒 · "+edit.status(),data);}).toList();
                yield result(action,"section_edits",items.isEmpty()?"没有局部修改任务。":"这是作品局部修改记录。",items);
            }
            case "section_context" -> {
                var context=sections.context(owner,work,asset(args,current,work));
                yield result(action,"text",context.available()?"可修改区间：至少 "+context.minimumSeconds()+" 秒、最多 "+context.maximumSeconds()+" 秒。请指定开始/结束秒数和替换歌词。\n"+context.lyrics():context.reason(),List.of());
            }
            default -> throw invalid("不支持的工作室操作。");
        };
    }
    private MusicSettings.Preferences preferences(Map<String,Object> args,MusicSettings.Preferences prior) {
        var choices=new LinkedHashMap<String,List<String>>(prior==null?Map.of():prior.choices());
        if(args.containsKey("choices")) {
            if(!(args.get("choices") instanceof Map<?,?> provided))throw invalid("音乐分类应为选项列表。");
            if(provided.size()>30)throw invalid("音乐分类数量过多。");
            for(var entry:provided.entrySet()) {
                if(!(entry.getKey() instanceof String group)||!(entry.getValue() instanceof List<?> values)||values.size()>20||values.stream().anyMatch(v->!(v instanceof String)||v.toString().length()>100))throw invalid("音乐选项格式无效。");
                choices.put(group,values.stream().map(Object::toString).toList());
            }
        }
        String voice=value(args,"vocalGender",prior==null?"AUTO":prior.vocalGender()).toUpperCase(Locale.ROOT);
        if(!Set.of("AUTO","MALE","FEMALE").contains(voice))throw invalid("人声请选择AUTO、MALE或FEMALE。");
        String free=args.containsKey("freeText")?Objects.toString(args.get("freeText"),""):prior==null?"":prior.freeText();
        String negative=args.containsKey("negativeText")?Objects.toString(args.get("negativeText"),""):prior==null?"":prior.negativeText();
        if(free.length()>300||negative.length()>200)throw invalid("补充要求最多300字，排除要求最多200字，请精简后再试。");
        // The domain catalog validates IDs, recipe conflicts, field limits and maximum choices.
        return new MusicSettings.Preferences(1,args.containsKey("recipeId")?value(args,"recipeId",null):prior==null?null:prior.recipeId(),choices,voice,free,negative);
    }
    private Result settingsResult(String action,MusicSettings.Direction direction,String label) {
        var prefs=direction.preferences();
        return result(action,"settings",label+"。",List.of(
            new Item("style","音乐方向",direction.style(),Map.of()),
            new Item("voice","人声",prefs.vocalGender(),Map.of()),
            new Item("free","补充要求",prefs.freeText(),Map.of()),
            new Item("negative","排除要求",prefs.negativeText(),Map.of())));
    }
    private static String asset(Map<String,Object> args,SongCard current,String work) {String asset=value(args,"assetId",current!=null&&work.equals(current.songId())?current.assetId():null);if(asset==null)throw invalid("请先选择音频，再获取歌词或片段信息。");return asset;}
    private static Result result(String action,String kind,String text,List<Item> items){return new Result(text,kind,items,List.of(),action);}
    private static String value(Map<String,Object> args,String key,String fallback){Object value=args.get(key);return value==null||value.toString().isBlank()?fallback:value.toString().trim();}
    private static String required(Map<String,Object> args,String key,int max){String value=value(args,key,null);if(value==null||value.length()>max)throw invalid("请提供有效的 "+key+"。");return value;}
    private static DomainException invalid(String message){return new DomainException("INVALID_AGENT_STUDIO_ARGUMENT",message);}
}
