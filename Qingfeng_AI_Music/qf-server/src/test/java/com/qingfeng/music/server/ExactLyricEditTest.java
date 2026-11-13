package com.qingfeng.music.server;
import com.qingfeng.music.agent.application.ExactLyricEdit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExactLyricEditTest {
 @Test void explicitReplacementPreservesPunctuation(){var e=ExactLyricEdit.parse("把最后一句改成：再吃一遍，西山大桥的糯米饭");assertNotNull(e);assertEquals("[副歌]\n原句\n再吃一遍，西山大桥的糯米饭\n",e.apply("[副歌]\n原句\n旧句\n"));}
 @Test void questionsAndNegationDoNotAuthorizeEditing(){for(String s:new String[]{"不要把最后一句改成再见","怎么把最后一句改成再见","把副歌改得更温柔","别人说把最后一句改成再见"})assertNull(ExactLyricEdit.parse(s));}
 @Test void quotationMarksAreNotSavedAsLyrics(){assertEquals("你好",ExactLyricEdit.parse("第一句改为：‘你好’").replacement());assertEquals("你好",ExactLyricEdit.parse("把第一句改为：\"你好\"").replacement());}
 @Test void preservationInstructionIsNotAppendedToLyrics(){assertEquals("新歌词",ExactLyricEdit.parse("把最后一句改成：新歌词，其他不动").replacement());assertEquals("不要走，其他不动",ExactLyricEdit.parse("把最后一句改成：“不要走，其他不动”").replacement());}
}
