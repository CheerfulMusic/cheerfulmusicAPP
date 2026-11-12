package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.agent.api.AgentChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Provider-neutral plan decoding. A decoded proposal never authorizes or executes an action.
 * The function-call/result separation adapts YQ-AI ToolCallingLoop (yth, commit a11c254);
 * execution remains in the existing ownership, confirmation and idempotency boundary.
 */
public final class AgentPlanCodec {
    static final Set<String> BASE_ACTIONS=Set.of("chat","generate_lyrics","generate_song","set_style","modify_section","get_current_song",
            "play","download","share","revoke_share","like","unlike","cancel");
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MAX_PLAN_CHARS=131072;
    private AgentPlanCodec() {}

    /** Legacy JSON plan used by both direct providers and workflow planning adapters. */
    public static AgentChatModel.Plan parse(String planJson, AgentChatModel.Context context) {
        try {
            JsonNode plan=object(planJson);
            String reply=reply(plan.path("reply").asText(""));
            Set<String> allowed=allowed(context);
            if(plan.has("steps")) {
                var nodes=plan.path("steps");
                if(!nodes.isArray()||nodes.isEmpty()||nodes.size()>6)throw invalid();
                var steps=new ArrayList<AgentChatModel.Step>();
                for(var step:nodes) {
                    if(!step.isObject())throw invalid();
                    String action=step.path("action").asText("");
                    requireAction(action,allowed,false);
                    steps.add(new AgentChatModel.Step(action,arguments(step.get("args"))));
                }
                return fromSteps(reply,steps);
            }
            String action=plan.path("action").asText("chat");
            requireAction(action,allowed,true);
            return new AgentChatModel.Plan(action,arguments(plan.get("args")),reply);
        } catch(IllegalArgumentException error) {throw error;}
        catch(Exception error) {throw invalid();}
    }

    /** Decode an OpenAI-compatible assistant message, including native function tool_calls. */
    public static AgentChatModel.Plan parseMessage(String messageJson, AgentChatModel.Context context) {
        try {
            JsonNode message=object(messageJson);
            JsonNode calls=message.get("tool_calls");
            if(calls!=null&&!calls.isNull()) {
                if(!calls.isArray()||calls.size()>6)throw invalid();
                if(!calls.isEmpty()) {
                    var steps=new ArrayList<AgentChatModel.Step>();
                    var ids=new HashSet<String>();
                    Set<String> allowed=allowed(context);
                    for(var call:calls) {
                        String id=call.path("id").asText("");
                        if(!call.isObject()||!"function".equals(call.path("type").asText())||id.isBlank()||id.length()>160||!ids.add(id))throw invalid();
                        var function=call.path("function");
                        if(!function.isObject()||!function.path("arguments").isString())throw invalid();
                        String action=function.path("name").asText("");
                        requireAction(action,allowed,false);
                        Map<String,Object> args=arguments(object(function.path("arguments").asText()));
                        // Unlike a provider-supplied schema, these definitions are reviewed application code.
                        args=AgentToolSchemaCatalog.normalizeNativeArguments(action,args);
                        steps.add(new AgentChatModel.Step(action,args));
                    }
                    // Function requests may have no textual content. Never invent a successful result.
                    return fromSteps("我会按你的要求处理。",steps);
                }
            }
            var content=message.get("content");
            if(content==null||!content.isString())throw invalid();
            String text=content.asText().trim();
            // Reject malformed plans, while ordinary Markdown/code answers remain ordinary chat.
            if(text.startsWith("```json")||text.startsWith("```\n{")||text.startsWith("```\r\n{")) {
                int newline=text.indexOf('\n');
                if(newline<0||!text.endsWith("```"))throw invalid();
                return parse(text.substring(newline+1,text.length()-3).trim(),context);
            }
            if(text.startsWith("{")||text.startsWith("["))return parse(text,context);
            return new AgentChatModel.Plan("chat",Map.of(),reply(text));
        } catch(IllegalArgumentException error) {throw error;}
        catch(Exception error) {throw invalid();}
    }

    static Set<String> allowed(AgentChatModel.Context context) {
        var allowed=new HashSet<String>();allowed.add("chat");
        if(context.capabilities().isEmpty())allowed.addAll(BASE_ACTIONS);
        else context.capabilities().forEach(capability->allowed.add(capability.id()));
        return Set.copyOf(allowed);
    }
    private static JsonNode object(String encoded) {
        if(encoded==null||encoded.isBlank()||encoded.length()>MAX_PLAN_CHARS)throw invalid();
        try {var value=JSON.readTree(encoded);if(value==null||!value.isObject())throw invalid();return value;}
        catch(Exception error) {throw invalid();}
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> arguments(JsonNode node) {
        if(node==null)return Map.of();
        if(!node.isObject())throw invalid();
        return JSON.convertValue(node,Map.class);
    }
    private static void requireAction(String action,Set<String> allowed,boolean chatAllowed) {
        if(!allowed.contains(action)||(!chatAllowed&&"chat".equals(action)))throw invalid();
    }
    private static String reply(String value) {
        if(value==null||value.isBlank())throw invalid();
        return value.length()>8000?value.substring(0,8000):value;
    }
    private static AgentChatModel.Plan fromSteps(String reply,List<AgentChatModel.Step> steps) {
        var first=steps.get(0);return new AgentChatModel.Plan(first.action(),first.args(),reply,steps);
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Invalid agent plan");}
}
