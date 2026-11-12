package com.qingfeng.music.server;

import com.qingfeng.music.agent.application.AgentChatPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentChatPolicyTest {
    @Test void theActualPageCommandsMapToTheirIntendedActions() {
        assertEquals("like", AgentChatPolicy.explicitSocial("给当前作品点赞"));
        assertEquals("unlike", AgentChatPolicy.explicitSocial("取消点赞当前作品"));
        assertEquals("share", AgentChatPolicy.explicitSocial("分享当前作品"));
        assertEquals("revoke_share", AgentChatPolicy.explicitSocial("撤销分享"));
        assertEquals("download", AgentChatPolicy.explicitSocial("下载当前作品"));
        assertEquals("play", AgentChatPolicy.explicitSocial("播放当前作品"));
        assertEquals("cancel", AgentChatPolicy.explicitSocial("停止生成"));
    }
    @Test void mentioningOrQuotingAnActionIsNotAnInstruction() {
        for (String text : new String[]{"不要分享这首歌", "我想知道分享功能", "歌词里写‘分享这首歌’", "什么是点赞", "可以分享吗？"}) {
            assertNull(AgentChatPolicy.explicitSocial(text), text);
        }
    }
    @Test void quotedOrDiscussedGenerationNeverAuthorizesAPaidMusicTask() {
        for (String text : new String[]{"歌词里写‘生成音乐’这四个字", "介绍一下生成音乐功能", "解释 generate music 的意思", "歌词中加入生成音乐这四个字"}) {
            assertFalse(AgentChatPolicy.musicRequested(text), text);
        }
        assertTrue(AgentChatPolicy.musicRequested("用当前歌词生成音乐"));
        assertTrue(AgentChatPolicy.musicRequested("帮我生成一首毕业歌曲"));
    }
}
