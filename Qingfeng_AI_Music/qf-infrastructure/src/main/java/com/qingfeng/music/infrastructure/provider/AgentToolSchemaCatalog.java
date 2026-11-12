package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.agent.api.AgentChatModel;
import java.util.*;

/**
 * Explicit function schemas for the server capability catalog, never inferred from model text.
 * Adapts the tool-registration/schema separation in YQ-AI ToolRegistry/AbstractTool
 * (yth, commit a11c254); domain tools, reflective execution and its unchecked arguments are not reused.
 */
public final class AgentToolSchemaCatalog {
    private static final Map<String,Map<String,Object>> SCHEMAS=schemas();
    private AgentToolSchemaCatalog() {}
    public static boolean supports(String action){return SCHEMAS.containsKey(action);}

    /** Only capabilities actually offered by the server can become provider function definitions. */
    public static List<Map<String,Object>> tools(AgentChatModel.Context context) {
        var descriptions=new TreeMap<String,String>();
        if(context.capabilities().isEmpty())AgentPlanCodec.BASE_ACTIONS.forEach(id->descriptions.put(id,id));
        else context.capabilities().forEach(capability->descriptions.put(capability.id(),"["+capability.effect()+"] "+capability.description()));
        var tools=new ArrayList<Map<String,Object>>();
        descriptions.forEach((id,description)->{
            var schema=SCHEMAS.get(id);
            if(schema!=null)tools.add(Map.of("type","function","function",Map.of("name",id,"description",description,"parameters",schema)));
        });
        return List.copyOf(tools);
    }

    public static void validateNativeArguments(String action,Map<String,Object> arguments) {
        var schema=SCHEMAS.get(action);
        if(schema==null)throw invalid();
        validate(schema,arguments,0);
    }

    /** JSON Schema integer includes 90.0; downstream typed business arguments require Java Integer. */
    @SuppressWarnings("unchecked")
    public static Map<String,Object> normalizeNativeArguments(String action,Map<String,Object> arguments) {
        validateNativeArguments(action,arguments);
        return (Map<String,Object>)normalize(SCHEMAS.get(action),arguments);
    }

    private static Map<String,Map<String,Object>> schemas() {
        var all=new LinkedHashMap<String,Map<String,Object>>();
        for(String action:List.of("get_current_song","list_capabilities","play","download","share","revoke_share","like","unlike","cancel",
                "music_settings_catalog","get_credits","list_credit_entries","get_usage","get_account","get_referral","list_orders","payment_catalog",
                "recharge","photo_song","login","logout","account_settings","privacy_settings","erase_analytics"))all.put(action,object(Map.of()));
        var style=fields("style",text(100),"mood",text(4000),"bpm",integer(40,220),"language",enumeration("zh","en","yue"),
                "vocal",enumeration("AUTO","MALE","FEMALE","auto","male","female"),"instruments",array(text(30),8));
        all.put("set_style",object(style));
        var song=new LinkedHashMap<>(style);song.remove("mood");song.putAll(fields("text",text(4000),"mode",enumeration("new","regenerate"),"revisionId",id()));
        all.put("generate_song",object(song));
        all.put("generate_lyrics",object(fields("text",text(4000),"mode",enumeration("new","rewrite","regenerate"),"language",enumeration("zh","en","yue"),"revisionId",id())));
        all.put("modify_section",object(fields("section",text(100),"replacementLyrics",text(4000),"startS",number(0,86400),"endS",number(0,86400)),"section","replacementLyrics","startS","endS"));

        var draft=fields("title",text(100),"recipient",text(80),"occasion",text(32),"story",text(4000),"message",text(500));
        all.put("create_work",object(draft));
        all.put("update_work",object(withWork(draft)));
        all.put("list_works",object(fields("query",text(200),"page",integer(0,10000))));
        all.put("select_work",object(withWork(fields("index",integer(1,50),"query",text(200)))));
        all.put("rename_work",object(withWork(fields("title",text(100))),"title"));
        // Confirmation/version fields are populated by prepare(), never supplied as model authority.
        all.put("delete_work",object(withWork(Map.of())));
        for(String action:List.of("list_versions","list_audio","list_covers","upload_cover","upload_audio","list_tasks","list_section_edits","index_work_memory"))all.put(action,object(withWork(Map.of())));
        all.put("select_version",object(withWork(fields("revisionId",id(),"index",integer(1,50)))));
        all.put("save_lyrics",object(withWork(fields("title",text(100),"lyrics",text(12000),"baseRevisionId",id())),"title","lyrics"));
        all.put("select_audio",object(withWork(fields("assetId",id(),"index",integer(1,50),"revisionId",id()))));
        all.put("select_cover",object(withWork(fields("coverId",id(),"index",integer(1,50)))));
        all.put("edit_cover",object(withWork(fields("sourceId",id(),"prompt",text(1000))),"prompt"));
        all.put("discover_music",object(fields("query",text(200),"favoritesOnly",Map.of("type","boolean"))));
        for(String action:List.of("favorite_music","unfavorite_music","like_sample","unlike_sample"))all.put(action,object(fields("trackId",id()),"trackId"));
        all.put("rewrite_inspiration",object(fields("text",text(4000),"language",enumeration("zh-CN","en","mixed")),"text"));
        for(String action:List.of("retry_archive","close_checking"))all.put(action,object(withWork(fields("taskId",id())),"taskId"));

        all.put("get_music_settings",object(withWork(fields("taskId",id()))));
        var choices=Map.<String,Object>of("type","object","additionalProperties",array(text(100),20),"maxProperties",30);
        var preferences=withWork(fields("freeText",text(300),"negativeText",text(200),"vocalGender",enumeration("AUTO","MALE","FEMALE"),"recipeId",id(),"choices",choices));
        all.put("preview_music_settings",object(preferences));all.put("update_music_settings",object(preferences));
        all.put("simplify_music_brief",object(fields("text",text(300)),"text"));
        all.put("rewrite_lyric_line",object(withWork(fields("line",text(160),"context",text(360),"mode",enumeration("REWRITE","RHYME","COLLOQUIAL","SHORTEN"),"language",enumeration("zh-CN","en","mixed"))),"line"));
        for(String action:List.of("get_playback_lyrics","sync_playback_lyrics","section_context"))all.put(action,object(withWork(fields("assetId",id()))));
        all.put("accept_referral",object(fields("code",text(128)),"code"));
        all.put("get_order",object(fields("orderId",id(),"provider",enumeration("paypal","airwallex")),"orderId"));
        all.put("close_order",object(fields("orderId",id()),"orderId"));
        all.put("search_memory",object(fields("query",text(1000),"limit",integer(1,10)),"query"));
        return Collections.unmodifiableMap(all);
    }

    private static Map<String,Object> id(){return text(80);}
    private static Map<String,Object> text(int max){return Map.of("type","string","maxLength",max);}
    private static Map<String,Object> integer(int min,int max){return Map.of("type","integer","minimum",min,"maximum",max);}
    private static Map<String,Object> number(int min,int max){return Map.of("type","number","minimum",min,"maximum",max);}
    private static Map<String,Object> enumeration(String... options){return Map.of("type","string","enum",List.of(options));}
    private static Map<String,Object> array(Map<String,Object> item,int max){return Map.of("type","array","items",item,"maxItems",max);}
    private static Map<String,Object> object(Map<String,Object> properties,String... required){
        var schema=new LinkedHashMap<String,Object>();schema.put("type","object");schema.put("properties",Collections.unmodifiableMap(new LinkedHashMap<>(properties)));
        schema.put("additionalProperties",false);if(required.length>0)schema.put("required",List.of(required));
        return Collections.unmodifiableMap(schema);
    }
    private static Map<String,Object> fields(Object... pairs){
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private static Map<String,Object> withWork(Map<String,Object> properties){var result=new LinkedHashMap<>(properties);result.put("workId",id());return result;}

    @SuppressWarnings("unchecked")
    private static Object normalize(Map<String,Object> schema,Object value) {
        if(value==null)return null;
        return switch((String)schema.get("type")) {
            // Every declared integer uses int bounds and was validated before reaching this conversion.
            case "integer" -> Integer.valueOf(((Number)value).intValue());
            case "object" -> {
                var properties=(Map<String,Object>)schema.getOrDefault("properties",Map.of());
                var result=new LinkedHashMap<String,Object>();
                ((Map<String,Object>)value).forEach((key,item)->{
                    Object child=properties.getOrDefault(key,schema.get("additionalProperties"));
                    result.put(key,normalize((Map<String,Object>)child,item));
                });
                yield result;
            }
            case "array" -> ((List<?>)value).stream().map(item->normalize((Map<String,Object>)schema.get("items"),item)).toList();
            default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    private static void validate(Map<String,Object> schema,Object value,int depth) {
        if(depth>8)throw invalid();
        // Optional nulls remain compatible with existing argument defaults; required fields below reject them.
        if(value==null)return;
        String type=(String)schema.get("type");
        switch(type) {
            case "object" -> {
                if(!(value instanceof Map<?,?> map))throw invalid();
                if(map.size()>((Number)schema.getOrDefault("maxProperties",50)).intValue())throw invalid();
                var properties=(Map<String,Object>)schema.getOrDefault("properties",Map.of());
                for(var required:(List<String>)schema.getOrDefault("required",List.of())) {
                    Object field=map.get(required);if(field==null||field instanceof String text&&text.isBlank())throw invalid();
                }
                for(var entry:map.entrySet()) {
                    if(!(entry.getKey() instanceof String key)||key.length()>100)throw invalid();
                    Object child=properties.get(key);
                    if(child==null)child=schema.get("additionalProperties");
                    if(!(child instanceof Map<?,?>))throw invalid();
                    validate((Map<String,Object>)child,entry.getValue(),depth+1);
                }
            }
            case "string" -> {
                if(!(value instanceof String text)||text.length()>((Number)schema.getOrDefault("maxLength",4000)).intValue())throw invalid();
                if(schema.get("enum") instanceof List<?> options&&!options.contains(text))throw invalid();
            }
            case "integer","number" -> {
                if(!(value instanceof Number number))throw invalid();double n=number.doubleValue();
                if(!Double.isFinite(n)||"integer".equals(type)&&n!=Math.rint(n)||n<((Number)schema.get("minimum")).doubleValue()||n>((Number)schema.get("maximum")).doubleValue())throw invalid();
            }
            case "boolean" -> {if(!(value instanceof Boolean))throw invalid();}
            case "array" -> {
                if(!(value instanceof List<?> list)||list.size()>((Number)schema.get("maxItems")).intValue())throw invalid();
                for(Object item:list){if(item==null)throw invalid();validate((Map<String,Object>)schema.get("items"),item,depth+1);}
            }
            default -> throw invalid();
        }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid native tool arguments");}
}
