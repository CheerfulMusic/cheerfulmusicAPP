package com.qingfeng.music.server;

import com.qingfeng.music.agent.api.AgentTool;
import com.qingfeng.music.agent.api.IntentClassifier;
import com.qingfeng.music.agent.api.IntentResult;
import com.qingfeng.music.agent.application.IntentRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 两级意图路由的规则层断言：≥20 条中英语料、参数抽取、LLM 兜底与降级。意图准确率由此文件承担。 */
class IntentRouterTest {

    private record Case(String text, String locale, String tool) {}

    /** 与 tests/fixtures/agent-intents.json 的 RULE 类前 35 条一致。 */
    private static final List<Case> RULE_CASES = List.of(
            new Case("写一首关于旅行的歌", "zh-CN", "generate_song"),
            new Case("给我一首电影风格的歌曲", "zh-CN", "generate_song"),
            new Case("我想要治愈系的音乐", "zh-CN", "generate_song"),
            new Case("用粤语唱一首情歌", "zh-CN", "generate_song"),
            new Case("创作一首夏日海岛风", "zh-CN", "generate_song"),
            new Case("帮我写一首生日快乐歌", "zh-CN", "generate_song"),
            new Case("换一版", "zh-CN", "generate_song"),
            new Case("再来一版", "zh-CN", "generate_song"),
            new Case("重新生成", "zh-CN", "generate_song"),
            new Case("another version please", "en", "generate_song"),
            new Case("换成民谣风格", "zh-CN", "set_style"),
            new Case("改成摇滚风格", "zh-CN", "set_style"),
            new Case("change the style", "en", "set_style"),
            new Case("make it jazz style", "en", "set_style"),
            new Case("延长 30 秒", "zh-CN", "extend_song"),
            new Case("把副歌再延长一点", "zh-CN", "extend_song"),
            new Case("extend the song", "en", "extend_song"),
            new Case("make it longer", "en", "extend_song"),
            new Case("播放", "zh-CN", "get_current_song"),
            new Case("试听一下", "zh-CN", "get_current_song"),
            new Case("play the song", "en", "get_current_song"),
            new Case("下载", "zh-CN", "get_current_song"),
            new Case("download the track", "en", "get_current_song"),
            new Case("改歌词", "zh-CN", "generate_lyrics"),
            new Case("rewrite the lyrics", "en", "generate_lyrics"),
            new Case("把第二段歌词改温柔些", "zh-CN", "modify_section"),
            new Case("局部重唱这段", "zh-CN", "modify_section"),
            new Case("replace section", "en", "modify_section"),
            new Case("100 BPM 加钢琴", "zh-CN", "generate_song"),
            new Case("来一首 90 BPM 的慢歌", "zh-CN", "generate_song"),
            new Case("用粤语唱", "zh-CN", "generate_song"),
            new Case("Cantonese love song please", "en", "generate_song"),
            new Case("国风古韵的歌", "zh-CN", "generate_song"),
            new Case("写一首英文歌", "zh-CN", "generate_song"),
            new Case("轻音乐，安静一点", "zh-CN", "generate_song")
    );

    private static IntentClassifier stub(IntentResult result) {
        return new IntentClassifier() {
            public boolean isConfigured() { return true; }
            public IntentResult classify(String text, String locale) { return result; }
        };
    }

    private static IntentRouter router(boolean llmEnabled, IntentResult llmResult) {
        return new IntentRouter(stub(llmResult), llmEnabled, 0.7);
    }

    @Test
    void allRuleCasesAreDirectWithoutClarification() {
        IntentRouter router = router(false, null);
        for (Case item : RULE_CASES) {
            IntentResult result = router.route(item.text(), item.locale());
            assertEquals(item.tool(), result.tool(), "语料未命中预期工具：" + item.text());
            assertEquals(IntentResult.Source.RULE, result.source(), item.text());
            assertEquals(1.0, result.confidence());
            assertFalse(result.needClarify(), item.text());
            assertNotNull(result.replyText());
            assertTrue(result.args().containsKey("text"));
        }
    }

    @Test
    void extractsOnlyExplicitlyStatedParameters() {
        IntentRouter router = router(false, null);
        IntentResult bpm = router.route("100 BPM 加钢琴", "zh-CN");
        assertEquals(100, bpm.args().get("bpm"));
        assertEquals(List.of("钢琴"), bpm.args().get("instruments"));
        assertEquals("yue", router.route("用粤语唱一首情歌", "zh-CN").args().get("language"));
        assertEquals("en", router.route("写一首英文歌", "zh-CN").args().get("language"));
        assertFalse(router.route("写一首关于旅行的歌", "zh-CN").args().containsKey("bpm"));
    }

    @Test
    void openInputFallsBackToClarificationWhenLlmIsOff() {
        IntentResult result = router(false, null).route("今天有点闷，想听点能让人安静下来的东西", "zh-CN");
        assertTrue(result.needClarify());
        assertNull(result.tool());
        assertEquals(IntentResult.Source.RULE, result.source());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void llmResultIsAcceptedOnlyWhenConfidentAndToolIsWhitelisted() {
        IntentResult good = new IntentResult("generate_song", Map.of("text", "随便来点惊喜"),
                0.91, false, "好的。", IntentResult.Source.LLM);
        assertEquals(IntentResult.Source.LLM, router(true, good).route("随便来点惊喜", "zh-CN").source());

        IntentResult weak = new IntentResult("generate_song", Map.of(), 0.3, false, "好的。", IntentResult.Source.LLM);
        assertTrue(router(true, weak).route("能不能来点不一样的", "zh-CN").needClarify());

        IntentResult unknownTool = new IntentResult("drop_database", Map.of(), 0.99,
                false, "好的。", IntentResult.Source.LLM);
        assertTrue(router(true, unknownTool).route("你决定吧", "zh-CN").needClarify());
    }

    @Test
    void blankOrOversizedInputNeverTriggersAnyTool() {
        IntentRouter router = router(true, new IntentResult("generate_song", Map.of(), 1.0,
                false, "好的。", IntentResult.Source.LLM));
        assertTrue(router.route("", "zh-CN").needClarify());
        assertTrue(router.route("   ", "zh-CN").needClarify());
        assertTrue(router.route("写一首" + "啊".repeat(4100), "zh-CN").needClarify());
    }

    @Test
    void toolIdsAreStableAndResolvable() {
        for (AgentTool tool : AgentTool.values()) {
            assertEquals(tool, AgentTool.fromId(tool.id()));
        }
        assertNull(AgentTool.fromId("drop_database"));
    }
}
