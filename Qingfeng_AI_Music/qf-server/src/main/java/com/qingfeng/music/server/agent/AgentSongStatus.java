package com.qingfeng.music.server.agent;

import com.qingfeng.music.agent.api.AgentApplicationActions.Command;
import com.qingfeng.music.agent.api.AgentApplicationActions.Item;
import com.qingfeng.music.agent.api.AgentApplicationActions.Result;
import com.qingfeng.music.agent.api.SongCard;
import java.util.List;
import java.util.Map;

final class AgentSongStatus {
    private AgentSongStatus() {}

    static Result result(SongCard card, String locale) {
        boolean en = locale != null && locale.startsWith("en");
        if (card == null || card.songId() == null || card.songId().isBlank()) {
            var works = new Command(en ? "My songs" : "查看我的作品", "列出我的作品。", "send");
            return new Result(en ? "Choose a song from your works to see its status." : "当前还没有选中歌曲，可以从我的作品里选择。", "song_status", List.of(), List.of(works), "get_current_song");
        }
        String title = card.title() == null || card.title().isBlank() ? (en ? "Current song" : "当前歌曲") : card.title();
        if (card.assetId() != null && card.audioUrl() != null && !card.audioUrl().isBlank()) {
            var audio = new Item(card.assetId(), title, "", Map.of("audioUrl", card.audioUrl()));
            return new Result(en ? title + " is ready to play." : "《" + title + "》的音频已就绪，可以直接播放。", "song_status", List.of(audio), List.of(), "get_current_song");
        }
        String detail = switch (card.status() == null ? "" : card.status()) {
            case "QUEUED" -> en ? "is in the generation queue." : "正在排队生成。";
            case "SUBMITTING", "RUNNING" -> en ? "is being generated." : "正在生成音频。";
            case "SAVING" -> en ? "is saving the audio." : "正在保存音频。";
            case "SUBMISSION_UNKNOWN" -> en ? "is awaiting confirmation from the music service." : "正在核对生成服务的接收状态。";
            case "FAILED" -> en ? "could not be generated." : "这次音频生成失败了。";
            case "CANCELLED", "CANCELED" -> en ? "has a cancelled generation task." : "的音频生成任务已取消。";
            case "LYRICS_READY" -> en ? "has lyrics and no audio for this version yet." : "的歌词已就绪，这一版还没有音频。";
            case "DRAFT" -> en ? "is still a draft." : "仍是草稿，还没有音频。";
            default -> en ? "has no playable audio for this version yet." : "这一版暂时没有可播放的音频。";
        };
        return new Result(en ? title + " " + detail : "《" + title + "》" + detail, "song_status", List.of(), List.of(), "get_current_song");
    }
}
