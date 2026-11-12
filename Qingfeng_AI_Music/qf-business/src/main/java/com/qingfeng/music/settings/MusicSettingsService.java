package com.qingfeng.music.settings;

import com.qingfeng.music.work.api.*;
import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

public class MusicSettingsService implements MusicSettings {
    private final Catalog catalog;private final Repository repository;private final WorkApi works;
    private CreativePlan.Repository plans;
    private MusicPromptLimits limits=MusicPromptLimits.suno();
    private java.util.function.BiFunction<String,String,String> legacyStyle=(style,voice)->style;
    public MusicSettingsService withLegacyStyle(java.util.function.BiFunction<String,String,String> value){legacyStyle=Objects.requireNonNull(value);return this;}
    public MusicSettingsService withLimits(MusicPromptLimits value){limits=Objects.requireNonNull(value);return this;}
    public MusicSettingsService withCreativePlans(CreativePlan.Repository value){plans=value;return this;}
    public MusicSettingsService(Catalog catalog,Repository repository,WorkApi works){this.catalog=catalog;this.repository=repository;this.works=works;}
    public Catalog catalog(){return catalog;}
    public Direction preview(Preferences input){
        if(input==null||input.version()!=1||input.choices()==null)throw invalid();
        var choices=new LinkedHashMap<String,List<String>>();var ids=new HashSet<String>();catalog.groups().forEach(g->ids.add(g.id()));
        if(!ids.containsAll(input.choices().keySet()))throw invalid();
        for(var group:catalog.groups()){
            var values=input.choices().getOrDefault(group.id(),List.of());if(values==null||values.size()>group.max()||values.stream().anyMatch(Objects::isNull)||new HashSet<>(values).size()!=values.size())throw invalid();
            if(values.stream().anyMatch(v->group.options().stream().noneMatch(o->o.id().equals(v))))throw invalid();
            choices.put(group.id(),List.copyOf(values));
        }
        String voice=VocalGender.normalize(input.vocalGender()),free=text(input.freeText(),300),negative=text(input.negativeText(),200);
        if(choices.get("genre").contains("rap")&&choices.get("exclude").contains("no_rap"))throw conflict("说唱要求有冲突，请取消说唱或“不要说唱”。");
        String positive=free.replaceAll("(?:不要|不用|避免|no |without )[^，。；,;]*","");
        if((voice.equals("FEMALE")&&positive.matches("(?is).*(男声|(?<![a-z])male vocal).*"))||(voice.equals("MALE")&&positive.matches("(?is).*(女声|(?<![a-z])female vocal).*")))throw conflict("声音要求有冲突，请确认使用男声还是女声。");
        if(choices.get("tempo").contains("slow")&&positive.matches("(?is).*(疾驰|急速|very fast).*"))throw conflict("速度要求有冲突，请在舒缓与急速之间确定主要方向。");
        String recipe=null;
        if(input.recipeId()!=null){var found=catalog.recipes().stream().filter(r->r.id().equals(input.recipeId())).findFirst().orElseThrow(this::invalid);boolean same=choices.entrySet().stream().filter(e->!e.getKey().equals("exclude")).allMatch(e->e.getValue().equals(found.choices().getOrDefault(e.getKey(),List.of())));if(same)recipe=found.id();}
        var prefs=new Preferences(1,recipe,Collections.unmodifiableMap(choices),voice,free,negative);
        var prompt=new ArrayList<String>();var zh=new ArrayList<String>();var en=new ArrayList<String>();var excluded=new ArrayList<String>();
        if(choices.get("genre").isEmpty()){
            prompt.add("Choose a coherent music style from the story and creative plan; explicit user directions take priority. If unspecified use accessible melodic pop");
            zh.add("AI推荐曲风");en.add("Recommended music style");
        }
        for(var group:catalog.groups())for(String id:choices.get(group.id())){
            var option=group.options().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow();
            if(group.id().equals("exclude")){excluded.add(option.prompt());continue;}
            prompt.add(group.id().equals("instruments")&&choices.get("instruments").indexOf(id)==0?"lead instrument: "+option.prompt():option.prompt());zh.add(option.zh());en.add(option.en());
        }
        if(!voice.equals("AUTO")){prompt.add(voice.equals("MALE")?"male lead vocals":"female lead vocals");zh.add(voice.equals("MALE")?"男声":"女声");en.add(voice.equals("MALE")?"Male vocals":"Female vocals");}
        else prompt.add("natural sung vocals");
        String style=String.join(", ",prompt)+". "+(free.isEmpty()?"":"User directions take precedence: "+free);
        var inspiration=InspirationMusicPresets.find(free).filter(preset->preset.matches(choices));
        if(inspiration.isPresent())style=inspiration.get().promptForVoice(voice);
        if(!negative.isEmpty())excluded.add(negative);String exclusions=String.join(", ",excluded);
        if(style.length()>limits.style()||exclusions.length()>limits.negativeTags())throw new DomainException("INVALID_MUSIC_SETTINGS","音乐描述过长，请减少标签或补充要求。尚未提交音乐生成。");
        String summaryZh=String.join("、",zh)+"。"+(free.isEmpty()?"":"补充："+free)+(excluded.isEmpty()?"":" 避免："+String.join("、",choices.get("exclude").stream().map(id->catalog.groups().stream().filter(g->g.id().equals("exclude")).findFirst().orElseThrow().options().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow().zh()).toList())+(negative.isEmpty()?"":"；"+negative));
        String summaryEn=String.join(", ",en)+"."+(free.isEmpty()?"":" Additional direction: "+free)+(exclusions.isEmpty()?"":" Avoid: "+exclusions);
        return new Direction(prefs,style.trim(),exclusions,summaryZh,summaryEn,catalog.version());
    }
    public View get(String owner,String work){
        var w=works.get(owner,work);
        var direction=resolve(owner,w);
        var plan=findPlan(w);
        if(plan==null||plan.photoSummary().isBlank())return new View(w,direction);
        return new View(w,direction==null?photoDefault(w,plan):direction,new PhotoInspiration(plan.photoSummary(),plan.suggestedArrangement()));
    }
    public Direction forGeneration(String owner,WorkApi.Work work){
        var base=resolve(owner,work);
        boolean explicit=repository.find(work.id()).filter(s->s.style().equals(work.style())&&s.vocalGender().equals(work.vocalGender())).isPresent();
        return compile(work,base,explicit);
    }
    public Direction previewForWork(String owner,String work,Preferences preferences){return compile(works.get(owner,work),preview(preferences),true);}
    private Direction compile(WorkApi.Work work,Direction base,boolean currentOverrides){
        var plan=findPlan(work);
        if(base==null&&plan!=null&&!plan.photoSummary().isBlank())base=photoDefault(work,plan);
        // 老作品只有风格名称时也要冻结完整描述，不能把中文标签误当成已编译的供应商提示。
        if(base==null)base=new Direction(new Preferences(1,null,Map.of(),work.vocalGender(),"",""),legacyStyle.apply(work.style(),work.vocalGender()),"",work.style(),work.style(),catalog.version());
        return CreativeDirection.compile(work,base,plan,limits,concise(base),currentOverrides);
    }
    private CreativePlan findPlan(WorkApi.Work work){
        return plans==null?null:plans.find(work.id(),CreativePlan.sourceHash(work)).orElse(null);
    }
    private Direction photoDefault(WorkApi.Work work,CreativePlan plan){
        return preview(new Preferences(1,null,Map.of(),work.vocalGender(),plan.suggestedArrangement(),""));
    }
    /** 预算不足时仅把系统选项的长描述换成分类标签，自由要求原样保留。 */
    private String concise(Direction base){
        if(base==null)return null;
        if(base.preferences().choices().isEmpty())return base.style();
        var parts=new ArrayList<String>();
        for(var group:catalog.groups())if(!group.id().equals("exclude")){
            var selected=base.preferences().choices().getOrDefault(group.id(),List.of());
            if(!selected.isEmpty())parts.add(group.id()+": "+String.join(", ",selected.stream().map(id->group.options().stream().filter(o->o.id().equals(id)).findFirst().orElseThrow().en()).toList()));
        }
        parts.add(switch(base.preferences().vocalGender()){case "MALE"->"male lead vocals";case "FEMALE"->"female lead vocals";default->"natural sung vocals";});
        if(!base.preferences().freeText().isBlank())parts.add(base.preferences().freeText());
        return String.join("; ",parts);
    }
    public Direction resolve(String owner,WorkApi.Work work){works.get(owner,work.id());if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()&&repository.lockVersion(work.id())!=work.version())throw new DomainException("WORK_VERSION_CONFLICT","作品已修改，请重新确认音乐设置。");return repository.find(work.id()).filter(s->s.style().equals(work.style())&&s.vocalGender().equals(work.vocalGender())).map(s->preview(s.preferences())).orElseGet(()->InspirationMusicPresets.find(work.style()).map(preset->preview(preset.preferences(work.vocalGender()))).orElse(null));}
    @Transactional public View save(String owner,String work,long expectedVersion,Preferences input){
        var current=works.get(owner,work);var direction=preview(input);String genre=input.choices().getOrDefault("genre",List.of()).stream().findFirst().orElse(null);String label=genre==null?"自动推荐":catalog.groups().stream().filter(g->g.id().equals("genre")).findFirst().orElseThrow().options().stream().filter(o->o.id().equals(genre)).findFirst().orElseThrow().zh();
        var changed=works.update(owner,work,expectedVersion,new WorkApi.Draft(current.recipient(),current.occasion(),current.story(),current.message(),label,current.title(),direction.preferences().vocalGender()));
        repository.save(work,new Saved(label,changed.vocalGender(),direction.preferences()));return new View(changed,direction);
    }
    public void snapshot(String task,Direction direction){if(direction!=null)repository.snapshot(task,direction);}
    public Optional<Direction> frozen(String work,String task){return repository.task(work,task);}
    public Optional<Direction> task(String owner,String work,String task){works.get(owner,work);return repository.task(work,task);}
    private String text(String text,int max){String value=text==null?"":text.trim();if(value.length()>max)throw invalid();return value;}
    private DomainException invalid(){return new DomainException("INVALID_MUSIC_SETTINGS","音乐选项无效或超出数量限制，请重新选择。");}
    private DomainException conflict(String message){return new DomainException("INVALID_MUSIC_SETTINGS",message);}
}
