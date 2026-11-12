package com.qingfeng.music.agent.api;

/**
 * 消息流内歌曲卡片投影，W1 冻结。W2 的 GetCurrentSongTool 从
 * music_work / lyric_revision / media_asset / work_audio_selection / generation_task 实时聚合。
 */
public record SongCard(
        String songId,
        String title,
        String style,
        String lyrics,
        String audioUrl,
        String status,
        String coverUrl,
        long revision, String revisionId, String assetId, String taskId, String audioOrigin) {

    public SongCard(String songId, String title, String style, String lyrics, String audioUrl,
                    String status, String coverUrl, long revision, String revisionId, String assetId, String taskId) {
        this(songId,title,style,lyrics,audioUrl,status,coverUrl,revision,revisionId,assetId,taskId,null);
    }

    public SongCard(String songId, String title, String style, String lyrics, String audioUrl,
                    String status, String coverUrl, long revision) {
        this(songId, title, style, lyrics, audioUrl, status, coverUrl, revision, null, null, null);
    }

    public static SongCard empty(long revision) {
        return new SongCard(null, "", "", "", "", "NONE", null, revision);
    }
}
