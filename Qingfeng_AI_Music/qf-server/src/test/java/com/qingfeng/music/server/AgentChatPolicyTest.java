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
    @Test void sequentialRequestsPreserveIntentInsteadOfTreatingFirstAsOnly() {
        assertFalse(AgentChatPolicy.lyricsOnly("先写歌词，再生成音乐"));
        assertTrue(AgentChatPolicy.lyricsRequested("先写歌词，再生成音乐"));
        assertTrue(AgentChatPolicy.musicRequested("先写歌词，再生成音乐"));
        assertTrue(AgentChatPolicy.settingsRequested("改成女声，90 BPM，然后生成音乐"));
        assertTrue(AgentChatPolicy.musicRequested("改成女声，90 BPM，然后生成音乐"));
        assertTrue(AgentChatPolicy.lyricsOnly("先写歌词，不要生成音乐"));
        assertFalse(AgentChatPolicy.musicRequested("先写歌词，不要生成音乐"));
        assertTrue(AgentChatPolicy.musicRequested("不要分享，生成音乐"));
        assertFalse(AgentChatPolicy.explicitAction("share","不要分享，生成音乐"));
        assertTrue(AgentChatPolicy.musicRequested("用《毕业》的歌词生成音乐"));
        assertTrue(AgentChatPolicy.lyricsRequested("只写歌词，不要生成歌曲"));
        assertFalse(AgentChatPolicy.musicRequested("只写歌词，不要生成歌曲"));
    }
    @Test void namedAndNumberedSocialTargetsCannotUseCurrentWorkShortcut() {
        assertEquals("share",AgentChatPolicy.currentSocial("分享这首歌"));
        assertEquals("like",AgentChatPolicy.currentSocial("给当前作品点赞"));
        assertNull(AgentChatPolicy.currentSocial("分享第二首"));
        assertNull(AgentChatPolicy.currentSocial("播放毕业"));
        assertNull(AgentChatPolicy.currentSocial("下载《毕业》"));
        assertNull(AgentChatPolicy.currentSocial("分享这首歌，然后播放"));
        assertTrue(AgentChatPolicy.explicitAction("share","选择第二首然后分享"));
    }
    @Test void catalogWritesRequireTheirOwnIntentAndRejectUnrecognizedActions() {
        assertTrue(AgentChatPolicy.applicationAllowed("rename_work","把第二首改名为《晴天》"));
        assertTrue(AgentChatPolicy.applicationAllowed("select_work","把第二首改名为《晴天》"));
        assertTrue(AgentChatPolicy.applicationAllowed("delete_work","删除第二首作品"));
        assertFalse(AgentChatPolicy.applicationAllowed("delete_work","第二首作品怎么样"));
        assertFalse(AgentChatPolicy.applicationAllowed("delete_work","不要删除第二首作品"));
        assertFalse(AgentChatPolicy.applicationAllowed("delete_work","解释删除作品功能"));
        assertFalse(AgentChatPolicy.applicationAllowed("delete_work","歌词中写‘删除作品’"));
        assertTrue(AgentChatPolicy.applicationAllowed("edit_cover","给这首歌生成封面"));
        assertFalse(AgentChatPolicy.applicationAllowed("edit_cover","我喜欢这个封面"));
        assertTrue(AgentChatPolicy.applicationAllowed("favorite_music","收藏第二首推荐歌曲"));
        assertFalse(AgentChatPolicy.applicationAllowed("favorite_music","取消收藏第二首推荐歌曲"));
        assertTrue(AgentChatPolicy.applicationAllowed("unfavorite_music","取消收藏第二首推荐歌曲"));
        assertFalse(AgentChatPolicy.applicationAllowed("arbitrary_sql","执行全部操作"));
    }
    @Test void nativeAndAccountActionsDoNotAuthorizePaymentOrDeletionImplicitly() {
        assertTrue(AgentChatPolicy.applicationAllowed("get_credits","我还有多少积分？"));
        assertTrue(AgentChatPolicy.applicationAllowed("recharge","我要充值"));
        assertFalse(AgentChatPolicy.applicationAllowed("recharge","怎么充值？"));
        assertFalse(AgentChatPolicy.applicationAllowed("recharge","不要充值"));
        assertTrue(AgentChatPolicy.applicationAllowed("login","打开登录"));
        assertTrue(AgentChatPolicy.applicationAllowed("logout","退出登录"));
        assertTrue(AgentChatPolicy.applicationAllowed("erase_analytics","清除我的分析数据"));
        assertTrue(AgentChatPolicy.requiresConfirmation("erase_analytics"));
        assertTrue(AgentChatPolicy.requiresConfirmation("delete_work"));
        assertTrue(AgentChatPolicy.requiresConfirmation("close_checking"));
        assertFalse(AgentChatPolicy.requiresConfirmation("list_works"));
    }
    @Test void photoCreationRequiresAnExplicitRequestAndCannotBeInferredFromAnImageMention(){
        for(String text:new String[]{"打开照片写歌","用照片写首歌","把这张照片变成一首歌","照片写歌","write a song from this photo"})
            assertTrue(AgentChatPolicy.applicationAllowed("photo_song",text),text);
        for(String text:new String[]{"不要打开照片写歌","不要用照片写歌","照片写歌是什么功能？","解释照片写歌","歌词里写‘打开照片写歌’","这张照片很好看"})
            assertFalse(AgentChatPolicy.applicationAllowed("photo_song",text),text);
    }
    @Test void studioToolsDifferentiateInspectionFromWritesAndPaidRequests() {
        assertTrue(AgentChatPolicy.applicationAllowed("get_music_settings","现在是什么设置？"));
        assertTrue(AgentChatPolicy.applicationAllowed("update_music_settings","把负面提示词设置为噪声"));
        assertFalse(AgentChatPolicy.applicationAllowed("update_music_settings","保持现有设置"));
        assertTrue(AgentChatPolicy.applicationAllowed("simplify_music_brief","精简音乐描述"));
        assertTrue(AgentChatPolicy.applicationAllowed("rewrite_lyric_line","这一句改得更押韵"));
        assertFalse(AgentChatPolicy.applicationAllowed("rewrite_lyric_line","不要改写这句歌词"));
        assertTrue(AgentChatPolicy.applicationAllowed("sync_playback_lyrics","重新对齐滚动歌词"));
        assertFalse(AgentChatPolicy.applicationAllowed("sync_playback_lyrics","什么是歌词时间轴"));
        assertTrue(AgentChatPolicy.applicationAllowed("close_order","关闭这笔未支付订单"));
        assertFalse(AgentChatPolicy.applicationAllowed("close_order","不要关闭订单"));
        assertFalse(AgentChatPolicy.explicitAction("like","我不喜欢这首歌"));
        assertFalse(AgentChatPolicy.applicationAllowed("favorite_music","我不收藏这首歌"));
        assertTrue(AgentChatPolicy.applicationAllowed("create_work","新建空白作品"));
        assertTrue(AgentChatPolicy.applicationAllowed("create_work","保存我写的歌词"));
        assertTrue(AgentChatPolicy.applicationAllowed("create_work","导入我自己的音频"));
        assertFalse(AgentChatPolicy.applicationAllowed("create_work","什么是作品草稿"));
        assertFalse(AgentChatPolicy.applicationAllowed("create_work","不要创建作品"));
        assertFalse(AgentChatPolicy.applicationAllowed("create_work","你好"));
    }
    @Test void politeTaskRequestsCanBeConfirmedWhileCapabilityQuestionsStayConversation() {
        assertFalse(AgentChatPolicy.chatOnly("能不能帮我写首歌？"));
        assertFalse(AgentChatPolicy.chatOnly("可以帮我写歌吗？"));
        assertFalse(AgentChatPolicy.musicRequested("能不能帮我写首歌？"));
        assertTrue(AgentChatPolicy.chatOnly("能不能生成音乐？"));
        assertTrue(AgentChatPolicy.chatOnly("怎么帮我生成音乐？"));
        assertTrue(AgentChatPolicy.chatOnly("能不能帮我解释什么是生成音乐？"));
        assertFalse(AgentChatPolicy.musicRequested("能不能先不要帮我生成音乐？"));
    }
}
