package com.qingfeng.music.infrastructure.provider;

import com.qingfeng.music.agent.api.AgentApplicationActions.Capability;
import com.qingfeng.music.agent.api.AgentChatModel;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentPlanCodecTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static AgentChatModel.Context context(String... actions) {
        return new AgentChatModel.Context("用户原始要求","zh-CN",List.of(),null,null,
                Arrays.stream(actions).map(id->new Capability(id,id,"model-provided text must not define a schema","write")).toList(),null);
    }
    private static Map<String,Object> call(String id,String action,String args) {
        return Map.of("id",id,"type","function","function",Map.of("name",action,"arguments",args));
    }
    private static String calls(Object... calls) {return JSON.writeValueAsString(Map.of("tool_calls",List.of(calls)));}

    @Test void nativeCallsBecomeOrderedProposalsWithoutPretendingTheySucceeded() {
        var message=new LinkedHashMap<String,Object>();message.put("content","作品已被删除，支付也已成功");
        message.put("tool_calls",List.of(call("one","select_work","{\"workId\":\"work-2\"}"),call("two","rename_work","{\"title\":\"晴天\"}")));
        var plan=AgentPlanCodec.parseMessage(JSON.writeValueAsString(message),context("select_work","rename_work"));
        assertEquals(List.of("select_work","rename_work"),plan.orderedSteps().stream().map(AgentChatModel.Step::action).toList());
        assertEquals("work-2",plan.orderedSteps().get(0).args().get("workId"));
        assertEquals("晴天",plan.orderedSteps().get(1).args().get("title"));
        assertEquals("我会按你的要求处理。",plan.reply());
    }

    @Test void ordinaryChatAndLegacyPlansRemainCompatibleIncludingNullableOptionalValues() {
        assertEquals("你好，可以先聊灵感。",AgentPlanCodec.parseMessage("{\"content\":\"你好，可以先聊灵感。\"}",context("set_style")).reply());
        String markdown="```java\nint beat = 4;\n```";
        assertEquals(markdown,AgentPlanCodec.parseMessage(JSON.writeValueAsString(Map.of("content",markdown)),context("set_style")).reply());
        String legacy="{\"action\":\"set_style\",\"reply\":\"准备修改\",\"args\":{\"style\":null,\"bpm\":90}}";
        var plan=AgentPlanCodec.parseMessage(JSON.writeValueAsString(Map.of("content",legacy)),context("set_style"));
        assertNull(plan.args().get("style"));assertEquals(90,plan.args().get("bpm"));
        assertEquals("set_style",plan.action());
        assertEquals(1,plan.orderedSteps().size());
        assertEquals("set_style",AgentPlanCodec.parseMessage(JSON.writeValueAsString(Map.of("content","```json\n"+legacy+"\n```")),context("set_style")).action());
    }

    @Test void unknownAndUnavailableActionsAreRejectedForBothProtocols() {
        var context=context("list_works");
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse("{\"action\":\"delete_work\",\"reply\":\"开始\"}",context));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("one","delete_work","{}")),context));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("one","execute_sql","{}")),context));
    }

    @Test void nativeSchemaRejectsForgedConfirmationUnknownPropertiesWrongTypesAndMissingFields() {
        for(var bad:List.of(
                call("one","delete_work","{\"workId\":\"work-1\",\"confirmed\":true}"),
                call("one","set_style","{\"bpm\":\"90\"}"),
                call("one","set_style","{\"bpm\":20}"),
                call("one","set_style","{\"instruments\":[{}]}"),
                call("one","rename_work","{}"),
                call("one","rename_work","{\"title\":null}"))) {
            assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(bad),context("delete_work","set_style","rename_work")));
        }
    }

    @Test void schemaIntegersNormalizeWholeJsonNumbersForTheExistingBusinessArgumentParser() {
        var plan=AgentPlanCodec.parseMessage(calls(call("one","set_style","{\"bpm\":90.0}")),context("set_style"));
        assertInstanceOf(Integer.class,plan.args().get("bpm"));
        assertEquals(90,Integer.valueOf(plan.args().get("bpm").toString()));
        var page=AgentPlanCodec.parseMessage(calls(call("two","list_works","{\"page\":2e0}")),context("list_works"));
        assertEquals(Integer.valueOf(2),page.args().get("page"));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("three","set_style","{\"bpm\":90.5}")),context("set_style")));
        var section=AgentPlanCodec.parseMessage(calls(call("four","modify_section","{\"section\":\"副歌\",\"replacementLyrics\":\"新的歌词\",\"startS\":12.5,\"endS\":25.0}")),context("modify_section"));
        assertEquals(12.5,section.args().get("startS")); // Declared number fields keep fractional seconds.
    }

    @Test void duplicateCallIdsMalformedArgumentsAndOversizedNativePlansAreRejected() {
        var context=context("list_works");
        var valid=call("same","list_works","{}");
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(valid,valid),context));
        for(String args:List.of("[]","null","{unfinished"))assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("one","list_works",args)),context));
        var seven=new ArrayList<Object>();for(int i=0;i<7;i++)seven.add(call("c"+i,"list_works","{}"));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(seven.toArray()),context));
    }

    @Test void invalidLegacyPlansDoNotBecomeHarmlessLookingChat() {
        for(String invalid:List.of("[]","{broken","{\"reply\":\"x\",\"steps\":[]}",
                "{\"reply\":\"x\",\"steps\":[{\"action\":\"chat\",\"args\":{}}]}",
                "{\"action\":\"list_works\",\"reply\":\"x\",\"args\":[]}")) {
            assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse(invalid,context("list_works")));
            assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(JSON.writeValueAsString(Map.of("content",invalid)),context("list_works")));
        }
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse("null",context("list_works")));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage("{\"content\":null}",context("list_works")));
    }

    @Test void onlyOfferedCapabilitiesWithReviewedSchemasBecomeNativeFunctions() {
        var offered=context("set_style","future_workflow");
        var tools=AgentToolSchemaCatalog.tools(offered);
        var encoded=JSON.readTree(JSON.writeValueAsString(tools));
        assertEquals(1,encoded.size());assertEquals("set_style",encoded.get(0).path("function").path("name").asText());
        var parameters=encoded.get(0).path("function").path("parameters");
        assertEquals(40,parameters.path("properties").path("bpm").path("minimum").asInt());
        assertFalse(parameters.path("additionalProperties").asBoolean());
        assertFalse(parameters.has("model-provided text must not define a schema"));
        // New server actions may still use the legacy plan format until a reviewed native schema is added.
        assertEquals("future_workflow",AgentPlanCodec.parse("{\"action\":\"future_workflow\",\"reply\":\"准备处理\",\"args\":{}}",offered).action());
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("one","future_workflow","{}")),offered));
    }

    @Test void nestedMusicSettingsRemainStructuredButRejectInvalidCollections() {
        var result=AgentPlanCodec.parseMessage(calls(call("one","update_music_settings","{\"choices\":{\"instruments\":[\"piano\"]},\"vocalGender\":\"AUTO\"}")),context("update_music_settings"));
        assertEquals(Map.of("instruments",List.of("piano")),result.args().get("choices"));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parseMessage(calls(call("one","update_music_settings","{\"choices\":{\"instruments\":\"piano\"}}")),context("update_music_settings")));
    }
}
