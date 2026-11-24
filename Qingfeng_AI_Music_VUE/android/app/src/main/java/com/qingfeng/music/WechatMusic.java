package com.qingfeng.music;

import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage;
import com.tencent.mm.opensdk.modelmsg.WXMusicVideoObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

public final class WechatMusic {
    public static final String OPENED_SONG = "com.qingfeng.music.OPENED_SONG";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String ORIGIN = "https://cheerfulmusic.com";

    private WechatMusic() {}

    public static boolean isSongId(String id) {
        return id != null && TOKEN.matcher(id).matches();
    }

    private static boolean validText(String value, int maxBytes) {
        return value != null && !value.trim().isEmpty() && value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
    }

    static SendMessageToWX.Req message(String id, String title, String singer, int duration, String destination,
                                       byte[] thumbnail, String lrc) {
        if (!isSongId(id) || !validText(title, 512) || !validText(singer, 1024) || duration <= 0 ||
                thumbnail == null || thumbnail.length == 0 || thumbnail.length > 32768) {
            throw new IllegalArgumentException("音乐卡片缺少有效的公开歌曲资料。");
        }
        SendMessageToWX.Req request = new SendMessageToWX.Req();
        if ("timeline".equals(destination)) request.scene = SendMessageToWX.Req.WXSceneTimeline;
        else if ("friend".equals(destination)) request.scene = SendMessageToWX.Req.WXSceneSession;
        else throw new IllegalArgumentException("未知的微信分享目标。");

        WXMusicVideoObject track = new WXMusicVideoObject();
        track.musicUrl = ORIGIN + "/song/" + id;
        track.musicDataUrl = ORIGIN + "/api/songs/" + id + "/audio";
        track.identification = id;
        track.singerName = singer;
        track.duration = duration;
        if (validText(lrc, 32768)) track.songLyric = lrc;

        WXMediaMessage media = new WXMediaMessage(track);
        media.title = title;
        media.description = singer;
        media.thumbData = thumbnail;
        media.messageExt = id;
        request.message = media;
        request.transaction = UUID.randomUUID().toString();
        return request;
    }
}
