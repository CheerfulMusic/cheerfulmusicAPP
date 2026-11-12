package com.qingfeng.music.agent.application;

import com.qingfeng.music.agent.api.SongCard;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.generation.api.GenerationApi;
import com.qingfeng.music.generation.domain.GenerationStatus;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.media.api.MediaApi;
import com.qingfeng.music.work.api.WorkApi;
import java.util.*;

/** 歌词、音频与任务必须属于同一版本，不能把新歌词与旧音频拼成作品卡。 */
public final class SongCardProjector {
    private final WorkApi works;
    private final LyricsApi lyrics;
    private final MediaApi media;
    private GenerationApi generation;

    public SongCardProjector(WorkApi works, LyricsApi lyrics, MediaApi media) {
        this.works = works; this.lyrics = lyrics; this.media = media;
    }
    public SongCardProjector withGeneration(GenerationApi generation) { this.generation = generation; return this; }

    public SongCard project(String creatorId, String workId, String snapshotJson) {
        if (workId == null || workId.isBlank()) return SongCard.empty(0L);
        var work = works.get(creatorId, workId);
        var snapshot = snapshotJson == null ? Map.<String,Object>of() : AgentJson.read(snapshotJson);
        String revisionId = text(snapshot.get("revisionId"));
        String taskId = text(snapshot.get("taskId"));
        List<GenerationApi.Task> tasks = generation == null ? List.of() : generation.list(creatorId, workId);
        GenerationApi.Task task = taskId == null ? null : tasks.stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
        var selection = media.selection(creatorId, workId);
        if (task == null && revisionId == null && selection != null && selection.assetId() != null) {
            task = tasks.stream().filter(t -> selection.assetId().equals(t.assetId())).findFirst().orElse(null);
        }
        if (revisionId == null && task != null) revisionId = task.revisionId();
        var revision = revisionId == null ? latest(lyrics.list(creatorId, workId)) : lyrics.get(creatorId, workId, revisionId);
        String audioUrl = "", assetId = null;
        String status = revision == null ? "DRAFT" : "LYRICS_READY";
        if (task != null && revision != null && revision.id().equals(task.revisionId())) {
            status = task.status().name();
            if (task.status() == GenerationStatus.SUCCEEDED && task.assetId() != null) assetId = task.assetId();
        } else if (revision != null && selection != null && selection.assetId() != null
                && media.matchesGenerationRevision(creatorId, workId, selection.assetId(), revision.id())) {
            assetId = selection.assetId(); status = "READY";
        }
        if (assetId != null) {
            media.get(creatorId, workId, assetId);
            audioUrl = "/api/app/works/" + workId + "/audio/" + assetId;
        }
        return new SongCard(workId, revision == null ? work.title() : revision.title(),
                text(snapshot.get("style")) == null ? work.style() : text(snapshot.get("style")),
                revision == null ? "" : revision.lyrics(), audioUrl, status, null, work.version(),
                revision == null ? null : revision.id(), assetId, task == null ? null : task.id());
    }

    public static LyricsApi.Revision latest(List<LyricsApi.Revision> revisions) {
        return revisions.stream().filter(LyricsApi.Revision::confirmed)
                .max(Comparator.comparing(LyricsApi.Revision::createdAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }
    public static String mergeSnapshot(String existingJson, String style, String mood, Integer bpm,
                                       String language, List<String> instruments) {
        Map<String,Object> map = existingJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(AgentJson.read(existingJson));
        if (style != null && !style.isBlank()) map.put("style", style);
        if (mood != null) map.put("mood", mood);
        if (bpm != null) map.put("bpm", Integer.toString(bpm));
        if (language != null && !language.isBlank()) map.put("language", language);
        if (instruments != null) map.put("instruments", String.join("\u0001", instruments));
        return AgentJson.write(map);
    }
    public static String withPointers(String snapshot, String revisionId, String taskId) {
        var map = snapshot == null ? new LinkedHashMap<String,Object>() : new LinkedHashMap<>(AgentJson.read(snapshot));
        map.put("revisionId", revisionId); map.put("taskId", taskId);
        return AgentJson.write(map);
    }
    private static String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
}
