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

    @Test void optionalNullsAndBlankMutationFieldsAreOmittedInBothProtocols() {
        String arguments="{\"story\":\"新的故事\",\"title\":null,\"recipient\":\"  \",\"message\":null}";
        var nativePlan=AgentPlanCodec.parseMessage(calls(call("one","update_work",arguments)),context("update_work"));
        var legacyPlan=AgentPlanCodec.parse("{\"action\":\"update_work\",\"reply\":\"更新故事\",\"args\":"+arguments+"}",context("update_work"));
        assertEquals(Map.of("story","新的故事"),nativePlan.args());
        assertEquals(nativePlan.args(),legacyPlan.args());
        var settings=AgentPlanCodec.parseMessage(calls(call("two","update_music_settings","{\"freeText\":null,\"negativeText\":\"  \",\"recipeId\":null,\"choices\":{\"instruments\":null},\"vocalGender\":\"FEMALE\"}")),context("update_music_settings"));
        assertEquals(Map.of("vocalGender","FEMALE"),settings.args());
        var audio=AgentPlanCodec.parseMessage(calls(call("three","select_audio","{\"assetId\":\"imported\",\"revisionId\":null}")),context("select_audio"));
        assertEquals(Map.of("assetId","imported"),audio.args());
    }

    @Test void emptyMusicCategoriesCannotEraseSettingsUnlessTheWholeRequestExplicitlyClearsThatCategory() {
        String arguments="{\"vocalGender\":\"FEMALE\",\"choices\":{\"genre\":[],\"instruments\":[],\"emotion\":[]}}";
        for(String text:List.of("改成女声","不要清空所有乐器","解释‘清空所有乐器’","\"clear all instruments\"","清空所有乐器再生成音乐","先查看积分，然后清空所有乐器","do not clear all instruments","clear all instruments and change genre")) {
            var input=new AgentChatModel.Context(text,"zh-CN",List.of(),null,null,context("update_music_settings").capabilities(),null);
            var nativePlan=AgentPlanCodec.parseMessage(calls(call("one","update_music_settings",arguments)),input);
            var legacyPlan=AgentPlanCodec.parse("{\"action\":\"update_music_settings\",\"reply\":\"更新\",\"args\":"+arguments+"}",input);
            if("改成女声".equals(text))assertEquals(Map.of("vocalGender","FEMALE"),nativePlan.args(),text);
            else {assertEquals("chat",nativePlan.action(),text);assertTrue(nativePlan.args().isEmpty());assertTrue(nativePlan.reply().contains("尚未执行"));}
            assertEquals(nativePlan.action(),legacyPlan.action(),text);assertEquals(nativePlan.args(),legacyPlan.args(),text);
        }
        for(String text:List.of("请清空所有乐器。","去掉全部配器","please clear all instruments")) {
            var input=new AgentChatModel.Context(text,"zh-CN",List.of(),null,null,context("update_music_settings","set_style").capabilities(),null);
            var plan=AgentPlanCodec.parseMessage(calls(call("one","update_music_settings",arguments)),input);
            assertEquals(Map.of("instruments",List.of()),plan.args().get("choices"),text);
            var style=AgentPlanCodec.parseMessage(calls(call("two","set_style","{\"instruments\":[],\"bpm\":90}")),input);
            assertEquals(List.of(),style.args().get("instruments"));
        }
        var input=new AgentChatModel.Context("clear all genre options","en",List.of(),null,null,context("update_music_settings").capabilities(),null);
        assertEquals(Map.of("genre",List.of()),AgentPlanCodec.parseMessage(calls(call("one","update_music_settings",arguments)),input).args().get("choices"));
        assertFalse(AgentPlanCodec.parseMessage(calls(call("one","set_style","{\"instruments\":[],\"bpm\":90}")),context("set_style")).args().containsKey("instruments"));
    }

    @Test void unresolvedClearingStopsTheWholePlanRatherThanGeneratingWithOldSettings() {
        for(String text:List.of("先去掉所有乐器，再生成音乐","清空情绪选项再生成音乐","清空寄语然后生成音乐")) {
            String action=text.contains("寄语")?"update_work":"set_style";
            String args=text.contains("寄语")?"{\"message\":null}":"{\"instruments\":[]}";
            var input=new AgentChatModel.Context(text,"zh-CN",List.of(),null,null,context(action,"generate_song").capabilities(),null);
            var nativePlan=AgentPlanCodec.parseMessage(calls(call("one",action,args),call("two","generate_song","{}")),input);
            var legacyPlan=AgentPlanCodec.parse("{\"reply\":\"开始\",\"steps\":[{\"action\":\""+action+"\",\"args\":"+args+"},{\"action\":\"generate_song\",\"args\":{}}]}",input);
            assertEquals("chat",nativePlan.action());assertEquals(1,nativePlan.orderedSteps().size());assertEquals(nativePlan,legacyPlan);
        }
        var input=new AgentChatModel.Context("去掉所有乐器","zh-CN",List.of(),null,null,context("set_style").capabilities(),null);
        var valid=AgentPlanCodec.parseMessage(calls(call("one","set_style","{\"instruments\":[],\"mood\":null}")),input);
        assertEquals("set_style",valid.action());assertEquals(Map.of("instruments",List.of()),valid.args());
    }

    @Test void legacyPlansCannotBypassReviewedTypesBoundsOrConfirmationFields() {
        for(String args:List.of("{\"title\":null}","{\"title\":\"\"}","{\"title\":{\"text\":\"wrong type\"}}","{\"title\":\"晴天\",\"owner\":\"foreign\"}"))
            assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse("{\"action\":\"rename_work\",\"reply\":\"开始\",\"args\":"+args+"}",context("rename_work")));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse("{\"action\":\"delete_work\",\"reply\":\"开始\",\"args\":{\"workId\":\"work\",\"confirmed\":true,\"expectedVersion\":1}}",context("delete_work")));
        assertThrows(IllegalArgumentException.class,()->AgentPlanCodec.parse("{\"reply\":\"开始\",\"steps\":[{\"action\":\"set_style\",\"args\":{\"bpm\":90.5}}]}",context("set_style")));
        var wholeNumber=AgentPlanCodec.parse("{\"action\":\"set_style\",\"reply\":\"开始\",\"args\":{\"bpm\":90.0}}",context("set_style"));
        assertEquals(Integer.valueOf(90),wholeNumber.args().get("bpm"));
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
