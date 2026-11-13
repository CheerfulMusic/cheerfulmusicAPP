package com.qingfeng.music.lyrics.application;

import com.qingfeng.music.common.exception.DomainException;
import java.util.*;
import java.util.regex.Pattern;

/** User-selected section order is a contract, not a reusable melody or lyric text. */
public record LyricsStructure(String templateId, boolean chorusFirst, List<String> customSections) {
    public static final int MAX_CUSTOM_SECTIONS = 24;
    private static final Set<String> CUSTOM_SECTION_NAMES = Set.of(
        "Verse", "Verse 1", "Verse 2", "Pre-Chorus", "Chorus", "Chorus 2", "Bridge", "Interlude");
    private static final Map<String,List<String>> TEMPLATES = Map.of(
        "AAB", List.of("Verse 1","Verse 2","Chorus","Interlude","Verse 2","Chorus","Chorus 2"),
        "ABB", List.of("Verse","Chorus","Chorus 2","Interlude","Verse","Chorus","Chorus 2"),
        "ABC", List.of("Verse","Pre-Chorus","Chorus","Interlude","Pre-Chorus","Chorus","Chorus 2"),
        "ABCD", List.of("Verse","Pre-Chorus","Chorus","Interlude","Pre-Chorus","Chorus","Bridge","Chorus"));

    public LyricsStructure(String templateId, boolean chorusFirst) {
        this(templateId, chorusFirst, null);
    }

    public LyricsStructure {
        templateId = templateId == null ? "AUTO" : templateId;
        if (templateId.equals("CUSTOM")) {
            if (chorusFirst || customSections == null || customSections.size() > MAX_CUSTOM_SECTIONS
                || customSections.stream().anyMatch(section -> section == null || !CUSTOM_SECTION_NAMES.contains(section))
                || customSections.stream().allMatch("Interlude"::equals)) {
                throw new DomainException("INVALID_LYRICS_REQUEST", "请设置有效的自定义段落顺序，最多24段，且至少有一段填写歌词");
            }
            customSections = List.copyOf(customSections);
        } else {
            if (!templateId.equals("AUTO") && !TEMPLATES.containsKey(templateId) || templateId.equals("AUTO") && chorusFirst)
                throw new DomainException("INVALID_LYRICS_REQUEST", "请选择有效的歌词模板；副歌先行需要先选择一种模板");
            if (customSections != null && !customSections.isEmpty())
                throw new DomainException("INVALID_LYRICS_REQUEST", "请先选择自定义歌词模板");
            customSections = null;
        }
    }
    public List<String> sections() {
        if (customSections != null) return customSections;
        var result = new ArrayList<>(TEMPLATES.getOrDefault(templateId,List.of()));
        if (chorusFirst) { result.remove("Chorus"); result.add(0,"Chorus"); }
        return List.copyOf(result);
    }
    public String instruction() {
        if (templateId.equals("AUTO")) return "";
        return "\n【段落结构约束】\n必须严格按以下段落顺序输出，不增删或合并段落，每个标签独占一行：\n"
            + String.join(" → ", sections().stream().map(s -> "["+s+"]").toList())
            + "\n[Interlude]为器乐间奏，只输出该标签，不填写歌词或说明。其余每段必须有歌词。"
            + "Chorus 2是有发展变化的第二副歌，不是Bridge。重复段落保留对应标签。"
            + "可保持副歌记忆点，主歌推动叙事；模板只规定结构，不套用固定歌词。"
            + (chorusFirst ? "副歌先行已经体现在上述顺序中：首个Chorus移到开头，原位置不再重复添加。" : "");
    }
    public void validate(String lyrics) {
        if (templateId.equals("AUTO")) return;
        var headers = Pattern.compile("(?m)^\\s*\\[([^]\\r\\n]+)]\\s*$").matcher(lyrics);
        var found = new ArrayList<String>();
        int end = 0; String last = null;
        while (headers.find()) {
            if (!validBody(last,lyrics.substring(end,headers.start()))) invalid();
            last = headers.group(1).trim(); found.add(last.toLowerCase(Locale.ROOT)); end = headers.end();
        }
        if (last == null || !validBody(last,lyrics.substring(end))
            || !found.equals(sections().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList())) invalid();
    }
    private static boolean validBody(String section, String body) {
        return section == null || section.equalsIgnoreCase("Interlude") ? body.isBlank() : !body.isBlank();
    }
    private static void invalid() { throw new DomainException("LYRICS_STRUCTURE_MISMATCH", "本次歌词未符合所选模板，未保存新版本，也未自动重试；原歌词保留，可调整要求后重新生成"); }
}
