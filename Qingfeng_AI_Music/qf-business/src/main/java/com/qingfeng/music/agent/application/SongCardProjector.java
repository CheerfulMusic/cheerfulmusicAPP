package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把当前的「指针 + 轻量快照」实时聚合成消息流内歌曲卡片。
 * lyrics / audioUrl / coverUrl / title 一律来自 work / lyric_revision / media_asset 实时读取，
 * 不在此处落地副本；style / mood / bpm / language / instruments 来自会话快照（current_song_json）。
 */
public final class SongCardProjector {

    private final WorkApi works;
    private final LyricsApi lyrics;
    private final MediaApi media;

    public SongCardProjector(WorkApi works, LyricsApi lyrics, MediaApi media) {
        this.works = works;
        this.lyrics = lyrics;
        this.media = media;
    }

    public SongCard project(String creatorId, String workId, String snapshotJson) {
        if (workId == null || workId.isBlank()) {
            return SongCard.empty(0L);
        }
        try {
            var work = works.get(creatorId, workId);
            var snapshot = snapshotJson == null ? Map.<String, Object>of() : AgentJson.read(snapshotJson);
            String style = firstNonNull(snapshot.get("style"), work.style());
            String mood = asString(snapshot.get("mood"));
            String language = asString(snapshot.get("language"));
            Integer bpm = asInteger(snapshot.get("bpm"));
            List<String> instruments = asList(snapshot.get("instruments"));

            String title = work.title() == null ? "" : work.title();
            String lyricText = "";
            try {
                lyricText = lyrics.list(creatorId, workId).stream()
                        .filter(com.qingfeng.music.lyrics.api.LyricsApi.Revision::confirmed)
                        .reduce((first, second) -> second)
                        .map(com.qingfeng.music.lyrics.api.LyricsApi.Revision::lyrics)
                        .orElse("");
            } catch (RuntimeException ignored) {
                lyricText = "";
            }

            String audioUrl = "";
            String coverUrl = null;
            String status = "DRAFT";
            try {
                MediaApi.Selection selection = media.selection(creatorId, workId);
                if (selection != null && selection.assetId() != null && !selection.assetId().isBlank()) {
                    audioUrl = "/api/app/works/" + workId + "/audio/" + selection.assetId();
                    status = "READY";
                    if (selection.asset() != null) coverUrl = selection.asset().contentType();
                }
            } catch (RuntimeException ignored) {
                audioUrl = "";
            }

            return new SongCard(workId, title, style == null ? "" : style, lyricText, audioUrl,
                    status, coverUrl, 0L);
        } catch (RuntimeException e) {
            return SongCard.empty(0L);
        }
    }

    /** 合并 set_style 参数到现有快照，返回新快照 JSON（不触碰 lyrics / audioUrl 等实时字段）。 */
    public static String mergeSnapshot(String existingJson, String style, String mood,
                                       Integer bpm, String language, List<String> instruments) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (existingJson != null) {
            Map<String, Object> parsed = AgentJson.read(existingJson);
            map.putAll(parsed);
        }
        if (style != null) map.put("style", style);
        if (mood != null) map.put("mood", mood);
        if (bpm != null) map.put("bpm", Integer.toString(bpm));
        if (language != null) map.put("language", language);
        if (instruments != null) map.put("instruments", String.join("\u0001", instruments));
        return AgentJson.write(map);
    }

    private static String firstNonNull(Object primary, String fallback) {
        if (primary != null && !primary.toString().isBlank()) return primary.toString();
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List) return (List<String>) value;
        String text = value.toString();
        if (text.isBlank()) return List.of();
        return List.of(text.split(java.util.regex.Pattern.quote("\u0001")));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
