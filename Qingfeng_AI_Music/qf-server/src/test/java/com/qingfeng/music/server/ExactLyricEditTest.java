package com.qingfeng.music.server;
import com.qingfeng.music.agent.application.ExactLyricEdit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExactLyricEditTest {
 @Test void separatesInstructionsFromQuotedAndUnquotedLyrics(){
    for(String text:new String[]{"把最后一句改成“海风替我说晚安”，先别生成。","把最后一句改成海风替我说晚安，其他不动，先别生成音乐。","把最后一句改成「海风替我说晚安」。"})
        assertEquals("[副歌]\r\n原句\r\n海风替我说晚安\r\n",ExactLyricEdit.parse(text).apply("[副歌]\r\n原句\r\n旧句\r\n"));
    assertEquals("回家，先别生成",ExactLyricEdit.parse("把最后一句改成“回家，先别生成”，其余不变").replacement());
    assertTrue(ExactLyricEdit.needsReplacement("只改最后一句，先别生成。"));
    assertTrue(ExactLyricEdit.needsReplacement("把最后一句改成，先别生成。"));
    assertNull(ExactLyricEdit.parse("把最后一句改成，先别生成。"));
 }
 @Test void explicitReplacementPreservesPunctuation(){var e=ExactLyricEdit.parse("把最后一句改成：再吃一遍，西山大桥的糯米饭");assertNotNull(e);assertEquals("[副歌]\n原句\n再吃一遍，西山大桥的糯米饭\n",e.apply("[副歌]\n原句\n旧句\n"));}
 @Test void questionsAndNegationDoNotAuthorizeEditing(){for(String s:new String[]{"不要把最后一句改成再见","怎么把最后一句改成再见","把副歌改得更温柔","别人说把最后一句改成再见"})assertNull(ExactLyricEdit.parse(s));}
 @Test void quotationMarksAreNotSavedAsLyrics(){assertEquals("你好",ExactLyricEdit.parse("第一句改为：‘你好’").replacement());assertEquals("你好",ExactLyricEdit.parse("把第一句改为：\"你好\"").replacement());}
 @Test void preservationInstructionIsNotAppendedToLyrics(){assertEquals("新歌词",ExactLyricEdit.parse("把最后一句改成：新歌词，其他不动").replacement());assertEquals("不要走，其他不动",ExactLyricEdit.parse("把最后一句改成：“不要走，其他不动”").replacement());}
}
