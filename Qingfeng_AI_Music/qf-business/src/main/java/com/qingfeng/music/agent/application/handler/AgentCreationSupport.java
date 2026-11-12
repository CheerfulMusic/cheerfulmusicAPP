package com.qingfeng.music.agent.application.handler;

import com.qingfeng.music.agent.application.AgentToolHandler.ToolContext;
import com.qingfeng.music.agent.application.SongCardProjector;
import com.qingfeng.music.agent.model.AgentJson;
import com.qingfeng.music.common.exception.DomainException;
import com.qingfeng.music.lyrics.api.LyricsApi;
import com.qingfeng.music.settings.MusicSettings;
import com.qingfeng.music.work.api.WorkApi;
import java.util.*;

/** 把会话中的音乐要求写入真实音乐设置；请求提交仍由既有服务负责预算和幂等。 */
final class AgentCreationSupport {
    private AgentCreationSupport() {}
    static String snapshot(ToolContext ctx) { return ctx.state() == null ? null : ctx.state().currentSongJson(); }
    static String value(String json, String key) { Object v = json == null ? null : AgentJson.read(json).get(key); return v == null ? null : v.toString(); }
    static String vocal(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT); }
    static String language(String requested, String snapshot, LyricsApi.Revision revision) {
        if (requested != null && !requested.isBlank()) return requested;
        String saved = value(snapshot, "language");
        if (saved != null) return saved;
        return revision != null && "en".equals(revision.language()) ? "en" : "zh";
    }
    static String lyricLanguage(String language) { return "en".equals(language) ? "en" : "zh-CN"; }
    static String instructions(String text, String language) {
        String requirement = text == null ? "" : text;
        if (requirement.length() > 900) throw new DomainException("INVALID_TOOL_ARGUMENT", "改词要求请控制在900字以内。");
        return "yue".equals(language) ? requirement + "\n请用地道粤语写歌词，按粤语发音与用词组织韵脚。" : requirement;
    }
    static LyricsApi.Revision selected(LyricsApi lyrics, String owner, String work, String requested, String snapshot) {
        String id = requested == null || requested.isBlank() ? value(snapshot, "revisionId") : requested;
        return id == null || id.isBlank() ? SongCardProjector.latest(lyrics.list(owner, work)) : lyrics.get(owner, work, id);
    }
    record Applied(WorkApi.Work work, String snapshot) {}
    static Applied apply(MusicSettings settings, WorkApi works, String owner, WorkApi.Work work, String snapshot, String voice) {
        if (settings == null) throw new DomainException("MUSIC_SETTINGS_UNAVAILABLE", "音乐设置暂不可用，尚未提交生成。");
        Map<String,Object> state = snapshot == null ? new LinkedHashMap<>() : new LinkedHashMap<>(AgentJson.read(snapshot));
        MusicSettings.Direction existing = settings.resolve(owner, work);
        MusicSettings.Preferences prior = existing == null ? null : existing.preferences();
        if (prior == null && !state.containsKey("style") && work.style() != null && !work.style().isBlank()) state.put("style", work.style());
        Map<String,List<String>> choices = new LinkedHashMap<>(prior == null ? Map.of() : prior.choices());
        if (!state.containsKey("baseFreeText")) state.put("baseFreeText", prior == null ? "" : prior.freeText().split("Agent: ", 2)[0].strip());
        var directions = new ArrayList<String>();
        String base = Objects.toString(state.get("baseFreeText"), "");

        add(directions, state, "style", "Style: ");
        add(directions, state, "mood", "Mood: ");
        add(directions, state, "bpm", "BPM: ");
        String language = Objects.toString(state.get("language"), "");
        if (!language.isBlank()) directions.add("Singing language: " + switch(language) {case "yue" -> "Cantonese"; case "en" -> "English"; default -> "Mandarin Chinese";});
        String instruments = Objects.toString(state.get("instruments"), "").replace('\u0001', ',');
        if (!instruments.isBlank()) directions.add("Instruments: " + instruments);
        if (state.containsKey("style")) choices.put("genre", List.of());
        if (state.containsKey("mood")) choices.put("emotion", List.of());
        if (state.containsKey("bpm")) choices.put("tempo", List.of());
        if (state.containsKey("instruments")) choices.put("instruments", List.of());
        var requested = new MusicSettings.Preferences(1, null, choices, vocal(voice, vocal(Objects.toString(state.get("vocal"), null), work.vocalGender())),
                (base.isBlank() ? "" : base + "\n") + "Agent: " + String.join("; ", directions), prior == null ? "" : prior.negativeText());
        // Normalize before comparing so retries do not increment workVersion again.
        var normalized = settings.preview(requested).preferences();
        if (!Objects.equals(prior, normalized)) work = settings.save(owner, work.id(), work.version(), normalized).work();
        state.put("vocal", work.vocalGender());
        return new Applied(work, AgentJson.write(state));
    }
    private static void add(List<String> result, Map<String,Object> state, String key, String label) {
        String value = Objects.toString(state.get(key), ""); if (!value.isBlank()) result.add(label + value);
    }
}
