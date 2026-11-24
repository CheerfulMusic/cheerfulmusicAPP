package com.qingfeng.music;

import static org.junit.Assert.*;
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.modelmsg.WXMusicVideoObject;
import org.junit.Test;

public class WechatMusicTest {
    private static final String ID = "A".repeat(43);

    @Test public void timelineCarriesAudioAndStableSongIdentity() {
        var request = WechatMusic.message(ID, "歌名", "音乐人", 267015, "timeline", new byte[]{1}, null);
        assertEquals(SendMessageToWX.Req.WXSceneTimeline, request.scene);
        var music = (WXMusicVideoObject) request.message.mediaObject;
        assertEquals("https://cheerfulmusic.com/api/songs/" + ID + "/audio", music.musicDataUrl);
        assertEquals("https://cheerfulmusic.com/song/" + ID, music.musicUrl);
        assertEquals(ID, request.message.messageExt);
        assertEquals(ID, music.identification);
        assertEquals(267015, music.duration);
        assertEquals("音乐人", music.singerName);
        assertEquals(SendMessageToWX.Req.WXSceneSession,
            WechatMusic.message(ID, "歌名", "音乐人", 5000, "friend", new byte[]{1}, null).scene);
    }

    @Test public void invalidInputsCannotCreateMessage() {
        assertFalse(WechatMusic.isSongId("../audio"));
        assertThrows(IllegalArgumentException.class, () -> WechatMusic.message(ID, "歌名", "音乐人", 0, "timeline", new byte[]{1}, null));
        assertThrows(IllegalArgumentException.class, () -> WechatMusic.message(ID, "歌名", "音乐人", 20, "other", new byte[]{1}, null));
    }
}
